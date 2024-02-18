package cc.calliope.mini.dialog.pattern;

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
import android.os.SystemClock;
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
import cc.calliope.mini.PatternMatrixView;
import cc.calliope.mini.ScanViewModelKt;
import cc.calliope.mini.utils.StaticExtras;
import cc.calliope.mini.views.FobParams;
import cc.calliope.mini.R;
import cc.calliope.mini.databinding.DialogPatternBinding;
import cc.calliope.mini.utils.Utils;
import no.nordicsemi.android.kotlin.ble.core.ServerDevice;
import no.nordicsemi.android.kotlin.ble.core.scanner.BleScanResultData;
import no.nordicsemi.android.kotlin.ble.core.scanner.BleScanResults;

import androidx.preference.PreferenceManager;

import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PatternDialogFragment extends DialogFragment {
    private static final int RELEVANT_LIMIT = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O ? 5 : 10;
    private final static int DIALOG_WIDTH = 220; //dp
    private final static int DIALOG_HEIGHT = 240; //dp
    private static final String FOB_PARAMS_PARCELABLE = "fob_params_parcelable";
    private static final String TAG = "PatternDialogFragment";
    private DialogPatternBinding binding;
    private String paintedPattern;
    private String currentPattern;
    private String currentAddress;
    //private String devicePattern;
    //private String deviceAddress;
    private boolean isDeviceActual;
    private boolean isDeviceAvailable;
    private Context context;

    private record Position(int x, int y) {
    }

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

        loadCurrentDevice();
        patternView.setPattern(currentPattern);
        patternView.setOnPatternChangeListener(pattern -> {
            binding.buttonAction.setBackgroundResource(R.drawable.btn_connect_aqua);
            paintedPattern = pattern;
        });

        ScanViewModelKt scanViewModelKt = new ViewModelProvider(this).get(ScanViewModelKt.class);
        scanViewModelKt.getDevices().observe(this, devices -> {
            for (DeviceKt device : devices) {
                isDeviceAvailable = false;

                if(paintedPattern != null){
                    if(paintedPattern.equals(device.getPattern())){
                        currentAddress = device.getAddress();
                        currentPattern = device.getPattern();
                        setBackGroundResource(device);
                    } else if(currentPattern.equals(paintedPattern) && currentAddress.equals(device.getAddress())){
                        setBackGroundResource(device);
                    }
                }else if(currentAddress.equals(device.getAddress())){
                    setBackGroundResource(device);
                }else if(currentPattern.equals(device.getPattern())){
                    currentAddress = device.getAddress();
                    setBackGroundResource(device);
                }
            }
        });
        scanViewModelKt.startScan();

        binding.buttonAction.setOnClickListener(this::onConnectClick);
    }

    private void setBackGroundResource(DeviceKt device) {
        isDeviceActual = device.isActual();
        binding.buttonAction.setBackgroundResource(isDeviceActual ? R.drawable.btn_connect_green : R.drawable.btn_connect_aqua);
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

    public void onConnectClick(View view) {
        if(isDeviceActual){
            saveCurrentDevice();
        }
        dismiss();
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

    private String getPattern(ServerDevice device) {
        String address = device.getAddress();
        String name = device.getName();
        if (name == null) {
            return "";
        }
        String pattern = "[a-zA-Z :]+\\[([A-Z]{5})]";
        name = name.toUpperCase();
        Pattern compiledPattern = Pattern.compile(pattern);
        Matcher matcher = compiledPattern.matcher(name);
        if (matcher.find()) {
            return matcher.group(1);
//        } else if (currentAddress.equals(address)) {
//            return currentPattern;
        } else {
            return "";
        }
    }

    public boolean isDeviceActual(BleScanResultData bleScanResultData){
        if (bleScanResultData != null) {
            long timeSinceBoot = nanosecondsToSeconds(SystemClock.elapsedRealtimeNanos());
            long timeSinceScan = nanosecondsToSeconds(bleScanResultData.getTimestampNanos());
            return timeSinceBoot - timeSinceScan < RELEVANT_LIMIT;
        }
        return false;
    }

    private long nanosecondsToSeconds(long nanoseconds) {
        return TimeUnit.NANOSECONDS.toSeconds(nanoseconds);
    }

    public void saveCurrentDevice() {
        SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(context).edit();
        editor.putString(StaticExtras.DEVICE_ADDRESS, currentAddress);
        editor.putString(StaticExtras.DEVICE_PATTERN, currentPattern);
        editor.apply();
    }

    public void loadCurrentDevice() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        currentAddress = preferences.getString(StaticExtras.DEVICE_ADDRESS, "");
        currentPattern = preferences.getString(StaticExtras.DEVICE_PATTERN, "ZUZUZ");
    }
}