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
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.Observer;
import androidx.preference.PreferenceManager;

import com.google.android.material.snackbar.BaseTransientBottomBar;

import cc.calliope.mini.popup.PopupAdapter;
import cc.calliope.mini.popup.PopupItem;
import cc.calliope.mini.R;
import cc.calliope.mini.dialog.pattern.PatternDialogFragment;
import cc.calliope.mini.core.state.Notification;
import cc.calliope.mini.core.state.Progress;
import cc.calliope.mini.core.state.State;
import cc.calliope.mini.core.state.ApplicationStateHandler;
import cc.calliope.mini.utils.Permission;
import cc.calliope.mini.utils.Utils;
import cc.calliope.mini.views.FobParams;
import cc.calliope.mini.views.MovableFloatingActionButton;

public abstract class BaseActivity extends AppCompatActivity implements DialogInterface.OnDismissListener{
    private static final int SNACKBAR_DURATION = 10000; // how long to display the snackbar message.
    private static boolean requestWasSent = false;
    private MovableFloatingActionButton patternFab;
    private ConstraintLayout rootView;
    private int screenWidth;
    private int screenHeight;
    private PopupWindow popupWindow;
    private int popupMenuWidth;
    private int popupMenuHeight;
    private ObjectAnimator rotationAnimator;
    private int previousState = STATE_IDLE;

    ActivityResultLauncher<Intent> bluetoothEnableResultLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ApplicationStateHandler.getNotificationLiveData().observe(this, notificationObserver);
        ApplicationStateHandler.getStateLiveData().observe(this, stateObserver);
        ApplicationStateHandler.getProgressLiveData().observe(this, progressObserver);

        ApplicationStateHandler.updateState(restoreState());
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


    private final Observer<State> stateObserver = new Observer<>() {
        @Override
        public void onChanged(State state) {
            if (state == null) {
                return;
            }

            int type = state.getType();

            if (type != previousState) {
                if (type == State.STATE_BUSY) {
                    startRotationAnimation(patternFab);
                } else if (previousState == State.STATE_BUSY) {
                    stopRotationAnimation(patternFab);
                }
                previousState = type;
            }

            switch (type) {
                case State.STATE_READY -> {
                    saveState(State.STATE_READY);
                    patternFab.setColor(R.color.green);
                }
                case State.STATE_BUSY -> {
                    patternFab.setColor(R.color.yellow_200);
                }
                case State.STATE_FLASHING -> {
                    patternFab.setColor(R.color.blue_light);
                }
                case State.STATE_ERROR -> {
                    saveState(STATE_IDLE);
                    patternFab.setColor(R.color.red);
                }
                case State.STATE_IDLE -> {
                    saveState(STATE_IDLE);
                    patternFab.setColor(R.color.aqua_200);
                }
            }
        }
    };

    private final Observer<Notification> notificationObserver = new Observer<>() {
        @Override
        public void onChanged(Notification notification) {
            int type = notification.getType();
            String message = notification.getMessage();
            switch (type) {
                case Notification.INFO ->
                        Utils.infoSnackbar(rootView, message).show();
                case Notification.WARNING ->
                        Utils.warningSnackbar(rootView, message).show();
                case Notification.ERROR ->
                        Utils.errorSnackbar(rootView, message).show();
            }
        }
    };

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

    @Override
    public void onResume() {
        super.onResume();
        requestWasSent = false;
        checkPermission();
        readDisplayMetrics();
        patternFab.setProgress(0);
    }

    @Override
    public void onPause() {
        super.onPause();
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
        Utils.errorSnackbar(rootView, getString(R.string.error_snackbar_bluetooth_disabled))
                .setDuration(BaseTransientBottomBar.LENGTH_INDEFINITE)
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
        State state = ApplicationStateHandler.getStateLiveData().getValue();
        boolean flashing = false;

        if (state != null) {
            flashing = state.getType() == State.STATE_FLASHING;
        }

        if(flashing){
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

    private void saveState(int state){
        SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(this).edit();
        editor.putInt("APP_STATE", state);
        editor.apply();
    }

    private int restoreState(){
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        return preferences.getInt("APP_STATE", STATE_IDLE);
    }
}