// AppConfig placeholder semantics + the AppConfig half of ModelDecodingTests.DeepLinkTests.websiteDomain.
package app.plead.android.services

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppConfigTests {
    /** Amendment bc: the website domain carries invites; the earlier hosts and the apex still route. */
    @Test fun websiteDomain() {
        assertEquals("www.plead-app.com", AppConfig.universalLinkHost)
        assertEquals("https://www.plead-app.com/join/ABC123", AppConfig.inviteURL(code = "ABC123"))
        assertEquals(setOf("plead-drab.vercel.app", "plead.app", "www.plead.app", "plead-app.com"), AppConfig.legacyUniversalLinkHosts)
        assertEquals("plead://login-callback", AppConfig.authRedirectURL)
    }

    @Test fun placeholdersReadAsUnset() {
        assertNull(AppConfig.key("goog_YOUR_REVENUECAT_PUBLIC_KEY"))
        assertNull(AppConfig.key("YOUR_APPSFLYER_DEV_KEY"))
        assertNull(AppConfig.key("  "))
        assertNull(AppConfig.string("$(SUPABASE_URL)"))
        assertEquals("goog_abc", AppConfig.key(" goog_abc "))
    }

    @Test fun supabaseURLFallsBackToPlaceholder() {
        assertEquals(URI("https://placeholder.supabase.co"), AppConfig.resolveSupabaseURL(""))
        assertEquals(URI("https://placeholder.supabase.co"), AppConfig.resolveSupabaseURL("not a url"))
        assertFalse(AppConfig.isConfigured(AppConfig.resolveSupabaseURL("https://YOUR-PROJECT-REF.supabase.co")))
        assertFalse(AppConfig.isConfigured(AppConfig.resolveSupabaseURL(null)))
        assertTrue(AppConfig.isConfigured(AppConfig.resolveSupabaseURL("https://abc.supabase.co")))
    }

    @Test fun uuidParsingIsStrict() {
        assertEquals(
            java.util.UUID.fromString("11111111-1111-1111-1111-111111111111"),
            parseUUID("11111111-1111-1111-1111-111111111111"),
        )
        assertNull(parseUUID("1-1-1-1-1"))
        assertNull(parseUUID(null))
        assertEquals("AAAAAAAA-0000-0000-0000-000000000014", parseUUID("aaaaaaaa-0000-0000-0000-000000000014")?.uuidString)
    }
}
