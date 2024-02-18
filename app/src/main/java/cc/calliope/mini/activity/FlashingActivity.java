package cc.calliope.mini.activity;

import static android.bluetooth.BluetoothDevice.BOND_BONDED;
import static android.bluetooth.BluetoothDevice.BOND_BONDING;
import static android.bluetooth.BluetoothDevice.BOND_NONE;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.google.android.material.snackbar.Snackbar;

import cc.calliope.mini.FlashingService;
import cc.calliope.mini.ProgressCollector;
import cc.calliope.mini.ProgressListener;
import cc.calliope.mini.R;
import cc.calliope.mini.databinding.ActivityDfuBinding;
import cc.calliope.mini.service.DfuService;
import cc.calliope.mini.utils.Utils;
import cc.calliope.mini.utils.Version;
import cc.calliope.mini.views.BoardProgressBar;

public class FlashingActivity extends AppCompatActivity implements ProgressListener {
    private static final String TAG = "FlashingActivity";
    private static final int SNACKBAR_DURATION = 10000; // how long to display the snackbar message.
    private static final int DELAY_TO_FINISH_ACTIVITY = 5000; // delay to finish activity after flashing
    private ActivityDfuBinding binding;
    private TextView title;
    private TextView status;
    private BoardProgressBar progressBar;
    private ProgressCollector progressCollector;
    private final Handler timerHandler = new Handler();
    private final Runnable deferredFinish = this::finish;

    ActivityResultLauncher<Intent> bluetoothEnableResultLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                Intent serviceIntent = new Intent(this, FlashingService.class);
                startService(serviceIntent);
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if(!Utils.isBluetoothEnabled()){
            showBluetoothDisabledWarning();
        }

        binding = ActivityDfuBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        status = binding.statusTextView;
        title = binding.titleTextView;
        progressBar = binding.progressBar;

        binding.retryButton.setOnClickListener(this::onRetryClicked);

        progressCollector = new ProgressCollector(this);
        getLifecycle().addObserver(progressCollector);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        binding = null;
    }

    @Override
    public void onDfuAttempt() {
    }

    @Override
    public void onConnectionFailed() {
        binding.retryButton.setVisibility(View.VISIBLE);
        status.setText(R.string.error_snackbar_no_connected);
        Utils.errorSnackbar(binding.getRoot(), getString(R.string.error_snackbar_no_connected)).show();
    }

    @Override
    public void onProgressUpdate(int progress) {
        switch (progress) {
            case DfuService.PROGRESS_CONNECTING ->
                    status.setText(R.string.flashing_device_connecting);
            case DfuService.PROGRESS_STARTING ->
                    status.setText(R.string.flashing_process_starting);
            case DfuService.PROGRESS_ENABLING_DFU_MODE ->
                    status.setText(R.string.flashing_enabling_dfu_mode);
            case DfuService.PROGRESS_VALIDATING ->
                    status.setText(R.string.flashing_firmware_validating);
            case DfuService.PROGRESS_DISCONNECTING ->
                    status.setText(R.string.flashing_device_disconnecting);
            case DfuService.PROGRESS_ABORTED ->
                    status.setText(R.string.flashing_aborted);
            case DfuService.PROGRESS_COMPLETED -> {
                title.setText(String.format(getString(R.string.flashing_percent), 100));
                status.setText(R.string.flashing_completed);
                progressBar.setProgress(progress);
                finishActivity();
            }
            default -> {
                title.setText(String.format(getString(R.string.flashing_percent), progress));
                status.setText(R.string.flashing_uploading);
                progressBar.setProgress(progress);
            }
        }
    }

    @Override
    public void onHardwareVersionReceived(int hardwareVersion) {

    }

    @Override
    public void onBluetoothBondingStateChanged(@NonNull BluetoothDevice device, int bondState, int previousBondState) {
        //if (!currentDevice.getAddress().equals(device.getAddress())) {
        //    return;
        //}
        title.setText("");

        switch (bondState) {
            case BOND_BONDING -> status.setText(R.string.bonding_started);
            case BOND_BONDED -> status.setText(R.string.bonding_succeeded);
            case BOND_NONE -> status.setText(R.string.bonding_not_succeeded);
        }
    }

    @Override
    public void onError(int code, String message) {
        progressBar.setProgress(0);
        binding.retryButton.setVisibility(View.VISIBLE);
        String error = String.format(getString(R.string.flashing_error), code, message);
        Utils.errorSnackbar(binding.getRoot(), error).show();
//        progress.setText(String.format(getString(R.string.flashing_error), code));
        status.setText(error);
        Utils.log(Log.ERROR, TAG, "ERROR " + code + ", " + message);
    }

    private void onRetryClicked(View view) {
        view.setVisibility(View.INVISIBLE);
        Intent serviceIntent = new Intent(this, FlashingService.class);
//        serviceIntent.putExtra(StaticExtra.EXTRA_FILE_PATH, file.getAbsolutePath());
        startService(serviceIntent);
        //initFlashing();
    }

    private void showBluetoothDisabledWarning() {
        Snackbar snackbar = Utils.errorSnackbar(binding.getRoot(), getString(R.string.error_snackbar_bluetooth_disable));
        snackbar.setDuration(SNACKBAR_DURATION)
                .setAction(R.string.button_enable, this::startBluetoothEnableActivity)
                .show();
    }

    public void startBluetoothEnableActivity(View view) {
        Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
        bluetoothEnableResultLauncher.launch(enableBtIntent);
    }

    private void finishActivity() {
        timerHandler.postDelayed(deferredFinish, DELAY_TO_FINISH_ACTIVITY);
    }
}