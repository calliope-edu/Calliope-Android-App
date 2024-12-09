package cc.calliope.mini.utils;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import androidx.core.content.ContextCompat;

import com.google.android.material.snackbar.Snackbar;

import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Date;

import cc.calliope.mini.R;

public class Utils {
    private static final String TAG = "UTILS";

    /**
     * Checks whether device is connected to network
     *
     * @param context the context
     * @return true if connected, false otherwise.
     */
    public static boolean isNetworkConnected(Context context) {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        return manager.getActiveNetworkInfo() != null && manager.getActiveNetworkInfo().isConnected();
    }

    /**
     * Actually checks if device is connected to internet
     * (There is a possibility it's connected to a network but not to internet)
     *
     * @return true if connected, false otherwise.
     */
    public static boolean isInternetAvailable() {
        String command = "ping -c 1 google.com";
        try {
            return Runtime.getRuntime().exec(command).waitFor() == 0;
        } catch (InterruptedException | IOException e) {
            e.printStackTrace();
        }
        return false;
    }

    /**
     * Checks whether Bluetooth is enabled.
     *
     * @return true if Bluetooth is enabled, false otherwise.
     */
    public static boolean isBluetoothEnabled() {
        final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        return adapter != null && adapter.isEnabled();
    }

    /**
     * On some devices running Android Marshmallow or newer location services must be enabled in order to scan for Bluetooth LE devices.
     * This method returns whether the Location has been enabled or not.
     *
     * @return true on Android 6.0+ if location mode is different than LOCATION_MODE_OFF.
     */
    public static boolean isLocationEnabled(final Context context) {
        LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        boolean gpsEnabled = false;
        boolean networkEnabled = false;

        try {
            gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        } catch (Exception ex) {
            Log.e(TAG, "isLocationEnabled: " + ex);
        }

        try {
            networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception ex) {
            Log.e(TAG, "isLocationEnabled: " + ex);
        }

        return gpsEnabled || networkEnabled;
    }

    /**
     * This method converts dp unit to equivalent pixels, depending on device density.
     *
     * @param dp      A value in dp (density independent pixels) unit. Which we need to convert into pixels
     * @param context Context to get resources and device specific display metrics
     * @return A int value to represent px equivalent to dp depending on device density
     */
    public static int convertDpToPixel(Context context, int dp) {
        return dp * (context.getResources().getDisplayMetrics().densityDpi / DisplayMetrics.DENSITY_DEFAULT);
    }

    public static String dateFormat(long lastModified) {
        final String OUTPUT_DATE_FORMAT = "EEEE dd.MM.yyyy HH:mm";
        Date date = new Date(lastModified);

        return DateFormat.format(OUTPUT_DATE_FORMAT, date.getTime()).toString();
    }
}
