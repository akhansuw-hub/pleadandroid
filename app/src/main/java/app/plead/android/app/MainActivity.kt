// Single activity (launchMode singleTask). Android counterpart of the `WindowGroup` in ArgueWinApp.swift:
// reads the demo-harness extras, forwards links, and hosts the Compose tree.
package app.plead.android.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import androidx.core.graphics.toColorInt
import app.plead.android.designsystem.PleadTheme
import app.plead.android.services.SupabaseService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // The iOS argument domain lives for one launch: read the extras once per process start.
        if (savedInstanceState == null) LaunchArguments.load(intent)
        // Light-only app (iOS forces `.light`): dark status/navigation bar icons over cream.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(TRANSPARENT, SCRIM),
            navigationBarStyle = SystemBarStyle.light(TRANSPARENT, SCRIM),
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleLink(intent)

        setContent {
            PleadTheme {
                // Wave 2b replaces this with RootScreen(model) (AppGate → onboarding / paywall / tabs).
                val router = remember { AppRouter().also(::applyDemoNavigation) }
                MainTabScreen(router)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLink(intent)
    }

    /** `plead://login-callback` completes auth; every other link goes to the deep-link router. */
    private fun handleLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW) return
        if (!DemoHarness.isDemo && SupabaseService.handleAuthCallback(intent)) return
        LaunchLinks.emit(uri)
    }

    /** Wave-1 subset of `DemoHarness.apply(to:)`: the starting tab (the rest needs AppModel, wave 2b). */
    private fun applyDemoNavigation(router: AppRouter) {
        if (!DemoHarness.isDemo) return
        when (DemoHarness.tab) {
            DemoHarness.Tab.cases -> router.tab = AppTab.cases
            DemoHarness.Tab.court -> router.tab = AppTab.court
            DemoHarness.Tab.us -> router.tab = AppTab.us
            else -> Unit
        }
    }

    private companion object {
        const val TRANSPARENT = android.graphics.Color.TRANSPARENT
        val SCRIM = "#80FFF7F0".toColorInt()
    }
}
