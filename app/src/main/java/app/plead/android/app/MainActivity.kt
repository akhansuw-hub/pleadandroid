// Single activity (launchMode singleTask). Android counterpart of the `WindowGroup` in ArgueWinApp.swift plus the
// tap / link half of AppDelegate.swift: reads the demo-harness extras, builds the AppModel, forwards links and
// notification taps, mirrors the scene phase (becameActive / resignedActive) and hosts RootScreen.
package app.plead.android.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.core.graphics.toColorInt
import app.plead.android.designsystem.PleadTheme
import app.plead.android.features.coldopen.ColdOpenSystemBars
import app.plead.android.services.PushService
import app.plead.android.widgets.WidgetPreviewOverlay
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

class MainActivity : ComponentActivity() {
    private lateinit var model: AppModel
    private var wasBackgrounded = false
    private var permissionContinuation: CancellableContinuation<Boolean>? = null

    /** POST_NOTIFICATIONS (API 33+): the system prompt behind NotificationPermissionService.request(). */
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionContinuation?.takeIf { it.isActive }?.resume(granted)
        permissionContinuation = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The iOS argument domain lives for one launch: read the extras once per process start.
        if (savedInstanceState == null && (application as PleadApplication).existingModel == null) LaunchArguments.load(intent)
        // Light-only app (iOS forces `.light`): dark status/navigation bar icons over cream.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(TRANSPARENT, SCRIM),
            navigationBarStyle = SystemBarStyle.light(TRANSPARENT, SCRIM),
        )
        super.onCreate(savedInstanceState)
        model = (application as PleadApplication).appModel()
        // The cold open was decided with the model (PleadApplication, iOS "before the first frame"): hide the status
        // bar now, before the window's first draw, rather than from RootScreen's SideEffect after it. RootScreen keeps
        // owning it from here on and shows it again when the cold open ends.
        if (model.coldOpen.isPlaying) ColdOpenSystemBars.hideBeforeFirstFrame(window) { model.coldOpen.isPlaying }
        installActivitySeams()
        if (!modelStarted) {
            modelStarted = true
            model.start()
            DemoHarness.apply(to = model)
        }
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            PleadTheme {
                Box {
                    RootScreen(model)
                    // DEBUG `AWWidgetPreview`: renders the real widgets / court-session notification over the app.
                    WidgetPreviewOverlay()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        installActivitySeams()
        // Returning from background never replays the cold open.
        if (wasBackgrounded) {
            wasBackgrounded = false
            model.coldOpen.resumedFromBackground()
        }
        // Users returning from system settings: mirror the notification state; then
        // `register_push {timezone}` (coalesced with the launch call).
        lifecycleScope.launch { model.becameActive() }
        if (model.phase == AppGate.Destination.tabs || model.phase == AppGate.Destination.paywall) {
            lifecycleScope.launch { model.store.refresh() }
        }
    }

    override fun onPause() {
        super.onPause()
        model.resignedActive()
    }

    override fun onStop() {
        super.onStop()
        wasBackgrounded = true
    }

    /** Things only an Activity can do: Google Play's purchase sheet and the runtime notification prompt. */
    private fun installActivitySeams() {
        model.purchases.activity = WeakReference(this)
        model.notifications.systemPrompt = {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                true
            } else {
                suspendCancellableCoroutine { cont ->
                    permissionContinuation = cont
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }

    /**
     * iOS `.onOpenURL` / universal links / `userNotificationCenter(_:didReceive:)`: every `plead://…` or
     * `https://www.plead-app.com/join/…` link goes to the deep-link router (the auth callback included, which
     * the router hands to AuthService); a tapped court notification carries its payload as extras.
     */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val uri = intent.data
        if (intent.action == Intent.ACTION_VIEW && uri != null) {
            LaunchLinks.emit(uri)
            return
        }
        val extras = intent.extras ?: return
        if (!extras.containsKey("link") && !extras.containsKey("case_id")) return
        val payload = extras.keySet().associateWith { key -> extras.getString(key) }
        // `push_opened`, then `data.link` (plead://…) or the legacy `{case_id, screen}`.
        PushService.shared.opened(payload)?.let(model.links::handle)
    }

    private companion object {
        const val TRANSPARENT = android.graphics.Color.TRANSPARENT
        val SCRIM = "#80FFF7F0".toColorInt()

        /** `AppModel.start()` + `DemoHarness.apply(to:)` run once per process (iOS `onAppear` of the root). */
        var modelStarted = false
    }
}
