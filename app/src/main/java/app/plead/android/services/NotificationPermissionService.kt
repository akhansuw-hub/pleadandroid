// Port of ArgueWin/Services/NotificationPermissionService.swift. Android: the POST_NOTIFICATIONS runtime permission
// (API 33+; below that notifications are allowed unless the user turned them off in system settings).
package app.plead.android.services

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.plead.android.app.DemoHarness
import app.plead.android.app.PleadApplication

/**
 * Mirror of the OS notification permission, persisted as `notification_status` (brief §10/§11) so app logic can
 * read it without an async call. Raw values are the iOS ones; Android only ever reports `notDetermined`, `denied`
 * or `authorized` (provisional / ephemeral are iOS-only states, kept for parity).
 */
enum class NotificationStatus(val rawValue: String) {
    notDetermined("notDetermined"), denied("denied"), authorized("authorized"), provisional("provisional"), ephemeral("ephemeral");

    val isDetermined: Boolean get() = this != notDetermined

    /** Court notices can be delivered. */
    val isAllowed: Boolean get() = this == authorized || this == provisional || this == ephemeral

    companion object {
        fun fromRaw(raw: String?): NotificationStatus? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * What a permission pre-prompt's primary CTA does for the current OS status. Pure (unit-tested):
 * the native prompt only ever fires from the CTA, and never when the OS has already decided.
 */
enum class PermissionCTA {
    /** Status is undetermined: the CTA shows the native prompt. */
    requestNative,

    /** Already decided: no fake flow; the CTA just continues. */
    continueOnly,
}

/**
 * Two-step notification permission (brief §7/§10). The native prompt is requested only from
 * [request], which the pre-prompt CTA calls; never from a composable body.
 */
class NotificationPermissionService(
    private val push: PushService?,
    private val defaults: UserDefaults = UserDefaults.standard,
    private val context: Context? = PleadApplication.contextOrNull,
) {
    var status: NotificationStatus by mutableStateOf(NotificationStatus.fromRaw(defaults.string(storageKey)) ?: NotificationStatus.notDetermined)
        private set

    /** `AWPermissions fresh` (demo): report `notDetermined` until the CTA is tapped. */
    private var demoFresh = DemoHarness.permissionsFresh

    /**
     * Shows the system POST_NOTIFICATIONS dialog and returns whether it was granted. Installed by MainActivity
     * (an `ActivityResultLauncher` needs an Activity); null before that, when [request] only re-reads the state.
     */
    var systemPrompt: (suspend () -> Boolean)? = null

    /** Re-read the OS state (launch, foreground, back from Settings). */
    fun refresh() {
        if (demoFresh) {
            set(NotificationStatus.notDetermined)
            return
        }
        val context = context ?: return
        set(osStatus(context))
    }

    /**
     * Shows the native prompt if (and only if) Android hasn't decided yet, then registers for pushes
     * when allowed. Returns the resulting status.
     */
    suspend fun request(): NotificationStatus {
        demoFresh = false
        refresh()
        if (status == NotificationStatus.notDetermined) {
            val prompt = systemPrompt
            if (prompt != null) {
                defaults.set(true, requestedKey)
                runCatching { prompt() }
            }
            refresh()
        }
        if (status.isAllowed) push?.registerIfAuthorized()
        return status
    }

    private fun osStatus(context: Context): NotificationStatus {
        val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return if (enabled) NotificationStatus.authorized else NotificationStatus.denied
        }
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return when {
            granted && enabled -> NotificationStatus.authorized
            granted -> NotificationStatus.denied
            defaults.bool(requestedKey) -> NotificationStatus.denied
            else -> NotificationStatus.notDetermined
        }
    }

    private fun set(new: NotificationStatus) {
        val old = status
        status = new
        defaults.set(new.rawValue, storageKey)
        analyticsEvent(old, new)?.let { Analytics.track(it) }
    }

    companion object {
        const val storageKey = "notification_status"

        /** Android only: the runtime prompt has been shown once (after that, "not granted" means denied). */
        const val requestedKey = "notification_permission_requested"

        fun cta(status: NotificationStatus): PermissionCTA =
            if (status.isDetermined) PermissionCTA.continueOnly else PermissionCTA.requestNative

        /**
         * `notification_permission_granted` / `_denied` when the OS decision changes (the prompt, or the
         * user flipping it in system settings). Pure; unit-tested.
         */
        fun analyticsEvent(from: NotificationStatus, to: NotificationStatus): String? {
            if (from == to || (from.isAllowed == to.isAllowed && from.isDetermined)) return null
            if (to.isAllowed) return "notification_permission_granted"
            if (to == NotificationStatus.denied) return "notification_permission_denied"
            return null
        }
    }
}
