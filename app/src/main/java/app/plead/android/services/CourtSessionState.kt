// The pure state half of ArgueWin/Services/LiveActivityService.swift + Shared/PleadCaseActivityAttributes.swift.
//
// Android has no Live Activities (amendment az): wave 3f shows an ongoing "court in session" notification built from
// the same pushes and app state. What that notification (and anything else) reads is ported here unchanged: the
// content state and its JSON, the lock-screen-safe copy and deep links, and the lifecycle planner that decides when a
// summons / verdict session starts, updates and ends. `LiveActivityService` itself (ActivityKit calls, push-to-start
// tokens, `register_live_activity`) is not ported: the Android client never calls `register_live_activity`.
@file:Suppress("EnumEntryName")

package app.plead.android.services

import app.plead.android.models.CaseStatus
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/** Android name for the court session's content (the Live Activity's `ContentState` on iOS). */
typealias CourtSessionState = PleadCaseActivityAttributes.ContentState

/** The court session's phase (Swift `PleadCaseActivityAttributes.ContentState.Phase`). */
typealias CourtSessionPhase = PleadCaseActivityAttributes.ContentState.Phase

/**
 * The one court-session type (CONTRACTS-v2 amendment o). Payloads use `{caseId, caseNumber, kind}` and content
 * state `{phase, headline, detail, deadlineAt}`: these exact camelCase keys (the same as the APNs Live Activity
 * payloads, so the backend's pushes describe both). `deadlineAt` decodes from an ISO-8601 string, Unix seconds, or
 * Foundation reference-date seconds, so server and local payloads agree.
 */
@Serializable
data class PleadCaseActivityAttributes(
    @Serializable(with = UUIDSerializer::class) val caseId: UUID,
    val caseNumber: Int,
    val kind: Kind,
) {
    @Serializable
    enum class Kind { summons, verdict }

    @Serializable(with = ContentStateSerializer::class)
    data class ContentState(
        val phase: Phase,
        /** Lock-screen-safe headline, e.g. "You've been summoned". */
        val headline: String,
        /** Generic unless the user enabled "Show case details on Lock Screen". */
        val detail: String? = null,
        /** Plea deadline (summons) or scheduled verdict time (deliberating); drives the countdown. */
        val deadlineAt: Instant? = null,
    ) {
        enum class Phase { summoned, pleaEntered, deliberating, verdictReady, ended }
    }

    companion object {
        /** Camel-case JSON (the attributes' own keys; no snake_case conversion). */
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; explicitNulls = false }
    }
}

object ContentStateSerializer : KSerializer<PleadCaseActivityAttributes.ContentState> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    override fun serialize(encoder: Encoder, value: PleadCaseActivityAttributes.ContentState) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("ContentState needs JSON")
        json.encodeJsonElement(
            buildJsonObject {
                put("phase", value.phase.name)
                put("headline", value.headline)
                value.detail?.let { put("detail", it) }
                value.deadlineAt?.let { put("deadlineAt", SupabaseDate.format(it)) }
            },
        )
    }

    override fun deserialize(decoder: Decoder): PleadCaseActivityAttributes.ContentState {
        val json = decoder as? JsonDecoder ?: throw SerializationException("ContentState needs JSON")
        val o = json.decodeJsonElement() as? JsonObject ?: throw SerializationException("ContentState is not an object")
        val raw = (o["phase"] as? JsonPrimitive)?.content ?: throw SerializationException("phase missing")
        // An unknown future phase must never crash the notification: treat it as over.
        val phase = PleadCaseActivityAttributes.ContentState.Phase.entries.firstOrNull { it.name == raw }
            ?: PleadCaseActivityAttributes.ContentState.Phase.ended
        val headline = (o["headline"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: PleadActivityCopy.headline(phase)
        val detail = (o["detail"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return PleadCaseActivityAttributes.ContentState(phase, headline, detail, decodeDate(o["deadlineAt"] as? JsonPrimitive))
    }

    /** ISO-8601 text, or a number: Unix seconds when large (after 2017), else seconds since 2001. */
    private fun decodeDate(p: JsonPrimitive?): Instant? {
        p ?: return null
        if (p.isString) return SupabaseDate.parse(p.content)
        val n = p.doubleOrNull ?: return null
        val unix = if (n > 1_500_000_000) n else n + referenceDateOffset
        return Instant.ofEpochMilli((unix * 1000).toLong())
    }

    /** Seconds between 1970-01-01 and 2001-01-01 (Foundation's reference date). */
    private const val referenceDateOffset = 978_307_200.0
}

/** Copy and deep links shared by the court-session notification, the planner and the tests. */
object PleadActivityCopy {
    fun headline(phase: CourtSessionPhase): String = when (phase) {
        CourtSessionPhase.summoned -> "You've been summoned"
        CourtSessionPhase.deliberating -> "The court is deliberating"
        CourtSessionPhase.verdictReady -> "The judge has ruled"
        CourtSessionPhase.pleaEntered -> "Plea entered"
        CourtSessionPhase.ended -> "Court adjourned"
    }

    /** Generic (lock-screen-safe) detail line. */
    fun genericDetail(phase: CourtSessionPhase): String = when (phase) {
        CourtSessionPhase.summoned -> "The court awaits your plea"
        CourtSessionPhase.deliberating -> "The ruling is on its way"
        CourtSessionPhase.verdictReady -> "Your verdict is ready"
        CourtSessionPhase.pleaEntered -> "The court has your plea"
        CourtSessionPhase.ended -> "Court adjourned"
    }

    /** Dynamic Island compact word (Android: the notification's short status). */
    fun shortWord(phase: CourtSessionPhase): String = when (phase) {
        CourtSessionPhase.summoned -> "Summoned"
        CourtSessionPhase.deliberating -> "Deliberating"
        CourtSessionPhase.verdictReady -> "Verdict"
        CourtSessionPhase.pleaEntered -> "Plea in"
        CourtSessionPhase.ended -> "Adjourned"
    }

    /** Label next to the countdown. */
    fun countdownLabel(phase: CourtSessionPhase): String? = when (phase) {
        CourtSessionPhase.summoned -> "Plea due in"
        CourtSessionPhase.deliberating -> "Ruling in"
        else -> null
    }

    /**
     * Content state for a phase. `caseTitle` is only used when the user opted into detailed Lock
     * Screen previews; otherwise the detail is the generic line.
     */
    fun state(phase: CourtSessionPhase, deadlineAt: Instant?, caseTitle: String? = null, detailed: Boolean = false): CourtSessionState {
        val detail = if (detailed && !caseTitle.isNullOrEmpty()) caseTitle else genericDetail(phase)
        val deadline = if (phase == CourtSessionPhase.summoned || phase == CourtSessionPhase.deliberating) deadlineAt else null
        return CourtSessionState(phase = phase, headline = headline(phase), detail = detail, deadlineAt = deadline)
    }

    /** `plead://case/{id}/plea | deliberation | verdict` (amendment o deep links). */
    fun link(caseId: UUID, kind: PleadCaseActivityAttributes.Kind, phase: CourtSessionPhase): String {
        val screen = when {
            phase == CourtSessionPhase.deliberating -> "deliberation"
            phase == CourtSessionPhase.verdictReady -> "verdict"
            kind == PleadCaseActivityAttributes.Kind.summons -> "plea"
            else -> "verdict"
        }
        return "plead://case/${caseId.toString().lowercase(Locale.ROOT)}/$screen"
    }

    /** TalkBack sentence for the whole notification. */
    fun accessibilityLabel(caseNumber: Int, state: CourtSessionState, now: Instant = Instant.now()): String {
        val parts = mutableListOf("Plead", state.headline, "Case $caseNumber")
        state.detail?.let { if (it != state.headline) parts.add(it) }
        val deadline = state.deadlineAt
        val label = countdownLabel(state.phase)
        if (deadline != null && deadline.isAfter(now) && label != null) {
            val seconds = Duration.between(now, deadline).seconds
            fun unit(n: Long, one: String) = if (n == 1L) "1 $one" else "$n ${one}s"
            val text = if (seconds >= 3600) {
                val h = seconds / 3600
                val m = (seconds % 3600) / 60
                if (m == 0L) unit(h, "hour") else "${unit(h, "hour")}, ${unit(m, "minute")}"
            } else {
                unit(seconds / 60, "minute")
            }
            parts.add("$label $text")
        }
        return parts.joinToString(". ")
    }
}

// MARK: - Lifecycle planner (pure; unit-tested)

/**
 * Decides which court sessions should be started, updated or ended for the current case state
 * (CONTRACTS-v2 amendment o). The backend drives them with pushes; this is the local fallback for when the app is
 * open while a state changes, plus reconciliation on launch (wave 3f applies the actions to the ongoing notification).
 *
 * - Summons: starts when a summons awaits my plea; ends on plea (or the case leaving `summoned`), and
 *   never lives beyond 24 h.
 * - Verdict: starts when the case is deliberating / awaiting the verdict and the scheduled reading
 *   (`trial_at`) is within 1 h; switches to Verdict ready on reveal; ends when the verdict is opened or
 *   2 h after the reveal.
 */
object LiveActivityPlanner {
    const val summonsMaxAge: Long = 24 * 3600
    const val verdictLeadTime: Long = 3600
    const val verdictAfterReveal: Long = 2 * 3600

    /** Absolute cap for any Plead session. */
    const val hardMaxAge: Long = 24 * 3600

    /** What the planner needs to know about one case. */
    data class CaseInput(
        val caseId: UUID,
        val caseNumber: Int,
        val title: String,
        val status: CaseStatus,
        /** I am the defendant (summons sessions are only for the summoned party). */
        val iAmDefendant: Boolean,
        /** A settlement is pending (the summons is hidden while it is). */
        val settlementPending: Boolean = false,
        val createdAt: Instant,
        val deadlineAt: Instant? = null,
        /** The scheduled verdict reading (`trial_at`, else `verdict_at`) while deliberating. */
        val readingAt: Instant? = null,
        /** When the verdict was revealed (null until then). */
        val revealedAt: Instant? = null,
        /** The verdict is on screen right now (Court tab shows it). */
        val verdictOpened: Boolean = false,
    ) {
        companion object {
            /** Builds the planner input from a store case. */
            fun from(kase: app.plead.android.models.Case, store: CaseStore, verdictOpened: Boolean): CaseInput {
                val meId = store.me?.id
                val revealed = if (kase.isRevealed) (kase.verdictAt ?: store.verdict(kase.id)?.createdAt ?: kase.updatedAt) else null
                return CaseInput(
                    caseId = kase.id, caseNumber = kase.caseNumber, title = kase.title, status = kase.status,
                    iAmDefendant = meId != null && kase.defendantId == meId,
                    settlementPending = store.hasPendingSettlement(kase.id),
                    createdAt = kase.createdAt, deadlineAt = kase.deadlineAt,
                    readingAt = kase.scheduledReadingAt, revealedAt = revealed, verdictOpened = verdictOpened,
                )
            }
        }
    }

    /** A running session. */
    data class Running(
        val activityId: String,
        val caseId: UUID,
        val kind: PleadCaseActivityAttributes.Kind,
        val phase: CourtSessionPhase,
        val startedAt: Instant,
        /** The session went stale (the summons window, or the verdict's deadline / reveal window). */
        val isStale: Boolean = false,
    )

    enum class EndReason(val rawValue: String) {
        pleaEntered("plea_entered"), verdictOpened("verdict_opened"), expired("expired"),
        caseClosed("case_closed"), caseMissing("case_missing"), duplicate("duplicate"),
    }

    sealed class Action {
        data class start(val caseId: UUID, val caseNumber: Int, val kind: PleadCaseActivityAttributes.Kind, val state: CourtSessionState) : Action()
        data class update(val activityId: String, val state: CourtSessionState) : Action()
        data class end(val activityId: String, val state: CourtSessionState, val reason: EndReason) : Action()
    }

    fun key(caseId: UUID, kind: PleadCaseActivityAttributes.Kind): String = "${caseId.toString().lowercase()}:${kind.name}"

    /**
     * @param allowStart local starts are allowed (app in the foreground, notifications enabled).
     * @param finished `caseId:kind` keys already ended on this device (never restarted).
     * @param detailed the user opted into case details on the Lock Screen.
     */
    fun plan(
        cases: List<CaseInput>,
        running: List<Running>,
        now: Instant,
        allowStart: Boolean,
        finished: Set<String> = emptySet(),
        detailed: Boolean = false,
    ): List<Action> {
        val actions = mutableListOf<Action>()
        val byId = LinkedHashMap<UUID, CaseInput>().apply { for (c in cases) putIfAbsent(c.caseId, c) }
        val live = mutableSetOf<String>()
        // Anything ended in this pass is finished too: never end-and-restart in one go.
        val done = finished.toMutableSet()
        fun end(r: Running, state: CourtSessionState, reason: EndReason) {
            done.add(key(r.caseId, r.kind))
            actions.add(Action.end(r.activityId, state, reason))
        }
        fun ago(t: Instant) = Duration.between(t, now).seconds

        // Running sessions: end or update.
        for (r in running.sortedByDescending { it.startedAt }) {
            val k = key(r.caseId, r.kind)
            if (live.contains(k)) {
                end(r, finalState(r.phase), EndReason.duplicate); continue
            }
            val c = byId[r.caseId]
            if (c == null) {
                end(r, finalState(r.phase), EndReason.caseMissing); continue
            }
            if (r.isStale || ago(r.startedAt) > hardMaxAge) {
                end(r, finalState(r.phase), EndReason.expired); continue
            }
            when (r.kind) {
                PleadCaseActivityAttributes.Kind.summons -> {
                    if (c.status != CaseStatus.summoned) {
                        val state = PleadActivityCopy.state(
                            if (c.status.isOpen) CourtSessionPhase.pleaEntered else CourtSessionPhase.ended,
                            deadlineAt = null, caseTitle = c.title, detailed = detailed,
                        )
                        end(r, state, if (c.status.isOpen) EndReason.pleaEntered else EndReason.caseClosed)
                    } else if (ago(r.startedAt) > summonsMaxAge) {
                        end(r, finalState(r.phase), EndReason.expired)
                    } else {
                        live.add(k)
                        val want = PleadActivityCopy.state(CourtSessionPhase.summoned, c.deadlineAt, c.title, detailed)
                        if (r.phase != CourtSessionPhase.summoned) actions.add(Action.update(r.activityId, want))
                    }
                }
                PleadCaseActivityAttributes.Kind.verdict -> {
                    val revealed = c.revealedAt
                    if (revealed != null) {
                        if (c.verdictOpened) {
                            end(r, PleadActivityCopy.state(CourtSessionPhase.verdictReady, null, c.title, detailed), EndReason.verdictOpened)
                        } else if (ago(revealed) > verdictAfterReveal) {
                            end(r, finalState(CourtSessionPhase.verdictReady), EndReason.expired)
                        } else {
                            live.add(k)
                            if (r.phase != CourtSessionPhase.verdictReady) {
                                actions.add(Action.update(r.activityId, PleadActivityCopy.state(CourtSessionPhase.verdictReady, null, c.title, detailed)))
                            }
                        }
                    } else if (c.status.isDeliberating) {
                        live.add(k)
                        if (r.phase != CourtSessionPhase.deliberating) {
                            actions.add(Action.update(r.activityId, PleadActivityCopy.state(CourtSessionPhase.deliberating, c.readingAt, c.title, detailed)))
                        }
                    } else if (c.status == CaseStatus.trial) {
                        live.add(k) // pushed early by the server; keep it until deliberation
                    } else {
                        // Settled, mistrial or otherwise closed without a reveal.
                        end(r, finalState(r.phase), EndReason.caseClosed)
                    }
                }
            }
        }

        if (!allowStart) return actions

        // Local starts.
        for (c in cases) {
            if (c.status == CaseStatus.summoned && c.iAmDefendant && !c.settlementPending) {
                val k = key(c.caseId, PleadCaseActivityAttributes.Kind.summons)
                if (!live.contains(k) && !done.contains(k) && ago(c.createdAt) < summonsMaxAge) {
                    live.add(k)
                    actions.add(
                        Action.start(
                            c.caseId, c.caseNumber, PleadCaseActivityAttributes.Kind.summons,
                            PleadActivityCopy.state(CourtSessionPhase.summoned, c.deadlineAt, c.title, detailed),
                        ),
                    )
                }
            }
            val reading = c.readingAt
            if (c.status.isDeliberating && c.revealedAt == null && reading != null) {
                val k = key(c.caseId, PleadCaseActivityAttributes.Kind.verdict)
                val lead = Duration.between(now, reading).seconds
                if (!live.contains(k) && !done.contains(k) && lead <= verdictLeadTime && lead > -verdictAfterReveal) {
                    live.add(k)
                    actions.add(
                        Action.start(
                            c.caseId, c.caseNumber, PleadCaseActivityAttributes.Kind.verdict,
                            PleadActivityCopy.state(CourtSessionPhase.deliberating, reading, c.title, detailed),
                        ),
                    )
                }
            }
        }
        return actions
    }

    fun finalState(phase: CourtSessionPhase): CourtSessionState = when (phase) {
        CourtSessionPhase.summoned, CourtSessionPhase.pleaEntered -> PleadActivityCopy.state(CourtSessionPhase.pleaEntered, null)
        else -> PleadActivityCopy.state(CourtSessionPhase.ended, null)
    }

    /**
     * When a session's content goes stale: the summons window, or the deadline / reveal window for the verdict.
     */
    fun staleDate(kind: PleadCaseActivityAttributes.Kind, state: CourtSessionState, now: Instant): Instant = when (kind) {
        PleadCaseActivityAttributes.Kind.summons -> now.plusSeconds(summonsMaxAge)
        PleadCaseActivityAttributes.Kind.verdict ->
            if (state.phase == CourtSessionPhase.verdictReady) now.plusSeconds(verdictAfterReveal)
            else (state.deadlineAt ?: now).plusSeconds(verdictAfterReveal)
    }
}
