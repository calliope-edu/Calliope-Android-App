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
    private TextView progress;
    private TextView status;
    private BoardProgressBar progressBar;
    private ProgressCollector progressCollector;
    private final Handler timerHandler = new Handler();
    private final Runnable deferredFinish = this::finish;

    ActivityResultLauncher<Intent> bluetoothEnableResultLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                //initFlashing();
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        //Utils.errorSnackbar(binding.getRoot(), getString(R.string.error_snackbar_no_connected)).show();

        binding = ActivityDfuBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        status = binding.statusTextView;
        progress = binding.progressTextView;
        progressBar = binding.progressBar;

        binding.retryButton.setOnClickListener(this::onRetryClicked);

        progressCollector = new ProgressCollector(this);
        getLifecycle().addObserver(progressCollector);
        progressCollector.registerReceivers();
    }

    @Override
    protected void onDestroy() {
        binding = null;
        super.onDestroy();
        progressCollector.unregisterReceivers();
    }

    @Override
    public void onDeviceConnecting() {
        status.setText(R.string.flashing_device_connecting);
        Utils.log(Log.WARN, TAG, "onDeviceConnecting");
    }

    @Override
    public void onProcessStarting() {
        status.setText(R.string.flashing_process_starting);
        Utils.log(Log.WARN, TAG, "onProcessStarting");
    }

    @Override
    public void onAttemptDfuMode() {
    }

    @Override
    public void onEnablingDfuMode() {
        status.setText(R.string.flashing_enabling_dfu_mode);
        Utils.log(Log.WARN, TAG, "onEnablingDfuMode");
    }

    @Override
    public void onFirmwareValidating() {
        status.setText(R.string.flashing_firmware_validating);
        Utils.log(Log.WARN, TAG, "onFirmwareValidating");
    }

    @Override
    public void onDeviceDisconnecting() {
        status.setText(R.string.flashing_device_disconnecting);
        finishActivity();
        Utils.log(Log.WARN, TAG, "onDeviceDisconnecting");
    }

    @Override
    public void onCompleted() {
        progress.setText(String.format(getString(R.string.flashing_percent), 100));
        status.setText(R.string.flashing_completed);
        progressBar.setProgress(DfuService.PROGRESS_COMPLETED);
        Utils.log(Log.WARN, TAG, "onCompleted");
    }

    @Override
    public void onAborted() {
        status.setText(R.string.flashing_aborted);
        Utils.log(Log.WARN, TAG, "onAborted");
    }

    @Override
    public void onProgressChanged(int percent) {
        if (percent >= 0 && percent <= 100) {
            progress.setText(String.format(getString(R.string.flashing_percent), percent));
            status.setText(R.string.flashing_uploading);
            progressBar.setProgress(percent);
        }
    }

    @Override
    public void onStartDfuService(int hardwareVersion) {
    }

    @Override
    public void onBonding(@NonNull BluetoothDevice device, int bondState, int previousBondState) {
        //if (!currentDevice.getAddress().equals(device.getAddress())) {
        //    return;
        //}
        progress.setText("");

        switch (bondState) {
            case BOND_BONDING -> status.setText(R.string.bonding_started);
            case BOND_BONDED -> status.setText(R.string.bonding_succeeded);
            case BOND_NONE -> status.setText(R.string.bonding_not_succeeded);
        }
    }

    @Override
    public void onError(int code, String message) {
        if (code == 4110) {
            if ((Version.VERSION_S_AND_NEWER && ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                    && ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED) {
                Utils.log(Log.ERROR, TAG, "BLUETOOTH permission no granted");
                return;
            }
//            currentDevice.createBond();
        }
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