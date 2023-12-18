package cc.calliope.mini.dialog.pattern;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
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

import java.util.Arrays;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;
import cc.calliope.mini.views.FobParams;
import cc.calliope.mini.R;
import cc.calliope.mini.ExtendedBluetoothDevice;
import cc.calliope.mini.databinding.DialogPatternBinding;
import cc.calliope.mini.utils.Utils;
import cc.calliope.mini.viewmodels.ScannerLiveData;
import cc.calliope.mini.viewmodels.ScannerViewModel;

public class PatternDialogFragment extends DialogFragment {
    private final static int DIALOG_WIDTH = 220; //dp
    private final static int DIALOG_HEIGHT = 240; //dp
    private static final String FOB_PARAMS_PARCELABLE = "fob_params_parcelable";
    private DialogPatternBinding binding;
    private Float[] oldPattern = {0f, 0f, 0f, 0f, 0f};
    private Float[] currentPattern = {0f, 0f, 0f, 0f, 0f};

    private boolean connectClicked = false;

    private ScannerViewModel scannerViewModel;
//    private ExtendedBluetoothDevice currentDevice;

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
        binding = DialogPatternBinding.inflate(inflater, container, false);

        // Create view model containing utility methods for scanning
        scannerViewModel = new ViewModelProvider(requireActivity()).get(ScannerViewModel.class);
        scannerViewModel.getScannerState().observe(getViewLifecycleOwner(), this::scanResults);

        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        customizingDialog(view);

        oldPattern = scannerViewModel.getScannerState().getCurrentPattern();
        currentPattern = Arrays.copyOf(oldPattern, oldPattern.length);

        binding.patternMatrix.columnA.setValue(currentPattern[0]);
        binding.patternMatrix.columnB.setValue(currentPattern[1]);
        binding.patternMatrix.columnC.setValue(currentPattern[2]);
        binding.patternMatrix.columnD.setValue(currentPattern[3]);
        binding.patternMatrix.columnE.setValue(currentPattern[4]);

        binding.patternMatrix.columnA.setOnChangeListener((bar, v, b) -> onPatternChange(0, v));
        binding.patternMatrix.columnB.setOnChangeListener((bar, v, b) -> onPatternChange(1, v));
        binding.patternMatrix.columnC.setOnChangeListener((bar, v, b) -> onPatternChange(2, v));
        binding.patternMatrix.columnD.setOnChangeListener((bar, v, b) -> onPatternChange(3, v));
        binding.patternMatrix.columnE.setOnChangeListener((bar, v, b) -> onPatternChange(4, v));

        binding.buttonAction.setOnClickListener(view1 -> onConnectClick());
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    private void customizingDialog(View view) {
        Dialog dialog = getDialog();
        if (dialog != null) {
            Window window = dialog.getWindow();
            window.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));

            Bundle bundle = getArguments();
            if (bundle != null) {
                FobParams fobParams = bundle.getParcelable(FOB_PARAMS_PARCELABLE);

                // set "origin" to top left corner, so to speak
                window.setGravity(Gravity.TOP | Gravity.START);
                // after that, setting values for x and y works "naturally"
                WindowManager.LayoutParams layoutParams = window.getAttributes();
                layoutParams.x = getPosition(window, fobParams).getX();
                layoutParams.y = getPosition(window, fobParams).getY();

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
    }

    @Override
    public void onStop() {
        super.onStop();
//        scannerViewModel.stopScan();
    }

    @Override
    public void onDismiss(@NonNull final DialogInterface dialog) {
        super.onDismiss(dialog);
//        if (scannerViewModel.getScannerState().getCurrentDevice() != null) {
//            pairDevice(scannerViewModel.getScannerState().getCurrentDevice().getDevice());
//        }
        if(!connectClicked){
            scannerViewModel.setCurrentPattern(oldPattern);
        }
//        else {
//            scannerViewModel.createBond();
//        }

        final Activity activity = getActivity();
        if (activity instanceof DialogInterface.OnDismissListener) {
            ((DialogInterface.OnDismissListener) activity).onDismiss(dialog);
        }
    }

    private void onPatternChange(int index, float newPatten) {
        currentPattern[index] = newPatten;
        scannerViewModel.setCurrentPattern(currentPattern);
    }

    private void setButtonBackground(ExtendedBluetoothDevice device) {
//        Log.i("DIALOG: ", "currentDevice: " + device);
        if (device != null && device.isRelevant()) {
            binding.buttonAction.setBackgroundResource(R.drawable.btn_connect_green);
        } else {
            binding.buttonAction.setBackgroundResource(R.drawable.btn_connect_aqua);
        }
    }

    // Call this method to send the data back to the parent fragment
    public void onConnectClick() {
        connectClicked = true;
        dismiss();
    }

    private void scanResults(final ScannerLiveData state) {
        // Bluetooth must be enabled
        if (state.isBluetoothEnabled()) {
//            scannerViewModel.startScan();
            setButtonBackground(state.getCurrentDevice());
        } else {
            setButtonBackground(null);
        }
    }

//    public void savePattern() {
//        SharedPreferences.Editor edit = PreferenceManager.getDefaultSharedPreferences(getActivity()).edit();
//        for (int i = 0; i < 5; i++) {
//            edit.putFloat("PATTERN_" + i, currentPattern.get(i));
//        }
//        edit.apply();
//    }
//
//    public void loadPattern() {
//        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getActivity());
//        for (int i = 0; i < 5; i++) {
//            currentPattern.set(i, preferences.getFloat("PATTERN_" + i, 0f));
//        }
//    }

    private Position getPosition(Window window, FobParams fobParams) {
        Activity activity = getActivity();
        if (activity != null) {
            DisplayMetrics displayMetrics = new DisplayMetrics();
            window.getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);

            int halfWidth = displayMetrics.widthPixels / 2;
            int halfHeight = displayMetrics.heightPixels / 2;
            int fobX = fobParams.getCenterX();
            int fobY = fobParams.getCenterY();
            int dialogWidth = Utils.convertDpToPixel(getActivity(), DIALOG_WIDTH);
            int dialogHeight = Utils.convertDpToPixel(getActivity(), DIALOG_HEIGHT);

            if (fobX <= halfWidth && fobY <= halfHeight) {
                return new Position(fobX, fobY);
            } else if (fobX > halfWidth && fobY <= halfHeight) {
                return new Position(fobX - dialogWidth, fobY);
            } else if (fobX <= halfWidth) {
                return new Position(fobX, fobY - dialogHeight);
            } else {
                return new Position(fobX - dialogWidth, fobY - dialogHeight);
            }
        }
        return new Position(0, 0);
    }

    private static class Position {
        private final int x;
        private final int y;

        public Position(int x, int y) {
            this.x = x;
            this.y = y;
        }

        public int getX() {
            return x;
        }

        public int getY() {
            return y;
        }
    }
}