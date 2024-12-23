package cc.calliope.mini.activity;

import static cc.calliope.mini.core.state.State.STATE_IDLE;

import android.animation.ObjectAnimator;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.drawable.ColorDrawable;
import android.icu.util.Calendar;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.ResultReceiver;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.PopupWindow;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.Observer;
import androidx.preference.PreferenceManager;

import com.google.android.material.snackbar.BaseTransientBottomBar;

import java.util.ArrayList;
import java.util.List;

import cc.calliope.mini.SnackbarHelper;
import cc.calliope.mini.core.bluetooth.CheckService;
import cc.calliope.mini.core.bluetooth.Device;
import cc.calliope.mini.core.state.ScanResults;
import cc.calliope.mini.popup.PopupAdapter;
import cc.calliope.mini.popup.PopupItem;
import cc.calliope.mini.R;
import cc.calliope.mini.dialog.pattern.PatternDialogFragment;
import cc.calliope.mini.core.state.Notification;
import cc.calliope.mini.core.state.Progress;
import cc.calliope.mini.core.state.State;
import cc.calliope.mini.core.state.ApplicationStateHandler;
import cc.calliope.mini.utils.Constants;
import cc.calliope.mini.utils.Permission;
import cc.calliope.mini.utils.Utils;
import cc.calliope.mini.views.FobParams;
import cc.calliope.mini.views.MovableFloatingActionButton;
import cc.calliope.mini.views.SnowfallView;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

public abstract class BaseActivity extends AppCompatActivity
        implements DialogInterface.OnDismissListener, SensorEventListener {

    private MovableFloatingActionButton patternFab;
    private ConstraintLayout rootView;
    private int screenWidth;
    private int screenHeight;
    private PopupWindow popupWindow;
    private int popupMenuWidth;
    private int popupMenuHeight;
    private ObjectAnimator rotationAnimator;

    private Handler handler;
    private Runnable runnable;

    private State currentState = new State(STATE_IDLE);

    // A flag to track whether the device check is running
    private boolean isDeviceCheckRunning = false;

    // Store a reference to our SnowfallView so we can access it in onShakeDetected()
    protected SnowfallView snowfallView;

    // ------------------------ SHAKE DETECTION FIELDS ------------------------
    private SensorManager sensorManager;
    private Sensor accelerometer;

    // Shake threshold (Earth gravity = 1g ~ 9.81 m/s^2)
    private static final float SHAKE_THRESHOLD_GRAVITY = 2.7f;
    // Minimum pause between shake events (to prevent spamming)
    private static final int SHAKE_SLOP_TIME_MS = 500;
    private long shakeTimestamp = 0;
    // ------------------------------------------------------------------------

    ActivityResultLauncher<Intent> bluetoothEnableResultLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                // Nothing special after returning from Bluetooth enable
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize the application state handler
        ApplicationStateHandler.updateState(State.STATE_IDLE);
        ApplicationStateHandler.getStateLiveData().observe(this, stateObserver);
        ApplicationStateHandler.getNotificationLiveData().observe(this, notificationObserver);
        ApplicationStateHandler.getProgressLiveData().observe(this, progressObserver);

        // Observe devices (ScanResults)
        ScanResults.getStateLiveData().observe(this, new Observer<List<Device>>() {
            @Override
            public void onChanged(List<Device> devices) {
                if (devices != null) {
                    for (Device device : devices) {
                        Log.v("SA", "Device: " + device);
                    }
                }
            }
        });

        // ------------- SENSOR INITIALIZATION (SHAKE DETECTION) -------------
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }
    }

    /**
     * Check if it's the holiday season (Dec 20 - Jan 10).
     */
    private boolean isHolidaySeason() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Calendar cal = Calendar.getInstance();
            int month = cal.get(Calendar.MONTH) + 1;
            int day = cal.get(Calendar.DAY_OF_MONTH);

            return ((month == 12 && day >= 20) || (month == 1 && day <= 10));
        }
        return false;
    }

    /**
     * Make the status bar transparent (optional).
     */
    private void makeStatusBarTransparent() {
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        );
    }

    // -------------------------------------------------
    // Button rotation animation (when STATE_BUSY)
    private void startRotationAnimation(final View view) {
        rotationAnimator = ObjectAnimator.ofFloat(view, "rotation", 0f, 360f);
        rotationAnimator.setDuration(2000);
        rotationAnimator.setInterpolator(new LinearInterpolator());
        rotationAnimator.setRepeatCount(ObjectAnimator.INFINITE);
        rotationAnimator.start();
    }

    private void stopRotationAnimation(final View view) {
        if (rotationAnimator != null) {
            rotationAnimator.cancel();
        }
        ObjectAnimator rotateToZero = ObjectAnimator.ofFloat(view, "rotation", view.getRotation(), 0f);
        rotateToZero.setDuration(300);
        rotateToZero.start();
    }
    // -------------------------------------------------

    // STATE OBSERVER
    private final Observer<State> stateObserver = new Observer<>() {
        @Override
        public void onChanged(State state) {
            if (state == null) {
                return;
            }

            if (currentState.getType() != state.getType()) {
                if (currentState.getType() == State.STATE_BUSY) {
                    // Leaving STATE_BUSY
                    stopRotationAnimation(patternFab);
                } else if (state.getType() == State.STATE_BUSY) {
                    // Entering STATE_BUSY
                    startRotationAnimation(patternFab);
                }
            }
            currentState = state;

            switch (state.getType()) {
                case State.STATE_BUSY -> {
                    patternFab.setColor(R.color.yellow_200);
                }
                case State.STATE_FLASHING -> {
                    patternFab.setColor(R.color.blue_light);
                }
                case State.STATE_ERROR -> {
                    patternFab.setColor(R.color.red);
                }
                case State.STATE_IDLE -> {
                    patternFab.setColor(R.color.aqua_200);
                    checkDeviceAvailability();
                }
            }
        }
    };

    // NOTIFICATION OBSERVER
    private final Observer<Notification> notificationObserver = new Observer<>() {
        @Override
        public void onChanged(Notification notification) {
            int type = notification.getType();
            String message = notification.getMessage();
            switch (type) {
                case Notification.INFO ->
                        SnackbarHelper.infoSnackbar(rootView, message).show();
                case Notification.WARNING ->
                        SnackbarHelper.warningSnackbar(rootView, message).show();
                case Notification.ERROR ->
                        SnackbarHelper.errorSnackbar(rootView, message).show();
            }
        }
    };

    // PROGRESS OBSERVER
    private final Observer<Progress> progressObserver = new Observer<>() {
        @Override
        public void onChanged(Progress progress) {
            patternFab.setProgress(progress.getValue());
        }
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ApplicationStateHandler.getNotificationLiveData().removeObserver(notificationObserver);
        ApplicationStateHandler.getStateLiveData().removeObserver(stateObserver);
        ApplicationStateHandler.getProgressLiveData().removeObserver(progressObserver);
    }

    // -------------------------------------------------
    // REGISTER/UNREGISTER SENSOR IN onResume/onPause
    @Override
    protected void onResume() {
        super.onResume();
        checkPermission();
        readDisplayMetrics();
        patternFab.setProgress(0);

        // Register the accelerometer listener (if available).
        // Optionally, check isHolidaySeason() here if you only want
        // shake detection during the holiday season.
        if (accelerometer != null) {
            sensorManager.registerListener(
                    this, accelerometer, SensorManager.SENSOR_DELAY_GAME
            );
        }

        // Schedule a task to check device availability every 10 seconds
        handler = new Handler();
        runnable = new Runnable() {
            @Override
            public void run() {
                if (!isDeviceCheckRunning) {
                    isDeviceCheckRunning = true;
                    checkDeviceAvailability();
                    isDeviceCheckRunning = false;
                }
                handler.postDelayed(this, 10000); // Schedule the next check
            }
        };
        // Start the initial check
        handler.post(runnable);
    }

    @Override
    protected void onPause() {
        super.onPause();

        // Unregister sensor
        sensorManager.unregisterListener(this);

        handler.removeCallbacks(runnable);
    }
    // -------------------------------------------------

    @Override
    public void onDismiss(final DialogInterface dialog) {
        // Fragment dialog was dismissed
        // ...
    }

    private void readDisplayMetrics() {
        WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics displayMetrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(displayMetrics);
        screenWidth = displayMetrics.widthPixels;
        screenHeight = displayMetrics.heightPixels;
    }

    /**
     * Overridden setContentView(ConstraintLayout) method
     * where we add our SnowfallView if it's holiday season.
     */
    public void setContentView(ConstraintLayout view) {
        super.setContentView(view);
        this.rootView = view;

        if (isHolidaySeason()) {
            // makeStatusBarTransparent(); // optional

            // Create and add the snow overlay
            SnowfallView snowfallViewLocal = new SnowfallView(this);
            this.snowfallView = snowfallViewLocal; // store reference

            ConstraintLayout.LayoutParams params = new ConstraintLayout.LayoutParams(
                    ConstraintLayout.LayoutParams.MATCH_PARENT,
                    ConstraintLayout.LayoutParams.MATCH_PARENT
            );
            rootView.addView(snowfallViewLocal, params);
        }
    }

    public void setPatternFab(MovableFloatingActionButton patternFab) {
        this.patternFab = patternFab;
        this.patternFab.setOnClickListener(this::onFabClick);
    }

    private void checkPermission() {
        boolean isBluetoothAccessGranted = Permission.isAccessGranted(this, Permission.BLUETOOTH_PERMISSIONS);
        boolean isLocationAccessGranted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                || Permission.isAccessGranted(this, Permission.LOCATION_PERMISSIONS);
        boolean isNotificationAccessGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || Permission.isAccessGranted(this, Permission.POST_NOTIFICATIONS);

        if (!isBluetoothAccessGranted || !isLocationAccessGranted || !isNotificationAccessGranted) {
            startNoPermissionActivity();
            return;
        }

        checkServiceStatus();
    }

    private void checkServiceStatus() {
        if (!Utils.isBluetoothEnabled()) {
            showBluetoothDisabledWarning();
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !Utils.isLocationEnabled(this)) {
            showLocationDisabledWarning();
        }
    }

    private void showPatternDialog(FobParams params) {
        if (Utils.isBluetoothEnabled()) {
            FragmentManager fragmentManager = getSupportFragmentManager();
            PatternDialogFragment dialogFragment = PatternDialogFragment.newInstance(params);
            dialogFragment.show(fragmentManager, "fragment_pattern");
        } else {
            showBluetoothDisabledWarning();
        }
    }

    private void showBluetoothDisabledWarning() {
        SnackbarHelper.errorSnackbar(rootView, getString(R.string.error_snackbar_bluetooth_disabled))
                .setDuration(BaseTransientBottomBar.LENGTH_INDEFINITE)
                .setAction(R.string.button_enable, this::startBluetoothEnableActivity)
                .show();
    }

    private void showLocationDisabledWarning() {
        SnackbarHelper.errorSnackbar(rootView, getString(R.string.error_snackbar_location_disable))
                .show();
    }

    private void startNoPermissionActivity() {
        Intent intent = new Intent(this, NoPermissionActivity.class);
        startActivity(intent);
    }

    public void startBluetoothEnableActivity(View view) {
        Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
        bluetoothEnableResultLauncher.launch(enableBtIntent);
    }

    public void onFabClick(View view) {
        State state = ApplicationStateHandler.getStateLiveData().getValue();
        boolean flashing = false;
        if (state != null) {
            flashing = state.getType() == State.STATE_FLASHING;
        }

        if (flashing) {
            startFlashingActivity();
        } else {
            createPopupMenu(view);
            showPopupMenu(view);
        }
    }

    private void createPopupMenu(View view) {
        List<PopupItem> popupItems = new ArrayList<>();
        addPopupMenuItems(popupItems);

        final ListView listView = new ListView(this);
        listView.setAdapter(new PopupAdapter(this,
                (Math.round(view.getX()) <= screenWidth / 2) ? PopupAdapter.TYPE_START : PopupAdapter.TYPE_END,
                popupItems)
        );
        listView.setDivider(null);
        listView.setOnItemClickListener(this::onPopupMenuItemClick);

        // get max item measured width
        popupMenuHeight = 0;
        for (int i = 0; i < listView.getAdapter().getCount(); i++) {
            View listItem = listView.getAdapter().getView(i, null, listView);
            listItem.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            int width = listItem.getMeasuredWidth();
            if (width > popupMenuWidth) {
                popupMenuWidth = width;
            }
            popupMenuHeight += listItem.getMeasuredHeight();
        }

        popupWindow = new PopupWindow(listView, popupMenuWidth,
                WindowManager.LayoutParams.WRAP_CONTENT, true);
        popupWindow.setTouchable(true);
        popupWindow.setFocusable(true);
        popupWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popupWindow.setOnDismissListener(() -> onDismissPopupMenu(view));
    }

    public void addPopupMenuItems(List<PopupItem> popupItems) {
        popupItems.add(new PopupItem(R.string.menu_fab_connect, R.drawable.ic_connect));
    }

    public void onPopupMenuItemClick(AdapterView<?> parent, View view, int position, long id) {
        Log.v("SA", "position: " + position);
        popupWindow.dismiss();
        if (position == 0) {
            showPatternDialog(new FobParams(
                    patternFab.getWidth(),
                    patternFab.getHeight(),
                    patternFab.getX(),
                    patternFab.getY()
            ));
        }
    }

    private void showPopupMenu(View view) {
        Point point = getOffset(view);
        popupWindow.showAsDropDown(view, point.x, point.y);
        dimBackground(0.5f);
        ViewCompat.animate(view)
                .rotation(45.0F)
                .withLayer().setDuration(300)
                .setInterpolator(new OvershootInterpolator(10.0F))
                .start();
    }

    private void onDismissPopupMenu(View view) {
        dimBackground(1.0f);
        ViewCompat.animate(view)
                .rotation(0.0F)
                .withLayer().setDuration(300)
                .setInterpolator(new OvershootInterpolator(10.0F))
                .start();
    }

    private void dimBackground(float dimAmount) {
        Window window = getWindow();
        WindowManager.LayoutParams layoutParams = window.getAttributes();
        layoutParams.alpha = dimAmount;
        window.setAttributes(layoutParams);
    }

    private Point getOffset(View view) {
        int x;
        int y;

        if (Math.round(view.getX()) <= screenWidth / 2) {
            x = Utils.convertDpToPixel(this, 8);
        } else {
            x = (Utils.convertDpToPixel(this, 8) - view.getWidth() + popupMenuWidth) * -1;
        }

        if (Math.round(view.getY()) <= screenHeight / 2) {
            y = Utils.convertDpToPixel(this, 4);
        } else {
            y = (Utils.convertDpToPixel(this, 4) + view.getHeight() + popupMenuHeight) * -1;
        }

        return new Point(x, y);
    }

    private void startFlashingActivity() {
        if (Utils.isBluetoothEnabled()) {
            final Intent intent = new Intent(this, FlashingActivity.class);
            startActivity(intent);
        } else {
            showBluetoothDisabledWarning();
        }
    }

    private boolean hasOpenedPatternDialog() {
        List<Fragment> fragments = getSupportFragmentManager().getFragments();
        for (Fragment fragment : fragments) {
            if (fragment instanceof PatternDialogFragment) {
                return true;
            }
        }
        return false;
    }

    private void checkDeviceAvailability() {
        if (currentState.getType() == State.STATE_BUSY
                || currentState.getType() == State.STATE_FLASHING
                || hasOpenedPatternDialog()) {
            return;
        }

        ResultReceiver resultReceiver = new ResultReceiver(new Handler()) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                super.onReceiveResult(resultCode, resultData);
                if (resultCode == CheckService.RESULT_OK) {
                    patternFab.setColor(R.color.green);
                    ApplicationStateHandler.updateDeviceAvailability(true);
                } else if (resultCode == CheckService.RESULT_CANCELED) {
                    patternFab.setColor(R.color.aqua_200);
                    ApplicationStateHandler.updateDeviceAvailability(false);
                }
            }
        };

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        String address = preferences.getString(Constants.CURRENT_DEVICE_ADDRESS, "");

        Intent intent = new Intent(this, CheckService.class);
        intent.putExtra("device_mac_address", address);
        intent.putExtra("result_receiver", resultReceiver);
        startService(intent);
    }

    // -------------------------------------------------
    // SensorEventListener - accelerometer handling
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float x = event.values[0];
            float y = event.values[1];
            float z = event.values[2];

            // Convert to g-forces
            float gX = x / SensorManager.GRAVITY_EARTH;
            float gY = y / SensorManager.GRAVITY_EARTH;
            float gZ = z / SensorManager.GRAVITY_EARTH;

            float gForce = (float) Math.sqrt(gX * gX + gY * gY + gZ * gZ);

            // If it exceeds the shake threshold
            if (gForce > SHAKE_THRESHOLD_GRAVITY) {
                final long now = System.currentTimeMillis();
                // Debounce check
                if (shakeTimestamp + SHAKE_SLOP_TIME_MS < now) {
                    shakeTimestamp = now;
                    onShakeDetected();
                }
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Usually not needed
    }

    /**
     * Called when a shake event is detected.
     * Here you can modify the snow or perform any other action.
     */
    private void onShakeDetected() {
        Log.d("BaseActivity", "Shake detected!");
        if (snowfallView != null) {
            snowfallView.shakeEffect();
        }
    }
}