package cc.calliope.mini.ui.fragment.home;

import android.app.Activity;
import android.os.Bundle;
import android.text.Html;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.ScaleAnimation;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.NavDirections;
import androidx.navigation.Navigation;

import java.util.Locale;

import cc.calliope.mini.R;
import cc.calliope.mini.databinding.FragmentHomeBinding;

public class HomeFragment extends Fragment {
    private FragmentHomeBinding binding;
    private boolean isLongPressHandled = false;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentHomeBinding.inflate(inflater, container, false);

        TextView appInfo = binding.appInfo;
        appInfo.setMovementMethod(LinkMovementMethod.getInstance());
        Spanned spanned = Html.fromHtml(getString(R.string.info_app));
        appInfo.setText(spanned);

        TextView appInfo2 = binding.appInfo2;
        appInfo2.setMovementMethod(LinkMovementMethod.getInstance());
        Spanned spanned2 = Html.fromHtml(getString(R.string.info_app2));
        appInfo2.setText(spanned2);

        setupClickAnimation(binding.openWebButton);
        binding.openWebButton.setOnClickListener(this::showWebFragment);
        binding.openWebButton.setOnLongClickListener(this::onLongPress);

        ImageView imageView = binding.openWebButton.findViewById(R.id.icon_image_view);
        TextView textView = binding.openWebButton.findViewById(R.id.title_text_view);

        imageView.setImageResource(R.drawable.document_ic);
        textView.setText(R.string.info_web);

        return binding.getRoot();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    private void showWebFragment(View view) {
        if (isLongPressHandled) {
            isLongPressHandled = false;
            return;
        }

        Activity activity = getActivity();
        if (activity == null) {
            return;
        }

        Locale locale = Locale.getDefault();
        String language = locale.getLanguage();
//        String language = switch (locale.getLanguage()) {
//            case "de" -> "de";
//            case "uk" -> "uk";
//            default -> "";
//        };

        String url = "https://app.calliope.cc/android/" + language;

        NavController navController = Navigation.findNavController(view);
        NavDirections action = HomeFragmentDirections.actionHomeToInfo(url, "Info");
        navController.navigate(action);
    }

    private boolean onLongPress(View view) {
        isLongPressHandled = true;
        //Toast.makeText(getContext(), "Long press detected", Toast.LENGTH_SHORT).show();
        Log.d("HomeFragment", "Long press detected");
        return true;
    }

    private void setupClickAnimation(View view) {
        view.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    isLongPressHandled = false;
                    // Animation downscale
                    ScaleAnimation scaleDown = new ScaleAnimation(1.0f, 0.9f, 1.0f, 0.9f,
                            Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
                    scaleDown.setDuration(100);
                    scaleDown.setFillAfter(true);
                    v.startAnimation(scaleDown);
                    return false;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    // Animation upscale
                    ScaleAnimation scaleUp = new ScaleAnimation(0.9f, 1.0f, 0.9f, 1.0f,
                            Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
                    scaleUp.setDuration(100);
                    scaleUp.setFillAfter(true);
                    v.startAnimation(scaleUp);

                    if (!isLongPressHandled) {
                        v.performClick();
                    }
                    return true;
            }
            return false;
        });
    }
}