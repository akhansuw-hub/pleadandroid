// Port of ArgueWin/Features/ColdOpen/ColdOpenCoordinator.swift.
package app.plead.android.features.coldopen

import android.content.res.Resources
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import app.plead.android.app.ColdOpenHost
import app.plead.android.app.DemoHarness
import app.plead.android.app.PleadApplication
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns whether the cold open plays (full cinematic, short logo sting, or nothing), preloads its frames,
 * and guarantees completion (tap-skip, end of timeline, or a hard timeout). Owned by `AppModel`; `RootScreen`
 * shows [ColdOpenView] while [isPlaying] and crossfades to whatever the gate decides when it ends.
 *
 * Timers run in [scope] (the main dispatcher by default; tests pass a `TestScope`); frames decode on [decodeDispatcher].
 */
class ColdOpenCoordinator(
    val launchState: AppLaunchState = AppLaunchState(),
    val hardTimeout: Duration = defaultHardTimeout,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val decodeDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val resources: () -> Resources? = { PleadApplication.contextOrNull?.resources },
) : ColdOpenHost {
    enum class Mode {
        /** First launch ever: the 5.8 s cinematic (static crossfades under Reduce Motion). */
        full,

        /** Later cold launches: 1.2 s logo card. */
        sting,

        /** Returning from background, Reduce Motion once seen, demo runs, or finished. */
        none,
    }

    enum class LaunchKind {
        /** Process launch. */
        cold,

        /** Returning from background: never replays. */
        resume,
    }

    data class Input(
        val launch: LaunchKind = LaunchKind.cold,
        val hasSeenColdOpen: Boolean = false,
        val reduceMotion: Boolean = false,
        /** `AWColdOpen full|sting|none` (debug), which wins over everything else. */
        val forced: Mode? = null,
        /** `AWDemo YES` without a forced mode: stay out of the way of screenshot runs. */
        val demo: Boolean = false,
    )

    enum class Completion { finished, skipped, timedOut }

    var mode: Mode by mutableStateOf(Mode.none)
        private set
    override var isPlaying: Boolean by mutableStateOf(false)
        private set
    var reduceMotion: Boolean by mutableStateOf(false)
        private set

    /** Decoded frames by asset name (released shortly after completion). */
    var frames: Map<String, ImageBitmap> by mutableStateOf(emptyMap())
        private set
    var framesReady: Boolean by mutableStateOf(false)
        private set

    /** How the last playback ended (for tests / diagnostics). */
    var completion: Completion? by mutableStateOf(null)
        private set

    private var timeoutTask: Job? = null
    private var preloadTask: Job? = null

    /** Process launch: decide from persisted state and start playback if needed. */
    fun launch(forced: Mode? = null, demo: Boolean = false, reduceMotion: Boolean, preload: Boolean = true): Mode {
        val mode = decide(
            Input(launch = LaunchKind.cold, hasSeenColdOpen = launchState.hasSeenColdOpen, reduceMotion = reduceMotion, forced = forced, demo = demo),
        )
        launchState.recordLaunch()
        begin(mode, reduceMotion = reduceMotion, preload = preload)
        return mode
    }

    /** [ColdOpenHost]: PleadApplication passes the parsed `AWColdOpen` flag. */
    override fun launch(forced: DemoHarness.ColdOpen?, demo: Boolean, reduceMotion: Boolean) {
        launch(forced = forced?.let { Mode.valueOf(it.name) }, demo = demo, reduceMotion = reduceMotion, preload = true)
    }

    /** Scene became active again after being backgrounded: never replays; a stale playback just ends. */
    override fun resumedFromBackground() {
        if (isPlaying) complete(Completion.finished)
    }

    fun begin(mode: Mode, reduceMotion: Boolean, preload: Boolean = true) {
        this.mode = mode
        this.reduceMotion = reduceMotion
        completion = null
        if (mode == Mode.none) {
            isPlaying = false
            return
        }
        isPlaying = true
        val timeout = hardTimeout
        timeoutTask?.cancel()
        timeoutTask = scope.launch {
            delay(timeout)
            complete(Completion.timedOut)
        }
        if (preload) startPreload(ColdOpenAssets.names(mode, reduceMotion)) else framesReady = true
    }

    /** Idempotent: the first caller (timeline end, skip, or the hard timeout) wins. */
    fun complete(how: Completion = Completion.finished) {
        if (!isPlaying) return
        isPlaying = false
        completion = how
        timeoutTask?.cancel()
        timeoutTask = null
        launchState.hasSeenColdOpen = true
        // Let the crossfade (ColdOpenTimeline.crossfade) finish with the decoded frames, then free the memory.
        scope.launch {
            delay(1.seconds)
            if (isPlaying) return@launch
            preloadTask?.cancel()
            frames = emptyMap()
        }
    }

    override fun skip() = complete(Completion.skipped)

    /** Waits until the frames are decoded or [limit] passes, whichever is first. */
    suspend fun waitForFrames(limit: Duration = 300.milliseconds) {
        withTimeoutOrNull(limit) {
            while (!framesReady) delay(10)
        }
    }

    private fun startPreload(names: List<String>) {
        framesReady = false
        preloadTask?.cancel()
        preloadTask = scope.launch {
            val res = resources()
            val decoded = if (res == null) emptyMap() else withContext(decodeDispatcher) { ColdOpenAssets.decode(names, res) }
            frames = decoded
            framesReady = true
        }
    }

    companion object {
        /** The decision table (unit-tested in `ColdOpenCoordinatorTests`). */
        fun decide(i: Input): Mode {
            i.forced?.let { return it }
            if (i.launch != LaunchKind.cold || i.demo) return Mode.none
            if (!i.hasSeenColdOpen) return Mode.full
            return if (i.reduceMotion) Mode.none else Mode.sting
        }

        /** Longest the cold open can hold the app, whatever happens (preload stall, stuck timeline). */
        val defaultHardTimeout: Duration = ColdOpenTimeline.hardTimeout.seconds

        /** `AWColdOpen full|sting|none` (debug builds only; `LaunchArguments` ignores flags in release). */
        val forcedModeFromArguments: Mode?
            get() = DemoHarness.coldOpen?.let { Mode.valueOf(it.name) }
    }
}
