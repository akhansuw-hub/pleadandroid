// Hand-off between MainActivity (which receives VIEW intents) and the deep-link router (wave 2a `DeepLinkRouter`,
// iOS `.onOpenURL` / `onContinueUserActivity` → `model.links.handle(url:)`).
package app.plead.android.app

import android.net.Uri
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object LaunchLinks {
    private val _links = MutableSharedFlow<Uri>(replay = 1, extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Every `plead://…` / `https://www.plead-app.com/join/…` link the app was opened with, newest replayed. */
    val links: SharedFlow<Uri> = _links.asSharedFlow()

    fun emit(uri: Uri) {
        _links.tryEmit(uri)
    }

    /** Call after handling so a configuration change doesn't replay the same link. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun consumed() {
        _links.resetReplayCache()
    }
}
