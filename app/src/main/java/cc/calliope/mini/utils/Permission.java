package cc.calliope.mini.utils;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;


public class Permission {
    public static final String[] BLUETOOTH_PERMISSIONS;
    static {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            BLUETOOTH_PERMISSIONS = new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT};
        } else {
            BLUETOOTH_PERMISSIONS = new String[]{Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN};
        }
    }

    public static final String[] LOCATION_PERMISSIONS = {Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION};
    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    public static final String[] POST_NOTIFICATIONS = {Manifest.permission.POST_NOTIFICATIONS};

    public static boolean isAccessGranted(Activity activity, String... permissions) {
        for (String permission : permissions) {
            boolean granted = ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED;
            Log.d("PERMISSION", permission + (granted ? " granted" : " denied"));
            if (!granted) {
                return false;
            }
        }
        return true;
    }

    public static boolean isAccessDeniedForever(Activity activity, String... permissions) {
        return !isAccessGranted(activity, permissions) // Location permission must be denied
                && Preference.getBoolean(activity, permissions[0], false)// Permission must have been requested before
                && !ActivityCompat.shouldShowRequestPermissionRationale(activity, permissions[0]); // This method should return false
    }

    public static void markPermissionRequested(Activity activity, String... permissions) {
        Preference.putBoolean(activity, permissions[0], true);
    }
}
