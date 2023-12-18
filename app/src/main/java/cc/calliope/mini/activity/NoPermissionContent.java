package cc.calliope.mini.activity;

import android.os.Build;

import androidx.annotation.RequiresApi;
import cc.calliope.mini.R;
import cc.calliope.mini.utils.Permission;

public enum NoPermissionContent {
    BLUETOOTH(R.drawable.ic_bluetooth_disabled, R.string.title_bluetooth_permission, R.string.info_bluetooth_permission, Permission.BLUETOOTH_PERMISSIONS),

    LOCATION(R.drawable.ic_location_disabled, R.string.title_location_permission, R.string.info_location_permission, Permission.LOCATION_PERMISSIONS),

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    NOTIFICATIONS(R.drawable.ic_notifications, R.string.title_notification_permission, R.string.info_notification_permission, Permission.POST_NOTIFICATIONS);

    private final int icResId;
    private final int titleResId;
    private final int messageResId;
    private final String[] permissionsArray;

    NoPermissionContent(int icResId, int titleResId, int messageResId, String[] permissionsArray) {
        this.icResId = icResId;
        this.titleResId = titleResId;
        this.messageResId = messageResId;
        this.permissionsArray = permissionsArray;
    }

    public int getIcResId() {
        return icResId;
    }

    public int getTitleResId() {
        return titleResId;
    }

    public int getMessageResId() {
        return messageResId;
    }

    public String[] getPermissionsArray() {
        return permissionsArray;
    }
}
