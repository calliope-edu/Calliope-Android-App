package cc.calliope.mini.ui.fragment.editors;

import static cc.calliope.mini.utils.Constants.MINI_V2;
import static cc.calliope.mini.utils.Constants.UNIDENTIFIED;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.ScaleAnimation;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.NavDirections;
import androidx.navigation.Navigation;
import androidx.preference.PreferenceManager;

import cc.calliope.mini.R;
import cc.calliope.mini.ui.SnackbarHelper;
import cc.calliope.mini.databinding.FragmentEditorsBinding;
import cc.calliope.mini.ui.dialog.DialogUtils;
import cc.calliope.mini.utils.Constants;
import cc.calliope.mini.utils.settings.Settings;
import cc.calliope.mini.utils.Utils;

public class EditorsFragment extends Fragment {
    private FragmentEditorsBinding binding;
    private boolean isLongPressHandled = false;

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
        if (editor == null || view == null) {
            return;
        }

        view.setClickable(true);

        view.setOnClickListener(v -> openEditor(v, editor));

        setupClickAnimation(view, editor);

        ImageView imageView = view.findViewById(R.id.icon_image_view);
        TextView textView = view.findViewById(R.id.title_text_view);
        ImageButton infoButton = view.findViewById(R.id.info_button);

        imageView.setImageResource(editor.getIconResId());
        textView.setText(editor.getTitleResId());
        infoButton.setOnClickListener(v -> onLongPress(v, editor));
        if (editor == Editor.CUSTOM) {
            infoButton.setVisibility(View.GONE);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    private void openEditor(View view, Editor editor) {
        if (isLongPressHandled) {
            isLongPressHandled = false;
            return;
        }

        Activity activity = getActivity();
        if (activity == null) {
            return;
        }

        if (Utils.isNetworkConnected(activity)) {
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
        } else {
            SnackbarHelper.errorSnackbar(binding.getRoot(), getString(R.string.error_snackbar_no_internet)).show();
        }
    }

    private boolean onLongPress(View view, Editor editor) {
        isLongPressHandled = true;

        ScaleAnimation scaleUp = new ScaleAnimation(0.9f, 1.0f, 0.9f, 1.0f,
                Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
        scaleUp.setDuration(100);
        scaleUp.setFillAfter(true);
        view.startAnimation(scaleUp);

        String dialogTitle = editor.getTitleResId() == 0 ? "" : getString(editor.getTitleResId());

        if (editor == Editor.CUSTOM) {
            String link = Settings.getCustomLink(getContext());
            DialogUtils.showEditDialog(getContext(), dialogTitle, link, output -> {
                Settings.setCustomLink(getContext(), output);
            });
        } else {
            String message = editor.getInfoResId() == 0 ? "" : getString(editor.getInfoResId());
            DialogUtils.showInfoDialog(getContext(), dialogTitle, message);
        }

        return true;
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

    private void setupClickAnimation(View view, Editor editor) {
        view.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    isLongPressHandled = false;
                    ScaleAnimation scaleDown = new ScaleAnimation(1.0f, 0.9f, 1.0f, 0.9f,
                            Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
                    scaleDown.setDuration(100);
                    scaleDown.setFillAfter(true);
                    v.startAnimation(scaleDown);
                    return false;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!isLongPressHandled) {
                        ScaleAnimation scaleUp = new ScaleAnimation(0.9f, 1.0f, 0.9f, 1.0f,
                                Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
                        scaleUp.setDuration(100);
                        scaleUp.setFillAfter(true);
                        v.startAnimation(scaleUp);
                        v.performClick();
                    }
                    isLongPressHandled = false;
                    return true;
            }
            return false;
        });

        view.setOnLongClickListener(v -> onLongPress(v, editor));
    }
}