package cc.calliope.mini.bridge

import android.net.Uri

/**
 * Single source of truth for which web origins count as "Calliope Campus"
 * and therefore get the native-proxy bridge.
 *
 * Used in two places:
 *   - EditorsFragment routing — any editor (including a custom editor
 *     pointed at campus) whose URL matches loads in WebProxyFragment, where
 *     the JS bridge is injected, instead of the legacy WebFragment.
 *   - BridgeController origin gate — the bridge only services requests while
 *     the WebView is actually showing one of these origins, so the
 *     firmware-flashing capability can't leak to a page the campus site
 *     navigates or links out to.
 *
 * Matched hosts (https only):
 *   - campus.calliope.cc          and any sub.campus.calliope.cc
 *   - calliope-campus.pages.dev   and any sub.calliope-campus.pages.dev
 *     (covers Cloudflare Pages preview branches such as
 *      feature-native-proxy.calliope-campus.pages.dev)
 *
 * Suffix matching is anchored on a leading dot so "evilcampus.calliope.cc"
 * or "campus.calliope.cc.attacker.com" do NOT match.
 */
object CampusUrls {

    private val ALLOWED_HOSTS = listOf(
        "campus.calliope.cc",
        "calliope-campus.pages.dev",
    )

    fun isCampusUrl(url: String?): Boolean {
        if (url.isNullOrEmpty()) return false
        val uri = try { Uri.parse(url) } catch (_: Exception) { return false }
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase() ?: return false
        return ALLOWED_HOSTS.any { allowed ->
            host == allowed || host.endsWith(".$allowed")
        }
    }
}
