package cc.calliope.mini

import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.fragment.app.Fragment
import java.util.Locale
import android.app.UiModeManager
import android.content.Context

class WebInfoFragment : Fragment() {
    companion object {
        fun newInstance() = WebInfoFragment()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_web, container, false)
        val webView: WebView = view.findViewById(R.id.webView)

        // Get the current language of the device
        val locale = Locale.getDefault()
        val language = locale.language // ISO language code, e.g., "en", "ua", etc.

        // Detect the current theme (dark or light)
        val uiModeManager = requireContext().getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        val currentMode = uiModeManager.currentModeType
        val theme = if (currentMode == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"

        // Add the locale and theme parameters to the URL
        val urlWithLocaleAndTheme = "https://app.calliope.cc/android/?locale=$language&theme=$theme"

        webView.loadUrl(urlWithLocaleAndTheme)
        return view
    }
}
