package cc.calliope.mini

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.fragment.app.Fragment
import java.util.Locale

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
        val language = when(locale.language) {
            "de" -> "de" // German
            "uk" -> "uk" // Ukrainian
            else -> ""
        }

        // Add the locale and theme parameters to the URL
        val urlWithLocaleAndTheme = "https://app.calliope.cc/android/$language"

        webView.loadUrl(urlWithLocaleAndTheme)
        return view
    }
}
