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
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;

import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;

import cc.calliope.mini.MyDeviceKt;
import cc.calliope.mini.PatternMatrixView;
import cc.calliope.mini.ScanViewModelKt;
import cc.calliope.mini.views.FobParams;
import cc.calliope.mini.R;
import cc.calliope.mini.databinding.DialogPatternBinding;
import cc.calliope.mini.utils.Utils;
import no.nordicsemi.android.kotlin.ble.core.scanner.BleScanResults;

import androidx.preference.PreferenceManager;

public class PatternDialogFragment extends DialogFragment {
    private final static int DIALOG_WIDTH = 220; //dp
    private final static int DIALOG_HEIGHT = 240; //dp
    private static final String FOB_PARAMS_PARCELABLE = "fob_params_parcelable";
    private DialogPatternBinding binding;
    private String currentPattern;
    private String currentAddress;
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
        context = getContext();
        if (context == null) {
            dismiss();
        }

        context.registerReceiver(bluetoothStateBroadcastReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
        binding = DialogPatternBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        customizeDialog(view);

        loadCurrentDevice();

        PatternMatrixView patternView = binding.patternView;

        patternView.setPattern(currentPattern);
        patternView.setOnPatternChangeListener(pattern -> {
            binding.buttonAction.setBackgroundResource(R.drawable.btn_connect_aqua);
            currentPattern = pattern;
        });

        ScanViewModelKt scanViewModelKt = new ViewModelProvider(this).get(ScanViewModelKt.class);
        scanViewModelKt.getDevices().observe(this, new Observer<List<BleScanResults>>() {
            @Override
            public void onChanged(List<BleScanResults> scanResults) {
                for (BleScanResults results : scanResults) {
                    MyDeviceKt device = new MyDeviceKt(results);

                    if (!device.getPattern().isEmpty() && device.getPattern().equals(currentPattern)) {
                        currentAddress = device.getAddress();
                        binding.buttonAction.setBackgroundResource(device.isActual() ? R.drawable.btn_connect_green : R.drawable.btn_connect_aqua);

                        Log.println(Log.DEBUG, "DIALOG",
                                "address: " + device.getAddress() + ", " +
                                        "pattern: " + device.getPattern() + ", " +
                                        "bonded: " + device.isBonded() + ", " +
                                        "actual: " + device.isActual());
                    }
                }
            }
        });
        scanViewModelKt.startScan();

        binding.buttonAction.setOnClickListener(this::onConnectClick);
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
                Log.v("DIALOG", String.format("Window size: width %d; height %d", view.getWidth(), view.getHeight()));
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
        saveCurrentDevice();
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

    public void saveCurrentDevice() {
        SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(context).edit();
        editor.putString("DEVICE_ADDRESS", currentAddress);
        editor.putString("DEVICE_PATTERN", currentPattern);
        editor.apply();
    }

    public void loadCurrentDevice() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        currentAddress = preferences.getString("DEVICE_ADDRESS", "");
        currentPattern = preferences.getString("DEVICE_PATTERN", "ZUZUZ");
    }
}