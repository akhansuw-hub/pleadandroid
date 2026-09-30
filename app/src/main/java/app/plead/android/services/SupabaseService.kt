// Port of ArgueWin/Services/SupabaseService.swift.
package app.plead.android.services

import android.content.Intent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.handleDeeplinks
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.serializer.KotlinXSerializer
import io.github.jan.supabase.storage.Storage
import java.util.UUID

/**
 * Process-wide Supabase client built from [AppConfig] (same project as iOS).
 *
 * - Every module decodes with [JSONCoding.json] (iOS: `db`/`functions` use `JSONDecoder.arguewin`, the encoder
 *   `JSONEncoder.supabase`).
 * - Auth persists its session on-device and restores it on launch (iOS `emitLocalSessionAsInitialSession`);
 *   the OAuth / magic-link redirect is `plead://login-callback` ([AppConfig.authRedirectURL]).
 * - The client is created lazily on first use, so demo mode (`AWDemo`) never touches it. With no project
 *   configured it points at the placeholder host, as on iOS; check [isConfigured] before relying on it.
 */
object SupabaseService {
    val isConfigured: Boolean get() = AppConfig.isSupabaseConfigured

    val shared: SupabaseClient by lazy {
        createSupabaseClient(
            supabaseUrl = AppConfig.supabaseURL.toString(),
            supabaseKey = AppConfig.supabaseAnonKey,
        ) {
            defaultSerializer = KotlinXSerializer(JSONCoding.json)
            install(Auth) {
                scheme = AppConfig.authRedirectScheme
                host = AppConfig.authRedirectHost
                alwaysAutoRefresh = true
                autoLoadFromStorage = true
                autoSaveToStorage = true
            }
            install(Postgrest)
            install(Realtime)
            install(Storage)
            install(Functions)
        }
    }

    /** The signed-in user's id, if a session exists (anonymous users included). */
    val currentUserId: UUID?
        get() = if (!isConfigured) null else parseUUID(shared.auth.currentUserOrNull()?.id)

    /**
     * Completes an OAuth / magic-link sign-in when [intent] is the `plead://login-callback` redirect.
     * Returns true when the intent was the auth callback (iOS: `DeepLink.authCallback` → `session(from:)`).
     */
    fun handleAuthCallback(intent: Intent?): Boolean {
        val data = intent?.data ?: return false
        if (!isConfigured || data.scheme != AppConfig.authRedirectScheme || data.host != AppConfig.authRedirectHost) return false
        shared.handleDeeplinks(intent)
        return true
    }
}
