package cc.calliope.mini.fragment.editors;

import static cc.calliope.mini.utils.Constants.MINI_V2;
import static cc.calliope.mini.utils.Constants.MINI_V3;
import static cc.calliope.mini.utils.Constants.UNIDENTIFIED;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.ScaleAnimation;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.browser.customtabs.CustomTabColorSchemeParams;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.NavDirections;
import androidx.navigation.Navigation;
import androidx.preference.PreferenceManager;

import cc.calliope.mini.R;
import cc.calliope.mini.SnackbarHelper;
import cc.calliope.mini.databinding.FragmentEditorsBinding;
import cc.calliope.mini.utils.Constants;
import cc.calliope.mini.utils.Settings;
import cc.calliope.mini.utils.Utils;

public class EditorsFragment extends Fragment {
    private FragmentEditorsBinding binding;

    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentEditorsBinding.inflate(inflater, container, false);
        setupEditorViews();
        return binding.getRoot();
    }

    private void setupEditorViews() {
        setupEditorView(binding.clRow1, Editor.MAKECODE);
        setupEditorView(binding.clRow2, Editor.ROBERTA);
        setupEditorView(binding.clRow3, Editor.PYTHON);
        setupEditorView(binding.clRow4, Editor.CUSTOM);
    }

    private void setupEditorView(View view, Editor editor) {
        if (editor == null && view == null) {
            return;
        }

        setupClickAnimation(view);
        view.setOnClickListener(v -> openEditor(v, editor));

        ImageView imageView = view.findViewById(R.id.icon_image_view);
        TextView textView = view.findViewById(R.id.title_text_view);

        imageView.setImageResource(editor.getIconResId());
        textView.setText(editor.getTitleResId());
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    private void openEditor(View view, Editor editor) {
        Activity activity = getActivity();
        if (activity == null) {
            return;
        }

        if (Utils.isNetworkConnected(activity)) {
//            if (editor == Editor.BLOCKS) {
//                openWebPage(editor.getUrl_v2());
//            } else {
            int boardVersion;
            Context context = getContext();
            if (context == null) {
                boardVersion = UNIDENTIFIED;
            } else {
                SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
                boardVersion = preferences.getInt(Constants.CURRENT_DEVICE_VERSION, UNIDENTIFIED);
            }

            String url;
            if (boardVersion == MINI_V2) {
                url = editor.getUrl_v2();
            } else {
                url = editor.getUrl_v3();
            }

            if (editor == Editor.CUSTOM) {
                url = Settings.getCustomLink(getContext());
            }
            showWebFragment(url, editor.toString());
//            }
        } else {
            SnackbarHelper.errorSnackbar(binding.getRoot(), getString(R.string.error_snackbar_no_internet)).show();
        }
    }

    private void openWebPage(String url) {
        Activity activity = getActivity();
        if (activity == null) {
            return;
        }

        CustomTabColorSchemeParams colorSchemeParams = new CustomTabColorSchemeParams.Builder()
                .setToolbarColor(ContextCompat.getColor(activity, R.color.aqua_200))
                .build();

        CustomTabsIntent customTabsIntent = new CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setDefaultColorSchemeParams(colorSchemeParams)
                .build();

        String chromePackageName = "com.android.chrome";
        customTabsIntent.intent.setPackage(chromePackageName);

        if (isPackageInstalled(chromePackageName, activity.getPackageManager())) {
            customTabsIntent.launchUrl(activity, Uri.parse(url));
        } else {
            Intent playStoreIntent = new Intent(Intent.ACTION_VIEW);
            playStoreIntent.setData(Uri.parse("market://details?id=com.android.chrome"));
            activity.startActivity(playStoreIntent);
        }
    }

    private boolean isPackageInstalled(String packageName, PackageManager packageManager) {
        //TODO: Check if the package is installed
        return true;
//        try {
//            packageManager.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES);
//            return true;
//        } catch (PackageManager.NameNotFoundException e) {
//            return false;
//        }
    }

    private void showWebFragment(String url, String editorName) {
        Activity activity = getActivity();
        if (activity == null) {
            return;
        }

        NavController navController = Navigation.findNavController(activity, R.id.navigation_host_fragment);
        NavDirections webFragment = EditorsFragmentDirections.actionEditorsToWeb(url, editorName);
        navController.navigate(webFragment);
    }

    private void setupClickAnimation(View view) {
        view.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    // Animation downscale
                    ScaleAnimation scaleDown = new ScaleAnimation(1.0f, 0.9f, 1.0f, 0.9f,
                            Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
                    scaleDown.setDuration(100);
                    scaleDown.setFillAfter(true);
                    v.startAnimation(scaleDown);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    // Animation upscale
                    ScaleAnimation scaleUp = new ScaleAnimation(0.9f, 1.0f, 0.9f, 1.0f,
                            Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
                    scaleUp.setDuration(100);
                    scaleUp.setFillAfter(true);
                    v.startAnimation(scaleUp);
                    v.performClick();
                    return true;
            }
            return false;
        });
    }

}

