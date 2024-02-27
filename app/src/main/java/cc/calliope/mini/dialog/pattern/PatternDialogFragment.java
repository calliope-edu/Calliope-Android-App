package cc.calliope.mini.dialog.pattern;

import static cc.calliope.mini.BondingService.EXTRA_DEVICE_ADDRESS;

import android.app.Activity;
import android.app.Dialog;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;

import cc.calliope.mini.DeviceKt;
import cc.calliope.mini.BondingService;
import cc.calliope.mini.PatternMatrixView;
import cc.calliope.mini.ScanViewModelKt;
import cc.calliope.mini.utils.BluetoothUtils;
import cc.calliope.mini.utils.StaticExtras;
import cc.calliope.mini.views.FobParams;
import cc.calliope.mini.R;
import cc.calliope.mini.databinding.DialogPatternBinding;
import cc.calliope.mini.utils.Utils;

import androidx.preference.PreferenceManager;

import java.util.List;

public class PatternDialogFragment extends DialogFragment {
    private final static int DIALOG_WIDTH = 220; //dp
    private final static int DIALOG_HEIGHT = 300; //dp
    private static final String FOB_PARAMS_PARCELABLE = "fob_params_parcelable";
    private static final String TAG = "PatternDialogFragment";
    private DialogPatternBinding binding;
    private DeviceKt currentDevice;
    private String currentAddress;
    private String currentPattern;
    private String paintedPattern;
    private Context context;
    private record Position(int x, int y) {
    }

    private final BroadcastReceiver bluetoothStateBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF);
            final int previousState = intent.getIntExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE, BluetoothAdapter.STATE_OFF);

            switch (state) {
                case BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    if (previousState != BluetoothAdapter.STATE_TURNING_OFF && previousState != BluetoothAdapter.STATE_OFF) {
                        dismiss();
                    }
                }
            }
        }
    };

    public PatternDialogFragment() {
        // Empty constructor is required for DialogFragment
        // Make sure not to add arguments to the constructor
        // Use `newInstance` instead as shown below
    }

    public static PatternDialogFragment newInstance(FobParams params) {
        PatternDialogFragment patternDialogFragment = new PatternDialogFragment();

        Bundle args = new Bundle();
        args.putParcelable(FOB_PARAMS_PARCELABLE, params);
        patternDialogFragment.setArguments(args);

        return patternDialogFragment;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        context = requireContext();
        context.registerReceiver(bluetoothStateBroadcastReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
        binding = DialogPatternBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        customizeDialog(view);

        PatternMatrixView patternView = binding.patternView;

        restoreCurrentDevice();
      //  onPatternChange(currentPattern);
        patternView.setPattern(currentPattern);
        binding.textTitle.setText(currentPattern);
        patternView.setOnPatternChangeListener(this::onPatternChange);

        ScanViewModelKt scanViewModelKt = new ViewModelProvider(this).get(ScanViewModelKt.class);
        scanViewModelKt.getDevices().observe(this, this::onDevicesDiscovered);
        scanViewModelKt.startScan();

        binding.buttonAction.setOnClickListener(this::onActionClick);
        binding.buttonRemove.setOnClickListener(this::onRemoveClick);
    }

    @Override
    public void onDismiss(@NonNull final DialogInterface dialog) {
        super.onDismiss(dialog);

        final Activity activity = getActivity();
        if (activity instanceof DialogInterface.OnDismissListener) {
            ((DialogInterface.OnDismissListener) activity).onDismiss(dialog);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        context.unregisterReceiver(bluetoothStateBroadcastReceiver);
        binding = null;
    }

    private void customizeDialog(View view) {
        Dialog dialog = getDialog();
        if (dialog == null) return;

        Window window = dialog.getWindow();
        if (window == null) return;

        window.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
        window.setGravity(Gravity.TOP | Gravity.START);

        Bundle bundle = getArguments();
        if (bundle != null) {
            FobParams fobParams = bundle.getParcelable(FOB_PARAMS_PARCELABLE);
            Position position = calculateDialogPosition(window, fobParams);

            WindowManager.LayoutParams layoutParams = window.getAttributes();
            layoutParams.x = position.x;
            layoutParams.y = position.y;
            window.setAttributes(layoutParams);
        }

        view.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                view.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                Utils.log(Log.DEBUG, TAG, String.format("Window size: width %d; height %d", view.getWidth(), view.getHeight()));
            }
        });
    }

    private Position calculateDialogPosition(Window window, FobParams fobParams) {
        DisplayMetrics displayMetrics = new DisplayMetrics();
        window.getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);

        int dialogWidth = Utils.convertDpToPixel(context, DIALOG_WIDTH);
        int dialogHeight = Utils.convertDpToPixel(context, DIALOG_HEIGHT);
        int halfWidth = displayMetrics.widthPixels / 2;
        int halfHeight = displayMetrics.heightPixels / 2;

        int fobX = fobParams.getCenterX();
        int fobY = fobParams.getCenterY();

        int posX = fobX > halfWidth ? fobX - dialogWidth : fobX;
        int posY = fobY > halfHeight ? fobY - dialogHeight : fobY;

        return new Position(posX, posY);
    }

    private void saveCurrentDevice() {
        SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(context).edit();
        editor.putString(StaticExtras.CURRENT_DEVICE_ADDRESS, currentDevice.getAddress());
        editor.putString(StaticExtras.CURRENT_DEVICE_PATTERN, currentDevice.getPattern());
        editor.apply();
    }

    private void restoreCurrentDevice() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        currentAddress = preferences.getString(StaticExtras.CURRENT_DEVICE_ADDRESS, "");
        currentPattern = preferences.getString(StaticExtras.CURRENT_DEVICE_PATTERN, "ZUZUZ");
    }

    private void onActionClick(View view){
        if(currentDevice != null && currentDevice.isActual()){
            saveCurrentDevice();
            if(!currentDevice.isBonded()) {
                BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
                if (bluetoothAdapter == null) {
                    return;
                }

                Intent service = new Intent(context, BondingService.class);
                service.putExtra(EXTRA_DEVICE_ADDRESS, currentDevice.getAddress());
                getActivity().startService(service);
            }
        }
        dismiss();
    }

    private void onRemoveClick(View view) {
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            return;
        }
        String address = currentDevice == null ? currentAddress : currentDevice.getAddress();
        BluetoothUtils.removeBond(bluetoothAdapter.getRemoteDevice(address));
    }

    private void onPatternChange(String pattern) {
        binding.textTitle.setText(pattern);
        currentPattern = pattern;
    }

    private void onDevicesDiscovered(List<DeviceKt> devices) {
        currentDevice = null;
        for (DeviceKt device : devices) {
            if (currentPattern.equals(device.getPattern())) {
                currentDevice = device;
            }
        }

        if (currentDevice != null) {
            Utils.log(Log.DEBUG, "DEVICES", "Device: " +
                    currentDevice.getAddress() + " " +
                    currentDevice.getName() + " " +
                    currentDevice.isBonded() + " " +
                    currentDevice.isActual());
            setupButtons(currentDevice.isBonded(), currentDevice.isActual());
            binding.textTitle.setText(currentDevice.getPattern() + (currentDevice.isBonded() ? " connected" : ""));
        } else {
            setupButtons(false, false);
            binding.textTitle.setText(currentPattern);
        }
    }

    private void setupButtons(boolean isBonded, boolean isActual){
        binding.buttonRemove.setVisibility(isBonded ? View.VISIBLE : View.GONE);
        binding.buttonAction.setBackgroundResource(!isBonded && isActual ? R.drawable.btn_connect_green : R.drawable.btn_aqua);
        binding.buttonAction.setText(!isBonded && isActual ? "" : "Cancel");
    }
}