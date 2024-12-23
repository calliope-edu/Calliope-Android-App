package cc.calliope.mini.activity;

import android.Manifest;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;

import java.io.File;
import java.util.List;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.ui.NavigationUI;

import cc.calliope.mini.popup.PopupItem;
import cc.calliope.mini.R;
import cc.calliope.mini.databinding.ActivityMainBinding;
import cc.calliope.mini.dialog.scripts.ScriptsFragment;
import cc.calliope.mini.utils.Utils;

public class MainActivity extends BaseActivity {
    private static final String TAG = "MainActivity";
    private ActivityMainBinding binding;
    private boolean fullScreen = false;
    private int currentFragment;
    private int previousFragment;
    private final ActivityResultLauncher<String> pushNotificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    Log.i(TAG, "NotificationPermission is Granted");
                } else {
                    Log.w(TAG, "NotificationPermission NOT Granted");
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setPatternFab(binding.patternFab);

        NavController navController = Navigation.findNavController(this, R.id.navigation_host_fragment);
        navController.addOnDestinationChangedListener((controller, destination, arguments) -> {
            previousFragment = currentFragment;
            currentFragment = destination.getId();

            if (currentFragment == R.id.navigation_web) {
                binding.bottomNavigation.setVisibility(View.GONE);
                binding.patternFab.moveDown();
            } else if (previousFragment == R.id.navigation_web){
                binding.bottomNavigation.setVisibility(View.VISIBLE);
                binding.patternFab.moveUp();
            }

            if (currentFragment == R.id.navigation_help) {
                binding.patternFab.setVisibility(View.GONE);
            } else if (previousFragment == R.id.navigation_help){
                binding.patternFab.setVisibility(View.VISIBLE);
            }

            Log.v(TAG, "Destination id: " + destination.getId());
            Log.v(TAG, "Select item id: " + binding.bottomNavigation.getSelectedItemId());
        });
        NavigationUI.setupWithNavController(binding.bottomNavigation, navController);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPushNotificationPermission();
        }

        externalStorageVolumes();
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
    }

    @Override
    public void onBackPressed() {
        if (fullScreen) {
            disableFullScreenMode();
        } else {
            super.onBackPressed();
        }
    }

//    @Override
//    public void onItemFabMenuClicked(View view) {
//        super.onItemFabMenuClicked(view);
//        if (view.getId() == R.id.itemFullScreen) {
//            if (fullScreen) {
//                disableFullScreenMode();
//            } else {
//                enableFullScreenMode();
//            }
//        } else if (view.getId() == R.id.itemScripts) {
//            ScriptsFragment scriptsFragment = new ScriptsFragment();
//            scriptsFragment.show(getSupportFragmentManager(), "Bottom Sheet Dialog Fragment");
//        }
//    }

    public void onPopupMenuItemClick(AdapterView<?> parent, View view, int position, long id) {
        super.onPopupMenuItemClick(parent, view, position, id);
        if (position == 1) {
            ScriptsFragment scriptsFragment = new ScriptsFragment();
            scriptsFragment.show(getSupportFragmentManager(), "Bottom Sheet Dialog Fragment");
        }else if(position == 2){
            if (fullScreen) {
                disableFullScreenMode();
            } else {
                enableFullScreenMode();
            }
        }
    }

    private void enableFullScreenMode() {
        fullScreen = true;
        binding.bottomNavigation.setVisibility(View.GONE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    private void disableFullScreenMode() {
        fullScreen = false;
        binding.bottomNavigation.setVisibility(View.VISIBLE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    @Override
    public void addPopupMenuItems(List<PopupItem> popupItems) {
        super.addPopupMenuItems(popupItems);
        popupItems.add(new PopupItem(R.string.menu_fab_scripts, R.drawable.ic_coding_black_24dp));
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
        if (currentFragment != R.id.navigation_web) {
            recreate();
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