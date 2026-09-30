// Port of ArgueWin/Courtroom/CourtEntrance.swift: the shared court entrance (CONTRACTS-v2 amendment ac, motion brief
// §17). INTERFACE IS FIXED for the mock trial and the live scene: names and semantics below are shared; members may be
// added, never renamed or removed.
//
// Timeline (`CourtEntranceTiming`, ≈ 2.2 s, one cancellable job on the injected `sleep`):
//   0–250 ms       `roomRevealed` + `caseLabelVisible` (hosts lift their dim / fade the room in over 250 ms)
//   250–950 ms     plaintiff walks in from the left, defendant from the right (only if present): offset from
//                  ∓`CourtEntrancePose.offstageDistance` to zero, `walkFrame` cycling 1…4 at 8 fps, then standing
//   950–1600 ms    `audienceSettle` 0 → 1 while the judge walks to the bench from the right (behind the bench:
//                  offset `judgeApproach` → 0, fading in over the first 200 ms, walk frames), then standing
//   1600–2200 ms   judge in position; `gavelTaps += 1` at 1700 ms; `ready` + `onReady` at 2200 ms
// Reduce Motion: opacity only (occupied positions fade in over `reduceMotionFade`), no walk frames, no offsets, one
// `gavelTaps` increment, then `ready`. `finish()` snaps to final + `onReady` once; `skip()` snaps without it;
// `restoreOccupied()` is instant with no gavel; `walkIn(plaintiff:)` plays one party's 700 ms walk only.
//
// Poses are in the host's coordinate space, in dp, from fixed constants (`offstageDistance`, `judgeApproach`) so the
// live court and the mock trial move the same way. Hosts apply them with `Modifier.courtEntrancePose(_:)`.
@file:Suppress("ClassName", "EnumEntryName")

package app.plead.android.courtroom

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import app.plead.android.designsystem.PleadMotion
import app.plead.android.models.Role
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class CourtEntrancePhase { preparing, entering, ready, skipped }

/** What a host applies to one of its own layers (judge, plaintiff, defendant) during the entrance. */
data class CourtEntrancePose(
    /** Hidden entirely (a party who has not joined, or before their walk-in starts). */
    val visible: Boolean = true,
    /** Offset from the layer's final position, in dp of the host's coordinate space (Swift `CGSize` → x / y). */
    val offset: Offset = Offset.Zero,
    val opacity: Double = 1.0,
    /** 0 = standing pose; 1… = walk-cycle frames (`JudgeSprite` / `CourtAvatarSprite` `walkFrame`). */
    val walkFrame: Int = 0,
) {
    /** The rendered opacity (0 when not visible). */
    val effectiveOpacity: Double get() = if (visible) opacity else 0.0
    val isStanding: Boolean get() = this == standing

    companion object {
        val standing = CourtEntrancePose()
        val hidden = CourtEntrancePose(visible = false, opacity = 0.0)

        /**
         * Where a party starts its walk: this far off its final x, outward (left for the plaintiff, right for the
         * defendant). 120 pt puts a podium avatar just past the screen edge on every phone width, so the walk is on
         * screen for most of its 700 ms instead of crossing empty space.
         */
        const val offstageDistance: Float = 120f

        /** Where the judge starts: this far to the right of the chair, walking behind the bench. */
        const val judgeApproach: Float = 64f

        /** Walk-cycle frames (1 step left, 2 passing, 3 step right, 4 passing). */
        const val walkFrames = 4

        fun offstage(role: Role): Offset = Offset(if (role == Role.plaintiff) -offstageDistance else offstageDistance, 0f)
    }
}

/** The entrance's clock (seconds from `start()`). */
object CourtEntranceTiming {
    const val reveal: Double = 0.25
    const val partiesStart: Double = 0.25
    const val partiesEnd: Double = 0.95
    const val judgeStart: Double = 0.95
    const val judgeEnd: Double = 1.6
    const val judgeFadeIn: Double = 0.2
    const val gavel: Double = 1.7
    const val ready: Double = 2.2

    /** Walk-cycle frame length (8 fps); hosts animate offsets linearly over one frame. */
    const val frame: Double = 0.125

    /** A late arrival's walk. */
    const val walkIn: Double = 0.7

    /** Reduce Motion: occupied positions fade in over this long. */
    const val reduceMotionFade: Double = 0.25
}

/** Runs the entrance on one cancellable job with an injected clock; hosts read the poses and phase. */
class CourtEntranceDirector(
    val hasPlaintiff: Boolean = true,
    val hasDefendant: Boolean = true,
    val reduceMotion: Boolean = false,
    private val sleep: suspend (Double) -> Unit = { delay((it * 1000).toLong()) },
    private val scope: CoroutineScope = MainScope(),
) {
    var phase: CourtEntrancePhase by mutableStateOf(CourtEntrancePhase.preparing)
        private set

    /** 0–250 ms: the room reveal (hosts fade the painting in / lift a dim). */
    var roomRevealed: Boolean by mutableStateOf(false)
        private set
    var caseLabelVisible: Boolean by mutableStateOf(false)
        private set
    var judge: CourtEntrancePose by mutableStateOf(CourtEntrancePose.hidden)
        private set
    var plaintiff: CourtEntrancePose by mutableStateOf(CourtEntrancePose.hidden)
        private set
    var defendant: CourtEntrancePose by mutableStateOf(CourtEntrancePose.hidden)
        private set

    /** 0 → 1 while the audience clusters settle. */
    var audienceSettle: Double by mutableStateOf(0.0)
        private set

    /** Increments once when the judge taps the gavel at the end (hosts play their gavel on change). */
    var gavelTaps: Int by mutableStateOf(0)
        private set

    /** Called once when the court is ready (after the tap, after `finish()`, or immediately under Reduce Motion / restore). */
    var onReady: (() -> Unit)? = null

    /** Who is in the room now (starts as `hasPlaintiff` / `hasDefendant`; a `walkIn` adds that party). */
    var plaintiffPresent: Boolean by mutableStateOf(hasPlaintiff)
        private set
    var defendantPresent: Boolean by mutableStateOf(hasDefendant)
        private set

    /** Test / debug hook: called after each keyframe with the seconds since `start()` (or since a `walkIn`). */
    var onKeyframe: ((Double) -> Unit)? = null

    private var task: Job? = null
    private val walkTasks: MutableMap<Role, Job> = mutableMapOf()
    private var generation = 0

    fun isPresent(r: Role): Boolean = if (r == Role.plaintiff) plaintiffPresent else defendantPresent
    fun pose(r: Role): CourtEntrancePose = if (r == Role.plaintiff) plaintiff else defendant

    /** True while the sequence (or a walk-in) is still moving. */
    val isRunning: Boolean get() = task != null || walkTasks.isNotEmpty()

    // MARK: Sequence

    /** Plays the full sequence (or the Reduce Motion fade). */
    fun start() {
        cancelTask()
        phase = CourtEntrancePhase.entering
        roomRevealed = false
        caseLabelVisible = false
        audienceSettle = 0.0
        val frames = if (reduceMotion) reduceMotionKeyframes() else keyframes(plaintiffPresent, defendantPresent)
        // Starting poses (before the first keyframe): off stage, hidden.
        judge = if (reduceMotion) CourtEntrancePose(opacity = 0.0) else judgeStart
        plaintiff = when {
            !plaintiffPresent -> CourtEntrancePose.hidden
            reduceMotion -> CourtEntrancePose(opacity = 0.0)
            else -> waiting(Role.plaintiff)
        }
        defendant = when {
            !defendantPresent -> CourtEntrancePose.hidden
            reduceMotion -> CourtEntrancePose(opacity = 0.0)
            else -> waiting(Role.defendant)
        }
        generation += 1
        val mine = generation
        task = scope.launch { run(frames, mine) }
    }

    /** A tap: everything to its final position now. */
    fun finish() {
        cancelAll()
        occupy()
        if (phase != CourtEntrancePhase.ready) {
            phase = CourtEntrancePhase.ready; onReady?.invoke()
        }
    }

    /** Skip demo / leave: stop without calling `onReady`. */
    fun skip() {
        cancelAll(); occupy(); phase = CourtEntrancePhase.skipped
    }

    /** Returning to an in-progress case: the occupied court, no sequence, no gavel. */
    fun restoreOccupied() {
        cancelAll(); occupy()
        if (phase != CourtEntrancePhase.ready) {
            phase = CourtEntrancePhase.ready; onReady?.invoke()
        }
    }

    /**
     * A late arrival walks to their podium (700 ms) without replaying the sequence or touching anything else. Ignored
     * when that party is already in the room.
     */
    fun walkIn(plaintiff: Boolean) {
        val role = if (plaintiff) Role.plaintiff else Role.defendant
        if (isPresent(role)) return
        if (plaintiff) plaintiffPresent = true else defendantPresent = true
        walkTasks[role]?.cancel()
        if (reduceMotion) {
            set(role, CourtEntrancePose(opacity = 0.0))
            walkTasks[role] = scope.launch {
                try {
                    sleep(0.0)
                } catch (e: Exception) {
                    return@launch
                }
                set(role, CourtEntrancePose.standing)
                onKeyframe?.invoke(0.0)
                walkTasks.remove(role)
            }
            return
        }
        set(role, waiting(role))
        val frames = walkKeyframes(role, 0.0)
        walkTasks[role] = scope.launch {
            var elapsed = 0.0
            for (f in frames) {
                if (!currentCoroutineContext().isActive) return@launch
                try {
                    sleep(max(0.0, f.at - elapsed))
                } catch (e: Exception) {
                    return@launch
                }
                if (!currentCoroutineContext().isActive) return@launch
                elapsed = f.at
                val c = f.change
                if (c is Change.pose) set(role, c.pose)
                onKeyframe?.invoke(elapsed)
            }
            walkTasks.remove(role)
        }
    }

    fun stop() {
        cancelAll()
    }

    /** Stills and tests: the sequence frozen `t` seconds in (no job, no `onReady` unless `t` reaches the end). */
    fun seek(t: Double) {
        start()
        cancelTask()
        val frames = if (reduceMotion) reduceMotionKeyframes() else keyframes(plaintiffPresent, defendantPresent)
        for (f in frames) if (f.at <= t + 0.0001) apply(f.change)
    }

    /** Awaits the running sequence (tests). */
    suspend fun join() {
        task?.join()
        for (t in walkTasks.values.toList()) t.join()
    }

    // MARK: Keyframes

    data class Keyframe(val at: Double, val change: Change)

    sealed class Change {
        data object reveal : Change()
        data class pose(val actor: CourtEntranceActor, val pose: CourtEntrancePose) : Change()
        data class audience(val value: Double) : Change()
        data object gavel : Change()
        data object ready : Change()
    }

    enum class CourtEntranceActor { judge, plaintiff, defendant }

    /** Reduce Motion: reveal, one fade to the occupied court, one gavel tap, ready. */
    private fun reduceMotionKeyframes(): List<Keyframe> {
        val out = mutableListOf(
            Keyframe(0.0, Change.reveal), Keyframe(0.0, Change.audience(1.0)),
            Keyframe(0.0, Change.pose(CourtEntranceActor.judge, CourtEntrancePose.standing)),
        )
        if (plaintiffPresent) out.add(Keyframe(0.0, Change.pose(CourtEntranceActor.plaintiff, CourtEntrancePose.standing)))
        if (defendantPresent) out.add(Keyframe(0.0, Change.pose(CourtEntranceActor.defendant, CourtEntrancePose.standing)))
        out.add(Keyframe(CourtEntranceTiming.reduceMotionFade, Change.gavel))
        out.add(Keyframe(CourtEntranceTiming.reduceMotionFade, Change.ready))
        return out
    }

    // MARK: Running

    private suspend fun run(frames: List<Keyframe>, mine: Int) {
        var elapsed = 0.0
        var i = 0
        while (i < frames.size) {
            if (!currentCoroutineContext().isActive || generation != mine) return
            val at = frames[i].at
            try {
                sleep(max(0.0, at - elapsed))
            } catch (e: Exception) {
                return
            }
            if (!currentCoroutineContext().isActive || generation != mine) return
            elapsed = at
            // Everything due at this instant lands in one update.
            while (i < frames.size && frames[i].at <= at + 0.0001) {
                apply(frames[i].change)
                i += 1
            }
            onKeyframe?.invoke(elapsed)
        }
        if (generation == mine) task = null
    }

    private fun apply(c: Change) {
        when (c) {
            Change.reveal -> {
                roomRevealed = true; caseLabelVisible = true
            }
            is Change.pose -> when (c.actor) {
                CourtEntranceActor.judge -> judge = c.pose
                CourtEntranceActor.plaintiff -> if (plaintiffPresent) plaintiff = c.pose
                CourtEntranceActor.defendant -> if (defendantPresent) defendant = c.pose
            }
            is Change.audience -> audienceSettle = c.value
            Change.gavel -> gavelTaps += 1
            Change.ready -> {
                task = null
                if (phase != CourtEntrancePhase.ready) {
                    phase = CourtEntrancePhase.ready; onReady?.invoke()
                }
            }
        }
    }

    private fun set(r: Role, p: CourtEntrancePose) {
        if (r == Role.plaintiff) plaintiff = p else defendant = p
    }

    private fun cancelTask() {
        task?.cancel(); task = null
        generation += 1
    }

    private fun cancelAll() {
        cancelTask()
        for (t in walkTasks.values) t.cancel()
        walkTasks.clear()
    }

    private fun occupy() {
        roomRevealed = true; caseLabelVisible = true; audienceSettle = 1.0
        judge = CourtEntrancePose.standing
        plaintiff = if (plaintiffPresent) CourtEntrancePose.standing else CourtEntrancePose.hidden
        defendant = if (defendantPresent) CourtEntrancePose.standing else CourtEntrancePose.hidden
    }

    companion object {
        const val totalDuration: Double = 2.2

        /** A present party before its walk starts: hidden, parked off stage (so the walk starts from there). */
        fun waiting(r: Role): CourtEntrancePose =
            CourtEntrancePose(visible = false, offset = CourtEntrancePose.offstage(r), opacity = 0.0)

        val judgeStart = CourtEntrancePose(visible = false, offset = Offset(CourtEntrancePose.judgeApproach, 0f), opacity = 0.0)

        /** The full (motion) timeline. */
        fun keyframes(plaintiff: Boolean, defendant: Boolean): List<Keyframe> {
            val T = CourtEntranceTiming
            val out = mutableListOf(Keyframe(0.0, Change.reveal))
            if (plaintiff) out += walkKeyframes(Role.plaintiff, T.partiesStart)
            if (defendant) out += walkKeyframes(Role.defendant, T.partiesStart)
            // Audience + judge, 950–1600 ms.
            val span = T.judgeEnd - T.judgeStart
            val steps = ceil(span / T.frame).toInt()
            for (i in 0..steps) {
                val at = min(T.judgeStart + i.toDouble() * T.frame, T.judgeEnd)
                val p = min(1.0, (at - T.judgeStart) / span)
                out.add(Keyframe(at, Change.audience(p)))
                if (p >= 1) {
                    out.add(Keyframe(at, Change.pose(CourtEntranceActor.judge, CourtEntrancePose.standing)))
                } else {
                    val pose = CourtEntrancePose(
                        visible = true,
                        offset = Offset((CourtEntrancePose.judgeApproach * (1 - p)).toFloat(), 0f),
                        opacity = min(1.0, (at - T.judgeStart) / T.judgeFadeIn),
                        walkFrame = 1 + i % CourtEntrancePose.walkFrames,
                    )
                    out.add(Keyframe(at, Change.pose(CourtEntranceActor.judge, pose)))
                }
            }
            out.add(Keyframe(T.gavel, Change.gavel))
            out.add(Keyframe(T.ready, Change.ready))
            return out.withIndex().sortedWith(compareBy({ it.value.at }, { it.index })).map { it.value }
        }

        /** One party's 700 ms walk from off stage to its podium, starting at `from`. */
        fun walkKeyframes(r: Role, from: Double): List<Keyframe> {
            val T = CourtEntranceTiming
            val actor = if (r == Role.plaintiff) CourtEntranceActor.plaintiff else CourtEntranceActor.defendant
            val off = CourtEntrancePose.offstage(r)
            val steps = ceil(T.walkIn / T.frame).toInt()
            val out = mutableListOf<Keyframe>()
            for (i in 0..steps) {
                val at = min(from + i.toDouble() * T.frame, from + T.walkIn)
                val p = min(1.0, (at - from) / T.walkIn)
                val pose = if (p >= 1) {
                    CourtEntrancePose.standing
                } else {
                    CourtEntrancePose(visible = true, offset = Offset((off.x * (1 - p)).toFloat(), 0f), opacity = 1.0,
                        walkFrame = 1 + i % CourtEntrancePose.walkFrames)
                }
                out.add(Keyframe(at, Change.pose(actor, pose)))
            }
            return out
        }
    }
}

// MARK: - Host helper

/**
 * Applies an entrance pose to one of the host's layers: hidden / opacity, the offset (animated linearly over one walk
 * frame so the 8 fps keyframes glide), nothing when `pose` is null. The walk frame itself is passed to the sprite
 * (`walkFrame =`).
 */
@Composable
fun Modifier.courtEntrancePose(pose: CourtEntrancePose?, reduceMotion: Boolean = false): Modifier {
    val p = pose ?: CourtEntrancePose.standing
    val target = if (reduceMotion) Offset.Zero else p.offset
    // Parking a hidden layer off stage is never animated (no ghost sliding outwards).
    val offsetSpec = if (reduceMotion || !p.visible) snap<Float>() else tween((CourtEntranceTiming.frame * 1000).toInt(), easing = LinearEasing)
    val ox by animateFloatAsState(target.x, offsetSpec, label = "entranceX")
    val oy by animateFloatAsState(target.y, offsetSpec, label = "entranceY")
    val fade = if (reduceMotion) CourtEntranceTiming.reduceMotionFade else 0.15
    val alpha by animateFloatAsState(
        p.effectiveOpacity.toFloat(),
        tween((fade * 1000).toInt(), easing = PleadMotion.easeOut),
        label = "entranceAlpha",
    )
    return this
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) {
                placeable.place(ox.dp.toPx().roundToInt(), oy.dp.toPx().roundToInt())
            }
        }
        .alpha(alpha)
}
