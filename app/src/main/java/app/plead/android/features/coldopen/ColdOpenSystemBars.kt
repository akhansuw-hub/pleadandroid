// iOS `RootView.statusBarHidden(model.coldOpen.isPlaying)`: the status bar is hidden from the cold open's first frame.
// RootScreen's SideEffect owns the bar once composition runs, but two things showed it on Android for the first frames:
// the SideEffect lands after the window's first draw, and, on Android 11+, the system splash window controls the bar
// until it exits; the app's hide request is then played as the system's fade (about 300 ms over the cold open). So the
// launch requests the hide before the window is added, and while the cold open plays it takes over any status-bar
// insets animation as soon as it starts and finishes it hidden.
package app.plead.android.features.coldopen

import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.view.WindowInsetsAnimationControlListener
import android.view.WindowInsetsAnimationController
import androidx.annotation.RequiresApi
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

object ColdOpenSystemBars {
    /**
     * Call from `Activity.onCreate` (before `setContent`) when the cold open plays. [stillHidden] is read on every
     * status-bar animation: once the cold open has ended the takeover detaches and RootScreen's own show() animates.
     */
    fun hideBeforeFirstFrame(window: Window, stillHidden: () -> Boolean) {
        WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.statusBars())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) finishHideAnimationsAtOnce(window, stillHidden)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun finishHideAnimationsAtOnce(window: Window, stillHidden: () -> Boolean) {
        val decor = window.decorView
        // The decor view is the root of the window's insets-animation dispatch; CONTINUE_ON_SUBTREE leaves Compose's
        // own callbacks below it untouched.
        decor.setWindowInsetsAnimationCallback(
            object : WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onStart(
                    animation: WindowInsetsAnimation,
                    bounds: WindowInsetsAnimation.Bounds,
                ): WindowInsetsAnimation.Bounds {
                    if (!stillHidden()) {
                        decor.detach()
                    } else if (animation.typeMask and WindowInsets.Type.statusBars() != 0) {
                        // Replaces the system's hide animation with one finished hidden at once. Posted: cancelling the
                        // system's animation inside its own onStart crashes its first update.
                        decor.post {
                            if (stillHidden()) {
                                window.insetsController?.controlWindowInsetsAnimation(
                                    WindowInsets.Type.statusBars(), 0L, null, null, FinishHidden,
                                )
                            }
                        }
                    }
                    return bounds
                }

                override fun onProgress(insets: WindowInsets, running: MutableList<WindowInsetsAnimation>): WindowInsets = insets
            },
        )
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun View.detach() = post { setWindowInsetsAnimationCallback(null) }

    @RequiresApi(Build.VERSION_CODES.R)
    private object FinishHidden : WindowInsetsAnimationControlListener {
        override fun onReady(controller: WindowInsetsAnimationController, types: Int) = controller.finish(false)
        override fun onFinished(controller: WindowInsetsAnimationController) = Unit
        override fun onCancelled(controller: WindowInsetsAnimationController?) = Unit
    }
}
