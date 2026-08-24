package cc.calliope.mini.ui.activity;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ComponentCallbacks2;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.ui.NavigationUI;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import cc.calliope.mini.bridge.CampusUrls;
import cc.calliope.mini.core.state.AppStateRepository;
import cc.calliope.mini.core.state.ApplicationStateHandler;
import cc.calliope.mini.core.state.Notification;
import cc.calliope.mini.core.state.State;
import cc.calliope.mini.ui.popup.PopupItem;
import cc.calliope.mini.R;
import cc.calliope.mini.databinding.ActivityMainBinding;
import cc.calliope.mini.ui.dialog.scripts.ScriptsFragment;
import cc.calliope.mini.ui.fragment.web.RetainedWebEditor;
import cc.calliope.mini.ui.model.EditorType;

public class MainActivity extends BaseActivity {
    private static final String TAG = "MainActivity";
    private static final String MAKECODE_HOST = "makecode.calliope.cc";
    private ActivityMainBinding binding;
    private NavController navController;
    private boolean fullScreen = false;
    /** Consumes Back to leave full-screen; enabled only while full-screen. */
    private OnBackPressedCallback fullScreenBackCallback;
    private final ActivityResultLauncher<String> pushNotificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    Log.i(TAG, "NotificationPermission is Granted");
                } else {
                    Log.w(TAG, "NotificationPermission NOT Granted");
                }
            });

    // ZXing's capture screen requests the CAMERA permission itself when it
    // opens, so the prompt appears only when the user actually scans.
    private final ActivityResultLauncher<ScanOptions> qrScanLauncher =
            registerForActivityResult(new ScanContract(), result -> {
                // result.getContents() == null means the user cancelled.
                if (result.getContents() != null) {
                    handleScannedContent(result.getContents());
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setPatternFab(binding.patternFab);

        fullScreenBackCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                disableFullScreenMode();
            }
        };
        getOnBackPressedDispatcher().addCallback(this, fullScreenBackCallback);

        BottomNavigationView bottomNavigationView = findViewById(R.id.bottom_navigation);

        navController = Navigation.findNavController(this, R.id.navigation_host_fragment);
        NavigationUI.setupWithNavController(bottomNavigationView, navController);

        Map<Integer, Integer> navMapping = new HashMap<>();
        navMapping.put(R.id.navigation_info, R.id.navigation_home);
        navMapping.put(R.id.navigation_web, R.id.navigation_editors);
        navMapping.put(R.id.navigation_web_ble, R.id.navigation_editors);
        navMapping.put(R.id.navigation_web_proxy, R.id.navigation_editors);
        navMapping.put(R.id.navigation_help, R.id.navigation_settings);
        navMapping.put(R.id.navigation_editor_settings, R.id.navigation_settings);

        navController.addOnDestinationChangedListener((controller, destination, arguments) -> {
            int destId = destination.getId();
            if (destId == R.id.navigation_web || destId == R.id.navigation_web_ble
                    || destId == R.id.navigation_web_proxy) {
               moveFabDown();
            } else {
               moveFabUp();
            }
            Integer mapping = navMapping.get(destId);
            int menuId = (mapping != null) ? mapping : destId;
            MenuItem menuItem = binding.bottomNavigation.getMenu().findItem(menuId);
            if (menuItem != null) {
                menuItem.setChecked(true);
            }
        });

        NavigationUI.setupWithNavController(binding.bottomNavigation, navController);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPushNotificationPermission();
        }

        externalStorageVolumes();

        // Cold start from a makecode.calliope.cc App Link. On config-change
        // recreation savedInstanceState is non-null, so we don't re-navigate.
        if (savedInstanceState == null) {
            handleMakeCodeLink(getIntent());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // App already running (singleTop) — a new link arrives here.
        setIntent(intent);
        handleMakeCodeLink(intent);
    }

    /**
     * If {@code intent} is a VIEW on a makecode.calliope.cc URL, open it in the
     * MakeCode web editor (same destination the editors list uses), loading the
     * exact incoming URL so shared projects open as-is.
     */
    private void handleMakeCodeLink(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) {
            return;
        }
        Uri data = intent.getData();
        if (data == null || !isMakeCodeUrl(data)) {
            return;
        }
        navigateToMakeCode(data.toString());
    }

    /** Opens {@code url} in the MakeCode web editor (callers verify the host). */
    private void navigateToMakeCode(String url) {
        navigateToEditor(R.id.navigation_web, url, EditorType.MAKECODE.getDirectoryName());
    }

    /** Opens {@code url} in the Campus web editor (native-proxy bridge). */
    private void navigateToCampus(String url) {
        navigateToEditor(R.id.navigation_web_proxy, url, EditorType.CAMPUS.getDirectoryName());
    }

    private void navigateToEditor(int destinationId, String url, String editorName) {
        if (navController == null) {
            return;
        }
        Bundle args = new Bundle();
        args.putString("editorUrl", url);
        args.putString("editorName", editorName);
        navController.navigate(destinationId, args);
    }

    /** True for makecode.calliope.cc and its subdomains (e.g. a /beta link). */
    private static boolean isMakeCodeUrl(Uri uri) {
        String host = uri.getHost();
        if (host == null) {
            return false;
        }
        host = host.toLowerCase(Locale.ROOT);
        return host.equals(MAKECODE_HOST) || host.endsWith("." + MAKECODE_HOST);
    }

    /** Opens the in-app QR scanner (permission is requested by the scanner). */
    private void startQrScan() {
        ScanOptions options = new ScanOptions();
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
        options.setPrompt(getString(R.string.qr_scan_prompt));
        options.setBeepEnabled(false);
        options.setOrientationLocked(false);
        options.setCaptureActivity(QrCaptureActivity.class);
        qrScanLauncher.launch(options);
    }

    /**
     * Routes a scanned link to the matching in-app editor: Campus links open in
     * the native-proxy editor, MakeCode links (incl. /beta) in the MakeCode
     * editor. Anything else shows an error so a foreign QR doesn't silently do
     * nothing.
     */
    private void handleScannedContent(String contents) {
        if (CampusUrls.INSTANCE.isCampusUrl(contents)) {
            navigateToCampus(contents);
        } else if (isMakeCodeUrl(Uri.parse(contents))) {
            navigateToMakeCode(contents);
        } else {
            ApplicationStateHandler.updateNotification(Notification.ERROR, R.string.error_qr_unsupported);
        }
    }

    private void externalStorageVolumes() {
        File[] externalStorageVolumes = ContextCompat.getExternalFilesDirs(getApplicationContext(), null);
        for (File externalDir : externalStorageVolumes) {
            Log.v(TAG, "Found dir at : " + externalDir);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        disableFullScreenMode();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        binding = null;
        // A configuration change recreates this activity and re-attaches the
        // retained editor to it, so only a genuine finish frees the page.
        if (isFinishing()) {
            RetainedWebEditor.destroyAll("activity finishing");
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // Give the retained editor back only under real memory pressure, and
        // only while it is off screen — merely putting the app in the
        // background must not cost the user their open project.
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
                || level == ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            RetainedWebEditor.destroyIfDetached("memory pressure");
        }
    }

    public void onPopupMenuItemClick(AdapterView<?> parent, View view, int position, long id) {
        super.onPopupMenuItemClick(parent, view, position, id);
        if (!(parent.getItemAtPosition(position) instanceof PopupItem item)) {
            return;
        }
        int titleId = item.titleId();
        if (titleId == R.string.menu_fab_scripts) {
            ScriptsFragment scriptsFragment = new ScriptsFragment();
            scriptsFragment.show(getSupportFragmentManager(), "Bottom Sheet Dialog Fragment");
        } else if (titleId == R.string.menu_fab_scan_qr) {
            startQrScan();
        } else if (titleId == R.string.menu_fab_full_screen) {
            if (fullScreen) {
                disableFullScreenMode();
            } else {
                enableFullScreenMode();
            }
        }
    }

    @SuppressLint("InlinedApi")
    private void enableFullScreenMode() {
        fullScreen = true;
        fullScreenBackCallback.setEnabled(true);
        binding.bottomNavigation.setVisibility(View.GONE);
        binding.navFade.setVisibility(View.GONE);
        setWebViewBottomMargin(0);

        WindowInsetsControllerCompat controller = systemBarsController();
        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        controller.hide(WindowInsetsCompat.Type.systemBars());
    }

    private void disableFullScreenMode() {
        fullScreen = false;
        fullScreenBackCallback.setEnabled(false);
        binding.bottomNavigation.setVisibility(View.VISIBLE);
        binding.navFade.setVisibility(View.VISIBLE);
        setWebViewBottomMargin(getResources().getDimensionPixelSize(R.dimen.bottom_bar_clearance));

        systemBarsController().show(WindowInsetsCompat.Type.systemBars());
    }

    /**
     * System-bars controller with the theme's light-bar appearance re-applied
     * each time. Hiding/showing the bars must not drop the dark-icon
     * appearance the theme sets — the old raw setSystemUiVisibility() path did,
     * leaving white icons on the light bar (see B15).
     */
    private WindowInsetsControllerCompat systemBarsController() {
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        boolean light = getResources().getBoolean(R.bool.light_system_bars);
        controller.setAppearanceLightStatusBars(light);
        controller.setAppearanceLightNavigationBars(light);
        return controller;
    }

    private void setWebViewBottomMargin(int margin) {
        // Editors put their retained WebView inside a container; the info page
        // still inflates a WebView directly.
        View webView = findViewById(R.id.webViewContainer);
        if (webView == null) {
            webView = findViewById(R.id.webView);
        }
        if (webView != null) {
            android.view.ViewGroup.MarginLayoutParams params =
                    (android.view.ViewGroup.MarginLayoutParams) webView.getLayoutParams();
            params.bottomMargin = margin;
            webView.setLayoutParams(params);
        }
    }

    @Override
    public void addPopupMenuItems(List<PopupItem> popupItems) {
        super.addPopupMenuItems(popupItems);
        // While controlling the mini (a live BLE session from an editor) leave
        // only full-screen: opening Scripts or the QR scanner would navigate
        // away from the editor and drop the connection the user is using.
        State state = AppStateRepository.getState().getValue();
        boolean controlling = state != null && state.getType() == State.STATE_CONTROL;
        if (!controlling) {
            popupItems.add(new PopupItem(R.string.menu_fab_scripts, R.drawable.ic_coding_24dp));
            popupItems.add(new PopupItem(R.string.menu_fab_scan_qr, R.drawable.ic_qr_scan_24dp));
        }
        popupItems.add(new PopupItem(R.string.menu_fab_full_screen, fullScreen ?
                R.drawable.ic_disable_full_screen_24dp : R.drawable.ic_enable_full_screen_24dp));
    }

    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Log.w(TAG, "onConfigurationChanged");
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            Log.w(TAG, "ORIENTATION_LANDSCAPE");

        } else if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
            Log.w(TAG, "ORIENTATION_PORTRAIT");
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    private void requestPushNotificationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            pushNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }
}