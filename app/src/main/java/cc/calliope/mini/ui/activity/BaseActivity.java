package cc.calliope.mini.ui.activity;

import android.content.DialogInterface;
import android.content.Intent;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;

import java.util.ArrayList;
import java.util.List;

import cc.calliope.mini.R;
import cc.calliope.mini.core.bluetooth.CheckService;
import cc.calliope.mini.core.state.AppMode;
import cc.calliope.mini.core.state.AppStateRepository;
import cc.calliope.mini.ui.components.DevicePopupMenu;
import cc.calliope.mini.ui.components.FabController;
import cc.calliope.mini.ui.components.PermissionGate;
import cc.calliope.mini.ui.components.SnackbarPresenter;
import cc.calliope.mini.ui.components.SnowfallDecorator;
import cc.calliope.mini.ui.dialog.pattern.PatternDialogFragment;
import cc.calliope.mini.ui.popup.PopupItem;
import cc.calliope.mini.ui.views.FobParams;
import cc.calliope.mini.ui.views.MovableFloatingActionButton;

/**
 * Base of the screens that show the movable device FAB. It only wires the
 * pieces together; each concern lives in its own component:
 * {@link FabController} (state → colour / spinner / progress),
 * {@link DevicePopupMenu} (the FAB menu), {@link SnackbarPresenter}
 * (notifications), {@link PermissionGate} (permissions, Bluetooth on) and
 * {@link SnowfallDecorator} (the holiday overlay).
 */
public abstract class BaseActivity extends AppCompatActivity
        implements DialogInterface.OnDismissListener {

    private MovableFloatingActionButton patternFab;
    private ConstraintLayout rootView;

    // Created with the activity: both register with its lifecycle, and the
    // gate's result launcher must exist before the activity starts.
    private final SnackbarPresenter snackbars = new SnackbarPresenter(this, () -> rootView);
    private final PermissionGate permissionGate = new PermissionGate(this, snackbars::host);
    private final DevicePopupMenu popupMenu = new DevicePopupMenu(this, item -> {
        onPopupItemClick(item);
        return kotlin.Unit.INSTANCE;
    });

    @Override
    protected void onResume() {
        super.onResume();
        // Ensure CheckService is running (may have been killed by system while in background)
        startService(new Intent(this, CheckService.class));
    }

    /** Called by the pattern dialog when it closes; subclasses may react. */
    @Override
    public void onDismiss(final DialogInterface dialog) {
    }

    /** Sets the content view and, in the holiday season, the snowfall overlay on top of it. */
    public void setContentView(ConstraintLayout view) {
        super.setContentView(view);
        this.rootView = view;
        SnowfallDecorator.install(this, view);
    }

    public void setPatternFab(MovableFloatingActionButton patternFab) {
        this.patternFab = patternFab;
        this.patternFab.setOnClickListener(this::onFabClick);
        new FabController(this, patternFab);
    }

    public void moveFabUp() {
        patternFab.moveUp();
    }

    public void moveFabDown() {
        patternFab.moveDown();
    }

    public void onFabClick(View view) {
        AppMode mode = AppStateRepository.getMode().getValue();
        if (mode instanceof AppMode.Flashing || mode instanceof AppMode.Busy) {
            if (permissionGate.requireBluetoothEnabled()) {
                startActivity(new Intent(this, FlashingActivity.class));
            }
        } else {
            List<PopupItem> items = new ArrayList<>();
            addPopupMenuItems(items);
            popupMenu.show(view, items);
        }
    }

    /** Subclasses call super and append their own items. */
    public void addPopupMenuItems(List<PopupItem> popupItems) {
        // While controlling the mini (a live editor session) we're already
        // linked to it, so pairing to another device makes no sense — offer
        // ending the session instead of the connect item.
        if (AppStateRepository.getControl().getValue()) {
            popupItems.add(new PopupItem(R.string.menu_fab_disconnect, R.drawable.ic_disconnect));
        } else if (AppStateRepository.getReconnect().getValue() != null) {
            // An editor that connects on its own is showing and the user ended
            // its session: connect the chosen board again, or pick another one.
            popupItems.add(new PopupItem(R.string.menu_fab_change_device, R.drawable.ic_connect));
            popupItems.add(new PopupItem(R.string.menu_fab_reconnect, R.drawable.ic_bluetooth));
        } else {
            popupItems.add(new PopupItem(R.string.menu_fab_connect, R.drawable.ic_connect));
        }
    }

    /** Subclasses call super and handle their own items. */
    protected void onPopupItemClick(PopupItem item) {
        if (item.titleId() == R.string.menu_fab_disconnect) {
            // The session owner registered how to end it (see setControl).
            AppStateRepository.disconnectControl();
        } else if (item.titleId() == R.string.menu_fab_reconnect) {
            kotlin.jvm.functions.Function0<kotlin.Unit> reconnect = AppStateRepository.getReconnect().getValue();
            if (reconnect != null && permissionGate.requireBluetoothEnabled()) {
                reconnect.invoke();
            }
        } else if (item.titleId() == R.string.menu_fab_connect || item.titleId() == R.string.menu_fab_change_device) {
            if (permissionGate.requireBluetoothEnabled()) {
                PatternDialogFragment.newInstance(new FobParams(
                        patternFab.getWidth(),
                        patternFab.getHeight(),
                        patternFab.getX(),
                        patternFab.getY()
                )).show(getSupportFragmentManager(), "fragment_pattern");
            }
        }
    }
}
