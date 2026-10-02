// Port of ArgueWin/Services/AppConfig.swift.
package app.plead.android.services

import app.plead.android.BuildConfig
import java.net.URI

/**
 * Runtime configuration read from `BuildConfig`, which `app/build.gradle.kts` populates from
 * `android/local.properties` (gitignored; see `local.properties.example`), the Android counterpart of
 * Info.plist ← `Config.xcconfig`. Placeholder semantics are the iOS ones: a value containing `YOUR_` is unset.
 */
object AppConfig {
    val supabaseURL: URI = resolveSupabaseURL(BuildConfig.SUPABASE_URL)

    val supabaseAnonKey: String = string(BuildConfig.SUPABASE_ANON_KEY) ?: "placeholder-anon-key"

    /** null when the key is missing or still the example placeholder — purchases are then disabled. */
    val revenueCatAPIKey: String? = key(BuildConfig.REVENUECAT_API_KEY)

    /** AppsFlyer dev key (amendment at), null when missing or still the example placeholder: AppsFlyer is then not started. */
    val appsFlyerDevKey: String? = key(BuildConfig.APPSFLYER_DEV_KEY)

    /**
     * iOS: the App Store app id AppsFlyer reports under. AppsFlyer identifies an Android app by its package
     * name ([BuildConfig.APPLICATION_ID]), so there is no Android value: always null.
     */
    val appsFlyerAppID: String? = null

    /** Web (server) OAuth client id for Sign in with Google via Credential Manager (amendment az); null when unset. */
    val googleWebClientID: String? = key(BuildConfig.GOOGLE_WEB_CLIENT_ID)

    val isSupabaseConfigured: Boolean get() = isConfigured(supabaseURL)

    /** Where magic links / OAuth send the user back to the app. */
    const val authRedirectURL = "plead://login-callback"
    const val authRedirectScheme = "plead"
    const val authRedirectHost = "login-callback"

    /** Universal-link host for couple invites (the website, CONTRACTS-v2 amendment bc). */
    const val universalLinkHost = "www.plead-app.com"

    /**
     * Other hosts whose links still route if Android opens them: the earlier website hosts (amendments ap, bc) and
     * the apex `plead-app.com`, which the manifest's App Links filter also claims (it redirects to `www`).
     */
    val legacyUniversalLinkHosts: Set<String> = setOf("plead-drab.vercel.app", "plead.app", "www.plead.app", "plead-app.com")

    fun inviteURL(code: String): String = "https://$universalLinkHost/join/$code"

    /** iOS opens the App Store subscriptions page; Android opens Google Play's, scoped to this app. */
    const val manageSubscriptionsURL = "https://play.google.com/store/account/subscriptions?package=${BuildConfig.APPLICATION_ID}"

    /** "1.0.0 (6)" */
    val appVersion: String get() = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    // MARK: - Placeholder semantics (pure, unit-tested)

    /** Trimmed value; null when empty or an unexpanded `$(…)` reference (iOS `string(_:)`). */
    fun string(raw: String?): String? {
        val trimmed = raw?.trim() ?: return null
        return if (trimmed.isEmpty() || trimmed.startsWith("$(")) null else trimmed
    }

    /** A secret that is unset while it still contains the example `YOUR_` marker. */
    fun key(raw: String?): String? = string(raw)?.takeUnless { it.contains("YOUR_") }

    fun resolveSupabaseURL(raw: String?): URI {
        string(raw)?.let { s ->
            val uri = runCatching { URI(s) }.getOrNull()
            if (uri?.host != null) return uri
        }
        return URI("https://placeholder.supabase.co")
    }

    fun isConfigured(url: URI): Boolean =
        !url.toString().contains("YOUR-PROJECT-REF") && url.host != "placeholder.supabase.co"
}
