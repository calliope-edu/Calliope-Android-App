package cc.calliope.mini.ui.fragment.info;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import cc.calliope.mini.databinding.FragmentInfoBinding;

/**
 * A plain web page behind a toolbar (the info pages opened from Home).
 *
 * <p>Deliberately not an editor: the page holds no project and no BLE
 * session, so it gets a view-scoped WebView with none of the editor
 * machinery — no retained page, no JavaScript bridge, no download or
 * flashing hooks.
 */
public class InfoFragment extends Fragment {
    /** Navigation argument (shared with the editor destinations). */
    private static final String ARG_URL = "editorUrl";
    private static final String STATE_WEB_VIEW = "webViewState";

    private FragmentInfoBinding binding;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentInfoBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        binding.topAppBar.setNavigationOnClickListener(v ->
                Navigation.findNavController(v).navigateUp());

        WebView webView = binding.webView;
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setDefaultTextEncodingName("utf-8");
        webView.setWebViewClient(new WebViewClient());

        Bundle state = savedInstanceState != null ? savedInstanceState.getBundle(STATE_WEB_VIEW) : null;
        if (state == null || webView.restoreState(state) == null) {
            Bundle arguments = getArguments();
            String url = arguments != null ? arguments.getString(ARG_URL) : null;
            if (url != null) {
                webView.loadUrl(url);
            }
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (binding != null) {
            Bundle bundle = new Bundle();
            binding.webView.saveState(bundle);
            outState.putBundle(STATE_WEB_VIEW, bundle);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        WebView webView = binding.webView;
        // destroy() requires the WebView to be out of the view system.
        if (webView.getParent() instanceof ViewGroup parent) {
            parent.removeView(webView);
        }
        webView.destroy();
        binding = null;
    }
}
