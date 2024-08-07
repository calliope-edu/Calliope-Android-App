package cc.calliope.mini.fragment.editors;

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.ScaleAnimation;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.NavDirections;
import androidx.navigation.Navigation;

import cc.calliope.mini.R;
import cc.calliope.mini.databinding.FragmentEditorsBinding;
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
        setupEditorView(binding.clRow3, Editor.CUSTOM);
        binding.clRow4.setVisibility(View.GONE);
    }

    private void setupEditorView(View view, Editor editor) {
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

    private void openEditor(View view, Editor editor){
        Activity activity = getActivity();
        if (activity == null){
            return;
        }

        if (Utils.isNetworkConnected(activity)) {
            String url = editor.getUrl();
            if (editor == Editor.CUSTOM) {
                url = Settings.getCustomLink(getContext());
            }
            showWebFragment(url, editor.toString());
        } else {
            Utils.errorSnackbar(binding.getRoot(), getString(R.string.error_snackbar_no_internet)).show();
        }
    }

    private void showWebFragment(String url, String editorName) {
        Activity activity = getActivity();
        if (activity == null){
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

