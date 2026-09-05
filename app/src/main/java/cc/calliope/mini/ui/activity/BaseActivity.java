package cc.calliope.mini.ui.activity;


import android.animation.ObjectAnimator;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.drawable.ColorDrawable;
import android.icu.util.Calendar;
import android.os.Build;
import android.os.Bundle;
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
import androidx.fragment.app.FragmentManager;
import androidx.core.util.Consumer;

import com.google.android.material.snackbar.BaseTransientBottomBar;

import java.util.ArrayList;
import java.util.List;

import cc.calliope.mini.core.state.AppStateRepository;
import cc.calliope.mini.core.state.RepoObserve;
import cc.calliope.mini.ui.SnackbarHelper;
import cc.calliope.mini.ui.popup.PopupAdapter;
import cc.calliope.mini.ui.popup.PopupItem;
import cc.calliope.mini.R;
import cc.calliope.mini.ui.dialog.pattern.PatternDialogFragment;
import cc.calliope.mini.core.state.AppMode;
import cc.calliope.mini.core.state.FlashEvent;
import cc.calliope.mini.core.state.FlashPhase;
import cc.calliope.mini.core.state.Notification;
import cc.calliope.mini.utils.Permission;
import cc.calliope.mini.utils.Utils;
import cc.calliope.mini.utils.WindowUtils;
import cc.calliope.mini.ui.views.FobParams;
import cc.calliope.mini.ui.views.MovableFloatingActionButton;
import cc.calliope.mini.ui.views.SnowfallView;

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

    private AppMode currentMode = AppMode.Idle.INSTANCE;
    private boolean controlActive = false;
    private boolean deviceAvailable = false;

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

        // The repository owns state transitions; an activity only renders
        // them (the FAB colour, spinner, progress ring and snackbars).
        RepoObserve.mode(this, modeObserver);
        RepoObserve.control(this, controlObserver);
        RepoObserve.notifications(this, notificationObserver);
        RepoObserve.flashEvents(this, flashEventObserver);
        RepoObserve.deviceAvailable(this, deviceAvailabilityObserver);

        // ------------- SENSOR INITIALIZATION (SHAKE DETECTION) -------------
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }
    }

    /**
     * Check if it's the holiday season:
     * - Dec 6 (St. Nicholas Day)
     * - Dec 20 - Jan 10 (Christmas/New Year)
     */
    private boolean isHolidaySeason() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Calendar cal = Calendar.getInstance();
            int month = cal.get(Calendar.MONTH) + 1;
            int day = cal.get(Calendar.DAY_OF_MONTH);

            return (month == 12 && day >= 6) || (month == 1 && day <= 10);
        }
        return false;
    }

    // -------------------------------------------------
    // Button rotation animation (bonding, pre-upload flash phases)
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

    // MODE OBSERVER
    private final Consumer<AppMode> modeObserver = mode -> {
        boolean wasSpinning = AppMode.isSpinning(currentMode);
        boolean spinning = AppMode.isSpinning(mode);
        if (wasSpinning && !spinning) {
            stopRotationAnimation(patternFab);
        } else if (!wasSpinning && spinning) {
            startRotationAnimation(patternFab);
        }
        currentMode = mode;
        renderFab();
    };

    // CONTROL OBSERVER (live editor BLE session)
    private final Consumer<Boolean> controlObserver = active -> {
        controlActive = active;
        renderFab();
    };

    /**
     * FAB colour from (mode, control, device availability). A running flash
     * or bonding wins over a control session; the rest is idle/error tinted
     * by whether the board is in range.
     */
    private void renderFab() {
        if (currentMode instanceof AppMode.Flashing flashing
                && flashing.getPhase().compareTo(FlashPhase.UPLOADING) >= 0) {
            patternFab.setColor(R.color.state_control);
        } else if (AppMode.isSpinning(currentMode)) {
            patternFab.setColor(R.color.state_busy);
        } else if (controlActive) {
            patternFab.setColor(R.color.state_script);
        } else if (currentMode instanceof AppMode.Error) {
            patternFab.setColor(deviceAvailable ? R.color.state_connected : R.color.status_error);
        } else {
            patternFab.setColor(deviceAvailable ? R.color.state_connected : R.color.brand_accent);
        }
    }

    // NOTIFICATION OBSERVER
    private final Consumer<Notification> notificationObserver = notification -> {
        int type = notification.getType();
        String message = notification.getMessage();

        switch (type) {
            case Notification.INFO -> SnackbarHelper.infoSnackbar(rootView, message).show();
            case Notification.WARNING -> SnackbarHelper.warningSnackbar(rootView, message).show();
            case Notification.ERROR -> SnackbarHelper.errorSnackbar(rootView, message).show();
        }
    };

    // FLASH EVENT OBSERVER — the progress ring around the FAB
    private final Consumer<FlashEvent> flashEventObserver = event -> {
        if (event instanceof FlashEvent.Progress progress) {
            patternFab.setProgress(progress.getPercent());
        } else if (event instanceof FlashEvent.Done) {
            patternFab.setProgress(0);
        }
    };

    // DEVICE AVAILABILITY OBSERVER
    private final Consumer<Boolean> deviceAvailabilityObserver = isAvailable -> {
        deviceAvailable = isAvailable;
        renderFab();
    };


    // -------------------------------------------------
    // REGISTER/UNREGISTER SENSOR IN onResume/onPause
    @Override
    protected void onResume() {
        super.onResume();
        checkPermission();
        readDisplayMetrics();
        patternFab.setProgress(0);

        // Ensure CheckService is running (may have been killed by system while in background)
        startService(new Intent(this, cc.calliope.mini.core.bluetooth.CheckService.class));

        // Register the accelerometer listener (if available).
        if (accelerometer != null) {
            sensorManager.registerListener(
                    this, accelerometer, SensorManager.SENSOR_DELAY_GAME
            );
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
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

    public void moveFabUp() {
        patternFab.moveUp();
    }

    public void moveFabDown() {
        patternFab.moveDown();
    }

    private void checkPermission() {
        boolean isBluetoothAccessGranted = Permission.isAccessGranted(this, Permission.BLUETOOTH_PERMISSIONS);
        boolean isLocationAccessGranted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                || Permission.isAccessGranted(this, Permission.LOCATION_PERMISSIONS);
        // Camera permission is now requested only when needed (e.g., in CARDBOARD_FACE editor)
        boolean isNotificationAccessGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || Permission.isAccessGranted(this, Permission.POST_NOTIFICATIONS);

        if (!isBluetoothAccessGranted || !isLocationAccessGranted || !isNotificationAccessGranted) {
            startNoPermissionActivity();
            return;
        }

        checkServiceStatus();
    }

    private void checkServiceStatus() {
        if (!Utils.isBluetoothEnabled(this)) {
            showBluetoothDisabledWarning();
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !Utils.isLocationEnabled(this)) {
            showLocationDisabledWarning();
        }
    }

    private void showPatternDialog(FobParams params) {
        if (Utils.isBluetoothEnabled(this)) {
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
        AppMode mode = AppStateRepository.getMode().getValue();
        if (mode instanceof AppMode.Flashing || mode instanceof AppMode.Busy) {
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

        // get max item measured width; reset both so a menu with fewer items
        // (e.g. only full-screen while controlling) isn't sized for a past,
        // longer menu.
        popupMenuWidth = 0;
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
        // While controlling the mini (a live editor session) we're already
        // linked to it, so pairing to another device makes no sense — hide
        // the connect item.
        if (!AppStateRepository.getControl().getValue()) {
            popupItems.add(new PopupItem(R.string.menu_fab_connect, R.drawable.ic_connect));
        }
    }

    public void onPopupMenuItemClick(AdapterView<?> parent, View view, int position, long id) {
        // Dispatch by item identity, not position: the connect item is hidden
        // during a control session, which shifts the remaining items' positions.
        popupWindow.dismiss();
        if (!(parent.getItemAtPosition(position) instanceof PopupItem item)) {
            return;
        }
        if (item.titleId() == R.string.menu_fab_connect) {
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
        WindowUtils.blurBehindDialog(this, true);
        ViewCompat.animate(view)
                .rotation(45.0F)
                .withLayer().setDuration(300)
                .setInterpolator(new OvershootInterpolator(10.0F))
                .start();
    }

    private void onDismissPopupMenu(View view) {
        dimBackground(1.0f);
        WindowUtils.blurBehindDialog(this, false);
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
            x = Utils.convertDpToPixel(this, 4);
        } else {
            x = (Utils.convertDpToPixel(this, 4) - view.getWidth() + popupMenuWidth) * -1;
        }

        if (Math.round(view.getY()) <= screenHeight / 2) {
            y = Utils.convertDpToPixel(this, 4);
        } else {
            y = (Utils.convertDpToPixel(this, 4) + view.getHeight() + popupMenuHeight) * -1;
        }

        return new Point(x, y);
    }

    private void startFlashingActivity() {
        if (Utils.isBluetoothEnabled(this)) {
            final Intent intent = new Intent(this, FlashingActivity.class);
            startActivity(intent);
        } else {
            showBluetoothDisabledWarning();
        }
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