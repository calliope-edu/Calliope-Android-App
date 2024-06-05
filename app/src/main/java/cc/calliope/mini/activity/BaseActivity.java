package cc.calliope.mini.activity;

import android.animation.ObjectAnimator;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.drawable.ColorDrawable;
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

import java.util.ArrayList;
import java.util.List;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.Observer;

import cc.calliope.mini.ProgressCollector;
import cc.calliope.mini.ProgressListener;
import cc.calliope.mini.notification.Notification;
import cc.calliope.mini.notification.NotificationManager;
import cc.calliope.mini.popup.PopupAdapter;
import cc.calliope.mini.popup.PopupItem;
import cc.calliope.mini.R;
import cc.calliope.mini.dialog.pattern.PatternDialogFragment;
import cc.calliope.mini.state.State;
import cc.calliope.mini.state.StateManager;
import cc.calliope.mini.utils.Permission;
import cc.calliope.mini.utils.Utils;
import cc.calliope.mini.views.FobParams;
import cc.calliope.mini.views.MovableFloatingActionButton;
import no.nordicsemi.android.dfu.DfuProgressListener;
import no.nordicsemi.android.dfu.DfuProgressListenerAdapter;
import no.nordicsemi.android.dfu.DfuServiceListenerHelper;

public abstract class BaseActivity extends AppCompatActivity implements DialogInterface.OnDismissListener, ProgressListener {
    private static final int SNACKBAR_DURATION = 10000; // how long to display the snackbar message.
    private static boolean requestWasSent = false;
    private MovableFloatingActionButton patternFab;
    private ConstraintLayout rootView;
    private int screenWidth;
    private int screenHeight;
    private PopupWindow popupWindow;
    private int popupMenuWidth;
    private int popupMenuHeight;
    private boolean isFlashing;
    private ObjectAnimator rotationAnimator;

    ActivityResultLauncher<Intent> bluetoothEnableResultLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
            }
    );

    private final DfuProgressListener dfuProgressListener = new DfuProgressListenerAdapter() {
        @Override
        public void onDeviceConnecting(@NonNull String deviceAddress) {
            super.onDeviceConnecting(deviceAddress);
            //patternFab.setColor(R.color.blue_light);
            isFlashing = true;
        }

        @Override
        public void onDeviceConnected(@NonNull String deviceAddress) {
            super.onDeviceConnected(deviceAddress);
            //patternFab.setColor(R.color.blue_light);
            isFlashing = true;
        }

        @Override
        public void onProgressChanged(@NonNull String deviceAddress, int percent, float speed, float avgSpeed, int currentPart, int partsTotal) {
            patternFab.setProgress(percent);
            patternFab.setColor(R.color.blue_light);
            stopRotationAnimation(patternFab);
            isFlashing = true;
        }

        @Override
        public void onDeviceDisconnecting(String deviceAddress) {
            patternFab.setProgress(0);
            //patternFab.setColor(R.color.aqua_200);
            isFlashing = false;
        }

        @Override
        public void onDeviceDisconnected(@NonNull String deviceAddress) {
            patternFab.setProgress(0);
            //patternFab.setColor(R.color.aqua_200);
            isFlashing = false;
        }

        @Override
        public void onDfuCompleted(@NonNull String deviceAddress) {
            patternFab.setProgress(0);
            //patternFab.setColor(R.color.aqua_200);
            Utils.infoSnackbar(rootView, getString(R.string.flashing_completed)).show();
            isFlashing = false;
        }

        @Override
        public void onDfuAborted(@NonNull String deviceAddress) {
            patternFab.setProgress(0);
            //patternFab.setColor(R.color.aqua_200);
            Utils.warningSnackbar(rootView, getString(R.string.flashing_aborted)).show();
            isFlashing = false;
        }

        @Override
        public void onError(@NonNull String deviceAddress, int error, int errorType, String message) {
            if (error == 4110) {
                return;
            }
            patternFab.setProgress(0);
            //patternFab.setColor(R.color.aqua_200);
            Utils.errorSnackbar(rootView, String.format(getString(R.string.flashing_error), error, message)).show();
            isFlashing = false;
        }
    };


    @Override
    public void onDfuAttempt() {

    }

    @Override
    public void onDfuControlComplete() {

    }

    @Override
    public void onProgressUpdate(int progress) {
        if(progress > 0){
            patternFab.setProgress(progress);
            //patternFab.setColor(R.color.blue_light);
            isFlashing = true;
        }else {
            patternFab.setProgress(0);
            //patternFab.setColor(R.color.aqua_200);
            isFlashing = false;
        }

    }

    @Override
    public void onBluetoothBondingStateChanged(@NonNull BluetoothDevice device, int bondState, int previousBondState) {

    }

    @Override
    public void onConnectionFailed() {
        patternFab.setProgress(0);
        //patternFab.setColor(R.color.aqua_200);
        Utils.warningSnackbar(rootView, getString(R.string.flashing_aborted)).show();
        isFlashing = false;
    }

    @Override
    public void onError(int code, String message) {
        patternFab.setProgress(0);
        //patternFab.setColor(R.color.aqua_200);
        Utils.errorSnackbar(rootView, String.format(getString(R.string.flashing_error), code, message)).show();
        isFlashing = false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        NotificationManager.getNotificationLiveData().observe(this, notificationObserver);
        StateManager.getStateLiveData().observe(this, state -> {
            int type = state.getType();
            String message = state.getMessage();
            switch (type) {
                case State.STATE_READY -> {
                    patternFab.setColor(R.color.green);
                    stopRotationAnimation(patternFab);
                    if (message != null && !message.isEmpty()) {
                        Utils.infoSnackbar(rootView, message).show();
                    }
                }
                case State.STATE_INITIALIZATION -> {
                    patternFab.setColor(R.color.yellow_200);
                    startRotationAnimation(patternFab);
                    if (message != null && !message.isEmpty()) {
                        Utils.warningSnackbar(rootView, message).show();
                    }
                }
                case State.STATE_FLASHING ->
                    patternFab.setColor(R.color.blue_light);
                case State.STATE_COMPLETED ->
                    patternFab.setColor(R.color.green);
                case State.STATE_ERROR -> {
                    patternFab.setColor(R.color.red);
                    stopRotationAnimation(patternFab);
                    if (message != null && !message.isEmpty()) {
                        Utils.errorSnackbar(rootView, message).show();
                    }
                }
                default ->
                    patternFab.setColor(R.color.aqua_200);
            }
        });

        ProgressCollector progressCollector = new ProgressCollector(this);
        getLifecycle().addObserver(progressCollector);
    }

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

    private final Observer<Notification> notificationObserver = new Observer<>() {
        @Override
        public void onChanged(Notification notification) {
            int type = notification.getType();
            String message = notification.getMessage();
            switch (type) {
                case Notification.TYPE_INFO ->
                        Utils.infoSnackbar(rootView, message).show();
                case Notification.TYPE_WARNING ->
                        Utils.warningSnackbar(rootView, message).show();
                case Notification.TYPE_ERROR ->
                        Utils.errorSnackbar(rootView, message).show();
            }
        }
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        //
        NotificationManager.getNotificationLiveData().removeObserver(notificationObserver);
    }

    @Override
    public void onResume() {
        super.onResume();
        requestWasSent = false;
        isFlashing = false;
        checkPermission();
        readDisplayMetrics();
        patternFab.setProgress(0);
//        patternFab.setColor(R.color.aqua_200);
        DfuServiceListenerHelper.registerProgressListener(this, dfuProgressListener);
    }

    @Override
    public void onPause() {
        super.onPause();
        DfuServiceListenerHelper.unregisterProgressListener(this, dfuProgressListener);
    }

    @Override
    public void onDismiss(final DialogInterface dialog) {
        //Fragment dialog had been dismissed
//        fab.setVisibility(View.VISIBLE);
    }

    private void readDisplayMetrics() {
        WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics displayMetrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(displayMetrics);
        screenWidth = displayMetrics.widthPixels;
        screenHeight = displayMetrics.heightPixels;
    }

    public void setContentView(ConstraintLayout view) {
        super.setContentView(view);
        this.rootView = view;
    }

    public void setPatternFab(MovableFloatingActionButton patternFab) {
        this.patternFab = patternFab;
        this.patternFab.setOnClickListener(this::onFabClick);
    }

    private void checkPermission() {
        boolean isBluetoothAccessGranted = Permission.isAccessGranted(this, Permission.BLUETOOTH_PERMISSIONS);
        boolean isLocationAccessGranted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || Permission.isAccessGranted(this, Permission.LOCATION_PERMISSIONS);
        boolean isNotificationAccessGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || Permission.isAccessGranted(this, Permission.POST_NOTIFICATIONS);

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
        if(Utils.isBluetoothEnabled()){
            FragmentManager fragmentManager = getSupportFragmentManager();
            PatternDialogFragment dialogFragment = PatternDialogFragment.newInstance(params);
            dialogFragment.show(fragmentManager, "fragment_pattern");
        } else {
            showBluetoothDisabledWarning();
        }
    }

    private void showBluetoothDisabledWarning() {
        Utils.errorSnackbar(rootView, getString(R.string.error_snackbar_bluetooth_disable))
                .setDuration(SNACKBAR_DURATION)
                .setAction(R.string.button_enable, this::startBluetoothEnableActivity)
                .show();
    }

    private void showLocationDisabledWarning() {
        Utils.errorSnackbar(rootView, getString(R.string.error_snackbar_location_disable))
                .show();
    }

    private void startNoPermissionActivity() {
        Intent intent = new Intent(this, NoPermissionActivity.class);
        startActivity(intent);
    }

    public void startBluetoothEnableActivity(View view) {
        requestWasSent = true;
        Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
        bluetoothEnableResultLauncher.launch(enableBtIntent);
    }

    public void onFabClick(View view) {
        if(isFlashing){
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

        //get max item measured width
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

        popupWindow = new PopupWindow(listView, popupMenuWidth, WindowManager.LayoutParams.WRAP_CONTENT, true);
        popupWindow.setTouchable(true);
        popupWindow.setFocusable(true);
        popupWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popupWindow.setOnDismissListener(() -> onDismissPopupMenu(view));
    }

    public void addPopupMenuItems(List<PopupItem> popupItems) {
        popupItems.add(new PopupItem(R.string.menu_fab_connect, R.drawable.ic_connect));
    }

    public void onPopupMenuItemClick(AdapterView<?> parent, View view, int position, long id) {
        Utils.log(Log.ASSERT, "SA", "position: " + position);
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
        if(Utils.isBluetoothEnabled()){
            final Intent intent = new Intent(this, FlashingActivity.class);
            startActivity(intent);
        }else {
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
}