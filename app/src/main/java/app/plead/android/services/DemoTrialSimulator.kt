// Port of ArgueWin/Services/DemoTrialSimulator.swift: the local trial simulator for the DEBUG demo mode (`AWDemo YES`,
// no backend). `CaseStore` only creates one in debug builds (`BuildConfig.DEMO_HARNESS`), so release builds never run it.
//
// CaseStore routes every case transition here when `backend == null`, so the demo docket can be
// walked end to end on-device: file a case → the "partner" pleads, defends and proposes a time →
// accept → a full trial with objection windows, judge lines and cross-examination → deliberation
// (panel_progress 1…4) → awaiting_verdict → verdict with a generated panel verdict + juror reviews.
//
// The phase rules mirror `supabase/functions/_shared/trial.ts` (the backend is authoritative):
//   - exhibit phases: the presenter shows one exhibit per turn; the observer then owns the turn for
//     one object-or-pass window; the presenter rests (turn without exhibit) or, when nothing is left
//     after a window, an automatic rest turn is written. Empty exhibit phases are skipped.
//   - cross-examination: judge asks plaintiff → plaintiff answers → judge asks defendant → answers.
//   - openings / closings: one turn each. After the second closing the case goes to `deliberating`.
// Judge calls run after the step with the floor held by the judge (phase_turn_owner = null), in
// courtroom order: objection rulings, the automatic rest, then the next phase's line.
//
// Everything runs on the main thread; simulated actions are cancellable coroutines.
// Launch flags: `AWDemoSpeed fast` (0.3 s delays), `AWAutoplay YES` (also plays MY turns).
@file:Suppress("EnumEntryName")

package app.plead.android.services

import android.util.Log
import app.plead.android.app.DemoHarness
import app.plead.android.app.LaunchArguments
import app.plead.android.app.PleadApplication
import app.plead.android.models.Case
import app.plead.android.models.CaseStatus
import app.plead.android.models.EdgeError
import app.plead.android.models.Exhibit
import app.plead.android.models.ExhibitLabel
import app.plead.android.models.ExhibitType
import app.plead.android.models.AICall
import app.plead.android.models.JSONValue
import app.plead.android.models.Judgement
import app.plead.android.models.JudgementOption
import app.plead.android.models.JudgementOptionSet
import app.plead.android.models.JudgementOptionType
import app.plead.android.models.JudgementStatus
import app.plead.android.models.JurorReview
import app.plead.android.models.JurorRole
import app.plead.android.models.ObjectionReason
import app.plead.android.models.ObjectionRuling
import app.plead.android.models.Plea
import app.plead.android.models.Role
import app.plead.android.models.Settlement
import app.plead.android.models.SettlementOffer
import app.plead.android.models.SettlementSource
import app.plead.android.models.SettlementStatus
import app.plead.android.models.SettlementSuggestion
import app.plead.android.models.SettlementSuggestionKind
import app.plead.android.models.Speaker
import app.plead.android.models.TrialPhase
import app.plead.android.models.Turn
import app.plead.android.models.Verdict
import app.plead.android.models.VerdictFinding
import app.plead.android.models.VerdictKind
import java.io.File
import java.lang.ref.WeakReference
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import app.plead.android.courtroom.CourtroomLogic
import app.plead.android.features.judgement.judgementPendingSentence

class DemoTrialSimulator(store: CaseStore, var config: Config = Config.fromDefaults()) {

    enum class Speed { normal, fast, instant }

    data class Config(
        val speed: Speed = Speed.normal,
        /** Also play the signed-in user's turns (unattended demo / screenshots). */
        val autoplay: Boolean = false,
        /** Uniform 0..<1. Injected so tests can force outcomes. */
        val random: () -> Double = { Math.random() },
    ) {
        companion object {
            fun fromDefaults(): Config = Config(
                speed = when (DemoHarness.demoSpeed) {
                    "fast" -> Speed.fast
                    "instant" -> Speed.instant
                    else -> Speed.normal
                },
                autoplay = LaunchArguments.bool("AWAutoplay"),
            )
        }
    }

    /** Judge calls a step asks for, run in order after the step is applied. */
    sealed class JudgeCall {
        data class phaseLine(val phase: TrialPhase) : JudgeCall()
        data class crossExamine(val side: Role) : JudgeCall()
        data class objectionRuling(val exhibitId: UUID) : JudgeCall()
    }

    /** Where the trial goes next (mirror of `TrialStep` in trial.ts). */
    data class Step(
        val status: CaseStatus,
        val phase: TrialPhase?,
        val owner: Role?,
        val judgeCalls: List<JudgeCall> = emptyList(),
        val autoRest: Role? = null,
    )

    private val storeRef = WeakReference(store)
    private val store: CaseStore? get() = storeRef.get()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val tasks = LinkedHashMap<UUID, Job>()

    /** Cases with a simulated party action already queued (avoids double-acting). */
    private val queuedActors = mutableSetOf<UUID>()
    private var lastStamp: Instant = Instant.MIN

    // MARK: Scheduling

    val isIdle: Boolean get() = tasks.isEmpty()

    fun cancelAll() {
        tasks.values.forEach { it.cancel() }
        tasks.clear()
        queuedActors.clear()
    }

    /**
     * Resume the simulation for every live case (launch / relaunch): cancels anything pending, then
     * queues whoever the simulator should play next.
     */
    fun resume() {
        cancelAll()
        val store = store ?: return
        for (c in store.cases) if (c.status == CaseStatus.trial) queueActor(c.id)
        resumeJudgements()
        resumeSettlements()
        log("resume: ${store.cases.count { it.status == CaseStatus.trial }} trial case(s), speed ${config.speed}, autoplay ${config.autoplay}")
    }

    /** Waits until no simulated work is pending (tests). Returns false on timeout. */
    suspend fun settle(timeout: kotlin.time.Duration = kotlin.time.Duration.parse("10s")): Boolean {
        var waited = 0L
        while (tasks.isNotEmpty()) {
            if (waited > timeout.inWholeMilliseconds) return false
            kotlinx.coroutines.delay(2)
            waited += 2
        }
        return true
    }

    /** Seconds to wait for a nominal range, scaled by speed. */
    fun delay(nominal: ClosedFloatingPointRange<Double>): Double = when (config.speed) {
        Speed.normal -> nominal.start + (nominal.endInclusive - nominal.start) * config.random()
        Speed.fast -> 0.3
        Speed.instant -> 0.0
    }

    private fun schedule(nominal: ClosedFloatingPointRange<Double>, work: (DemoTrialSimulator) -> Unit) = schedule(delay(nominal), work)

    private fun schedule(seconds: Double, work: (DemoTrialSimulator) -> Unit) {
        val id = UUID.randomUUID()
        // Registered before it starts, so work that completes without suspending still leaves the map.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            if (seconds > 0) kotlinx.coroutines.delay((seconds * 1000).toLong()) else yield()
            if (!isActive) return@launch
            tasks.remove(id)
            work(this@DemoTrialSimulator)
        }
        tasks[id] = job
        job.start()
    }

    // MARK: Public entry points (CaseStore demo hooks)

    fun submitTurn(caseId: UUID, body: String, exhibitId: UUID?) {
        submit(caseId, myRole(caseId), body, exhibitId)
    }

    fun raiseObjection(caseId: UUID, exhibitId: UUID, reason: ObjectionReason?) {
        `object`(caseId, myRole(caseId), exhibitId, reason)
    }

    fun fileCase(title: String, charge: String, remedy: String, drafts: List<DraftExhibit>): Case {
        val store = store
        val me = store?.me
        val partner = store?.partner
        if (store == null || me == null || partner == null) {
            throw EdgeError(code = "not_in_couple", message = "Link with your partner before filing a case.")
        }
        val now = stamp()
        val number = (store.cases.maxOfOrNull { it.caseNumber } ?: 0) + 1
        val kase = Case(
            id = UUID.randomUUID(), coupleId = store.couple?.id ?: me.coupleId ?: UUID.randomUUID(), caseNumber = number, title = title,
            plaintiffId = me.id, defendantId = partner.id, status = CaseStatus.summoned, phaseTurnOwner = Role.defendant,
            charge = charge, remedyRequested = remedy, deadlineAt = now.plusSeconds(72 * 3600),
            createdAt = now, updatedAt = now,
        )
        store.demoUpsertCase(kase)
        addExhibits(drafts, kase.id, me.id)
        log("file_case #$number → summoned")
        // The partner reads the summons and pleads not guilty…
        schedule(2.5..3.5) { sim ->
            val c = sim.store?.caseById(kase.id) ?: return@schedule
            if (c.status != CaseStatus.summoned || c.settlementId != null) return@schedule
            val t = sim.stamp()
            sim.store?.demoUpsertCase(
                c.copy(plea = Plea.notGuilty, status = CaseStatus.defence, phaseTurnOwner = Role.defendant, deadlineAt = t.plusSeconds(48 * 3600), updatedAt = t),
            )
            sim.log("partner pleads not guilty → defence")
            // …then files a defence with one exhibit and proposes a time.
            sim.schedule(2.5..3.5) { it.partnerFilesDefence(kase.id) }
        }
        return kase
    }

    fun enterPlea(plea: Plea, caseId: UUID) {
        val store = store ?: throw wrongState()
        var c = store.caseById(caseId) ?: throw wrongState()
        if (c.status != CaseStatus.summoned) throw wrongState()
        val me = store.me
        if (me == null || c.defendantId != me.id) throw EdgeError(code = "wrong_role", message = "Only the defendant can enter a plea.")
        val now = stamp()
        c = c.copy(plea = plea, updatedAt = now)
        if (plea == Plea.guilty) {
            c = c.copy(status = CaseStatus.closedGuilty, phaseTurnOwner = null, deadlineAt = null, closedAt = now, verdictAt = now)
            store.demoAddVerdict(
                Verdict(
                    id = UUID.randomUUID(), caseId = c.id, kind = VerdictKind.guilty, winnerId = c.plaintiffId, isTie = false,
                    recap = "The defendant pleaded guilty.", findings = emptyList(), sentence = judgementPendingSentence,
                    closingLine = "Honesty is noted. The remedy is expected.", createdAt = now,
                ),
                reviews = emptyList(),
            )
        } else {
            c = c.copy(status = CaseStatus.defence, phaseTurnOwner = Role.defendant, deadlineAt = now.plusSeconds(48 * 3600))
        }
        store.demoUpsertCase(c)
        if (plea == Plea.guilty) store.verdict(c.id)?.let { openJudgement(c.id, it) }
        log("enter_plea ${plea.rawValue} → ${c.status.rawValue}")
    }

    fun fileDefence(caseId: UUID, statement: String, counterClaim: String?, drafts: List<DraftExhibit>, proposedTrialAt: Instant) {
        val store = store ?: throw wrongState()
        val c = store.caseById(caseId)
        if (c == null || c.status != CaseStatus.defence) throw wrongState()
        val me = store.me
        if (me == null || c.defendantId != me.id) throw EdgeError(code = "wrong_role", message = "Only the defendant files a defence.")
        val now = stamp()
        store.demoUpsertCase(
            c.copy(
                defenceStatement = statement, counterClaim = counterClaim, proposedTrialAt = proposedTrialAt,
                status = CaseStatus.scheduling, phaseTurnOwner = Role.plaintiff, deadlineAt = now.plusSeconds(24 * 3600), updatedAt = now,
            ),
        )
        addExhibits(drafts, caseId, me.id)
        log("file_defence → scheduling; partner will accept")
        schedule(2.5..3.5) { sim ->
            val fresh = sim.store?.caseById(caseId) ?: return@schedule
            if (fresh.status != CaseStatus.scheduling) return@schedule
            sim.startTrial(caseId)
        }
    }

    fun proposeTime(date: Instant, caseId: UUID) {
        val store = store ?: throw wrongState()
        val c = store.caseById(caseId)
        if (c == null || c.status != CaseStatus.scheduling) throw wrongState()
        if (c.proposalCount != 0) throw EdgeError(code = "already_proposed", message = "You've already proposed another time.")
        val now = stamp()
        store.demoUpsertCase(
            c.copy(
                proposedTrialAt = date, proposalCount = c.proposalCount + 1,
                phaseTurnOwner = c.role(store.me?.id ?: UUID.randomUUID())?.other, updatedAt = now,
            ),
        )
        log("propose_time → partner will accept")
        schedule(2.5..3.5) { sim ->
            val fresh = sim.store?.caseById(caseId) ?: return@schedule
            if (fresh.status != CaseStatus.scheduling) return@schedule
            sim.startTrial(caseId)
        }
    }

    fun acceptTime(caseId: UUID) {
        val c = store?.caseById(caseId)
        if (c == null || c.status != CaseStatus.scheduling) throw wrongState()
        startTrial(caseId)
    }

    // MARK: Pre-trial helpers

    private fun addExhibits(drafts: List<DraftExhibit>, caseId: UUID, owner: UUID) {
        val store = store ?: return
        val now = stamp()
        for ((i, d) in drafts.withIndex()) {
            val id = UUID.randomUUID()
            var path: String? = null
            val data = d.imageData
            if (data != null) {
                val dir = PleadApplication.contextOrNull?.cacheDir ?: File(System.getProperty("java.io.tmpdir") ?: ".")
                val file = File(dir, "demo-exhibit-${id.uuidString}.jpg")
                if (runCatching { file.writeBytes(data) }.isSuccess) {
                    store.demoSetExhibitURL(file.toURI(), id)
                    path = "demo/${id.uuidString}"
                }
            }
            store.demoUpsertExhibit(
                Exhibit(
                    id = id, caseId = caseId, ownerId = owner, label = drafts.label(i), type = d.type,
                    storagePath = path, caption = d.caption, body = d.body,
                    occurredAt = if (DraftExhibit.supportsDate(d.type)) d.occurredAt else null,
                    sort = i, createdAt = now,
                ),
            )
        }
    }

    private fun partnerFilesDefence(caseId: UUID) {
        val store = store ?: return
        val c = store.caseById(caseId) ?: return
        if (c.status != CaseStatus.defence) return
        val now = stamp()
        store.demoUpsertCase(
            c.copy(
                defenceStatement = pick(Lines.defenceStatements), proposedTrialAt = now.plusSeconds(3 * 3600),
                status = CaseStatus.scheduling, phaseTurnOwner = Role.plaintiff, deadlineAt = now.plusSeconds(24 * 3600), updatedAt = now,
            ),
        )
        val ex = pick(Lines.defenceExhibits)
        store.demoUpsertExhibit(
            Exhibit(
                id = UUID.randomUUID(), caseId = caseId, ownerId = c.defendantId, label = ExhibitLabel.A, type = ex.type,
                caption = ex.caption, body = ex.body, occurredAt = if (ex.type == ExhibitType.receipt) now.minusSeconds(86_400) else null,
                sort = 0, createdAt = now,
            ),
        )
        log("partner files defence + proposes a time → scheduling")
    }

    private fun startTrial(caseId: UUID) {
        val store = store ?: return
        val c0 = store.caseById(caseId) ?: return
        if (c0.status != CaseStatus.scheduling) return
        val c = c0.copy(status = CaseStatus.trial, trialAt = c0.proposedTrialAt ?: stamp().plusSeconds(3 * 3600))
        store.demoUpsertCase(c)
        val step = enterPhase(c, store.exhibits(caseId), TrialPhase.plaintiffOpening)
        log("accept_time → trial")
        commit(step, caseId, from = null)
    }

    // MARK: Trial actions

    private fun myRole(caseId: UUID): Role {
        val store = store ?: throw wrongState()
        val c = store.caseById(caseId) ?: throw wrongState()
        val me = store.me
        val role = me?.let { c.role(it.id) }
        if (role == null) throw EdgeError(code = "wrong_role", message = "You're not a party to this case.")
        return role
    }

    /** submit_turn for `role`. */
    fun submit(caseId: UUID, role: Role, body: String, exhibitId: UUID?) {
        val store = store ?: throw wrongState()
        val c = store.caseById(caseId)
        val phase = c?.phase
        if (c == null || c.status != CaseStatus.trial || phase == null) throw wrongState()
        if (c.phaseTurnOwner != role) throw EdgeError(code = "wrong_role", message = "It isn't your turn.")
        val meta = mutableMapOf<String, JSONValue>()
        var rest = false
        var exhibitRef: UUID? = null
        val presenter = presenter(phase)
        if (presenter != null) {
            if (role != presenter) throw wrongState("Object or pass on the exhibit before the court.")
            if (exhibitId != null) {
                val ex = store.exhibits.firstOrNull { it.id == exhibitId }
                if (ex == null || ex.caseId != caseId || ex.ownerId != c.userId(role) || ex.presentedAt != null) {
                    throw wrongState("That exhibit can't be presented.")
                }
                store.demoUpsertExhibit(ex.copy(presentedAt = stamp()))
                exhibitRef = exhibitId
            } else {
                rest = true
                meta["rest"] = JSONValue.Bool(true)
            }
        }
        val text = body.trim()
        appendTurn(
            caseId, phase, speaker(role),
            body = if (text.isEmpty()) (if (rest) restLine(role) else "…") else text,
            exhibitId = exhibitRef, meta = meta,
        )
        log("${role.rawValue} ${if (rest) "rests" else if (exhibitRef != null) "presents an exhibit" else "speaks"} in ${phase.rawValue}")
        val fresh = store.caseById(caseId) ?: c
        commit(nextAfterTurn(fresh, store.turns(caseId), store.exhibits(caseId), rest), caseId, from = phase)
    }

    /** raise_objection for `role` (null reason = pass). */
    fun `object`(caseId: UUID, role: Role, exhibitId: UUID, reason: ObjectionReason?) {
        val store = store ?: throw wrongState("There is no exhibit open for objection.")
        val c = store.caseById(caseId)
        val phase = c?.phase
        val presenter = presenter(phase)
        if (c == null || c.status != CaseStatus.trial || phase == null || presenter == null || c.phaseTurnOwner != role || role != presenter.other) {
            throw wrongState("There is no exhibit open for objection.")
        }
        val exhibits = store.exhibits(caseId)
        val reviewed = exhibitUnderReview(c, store.turns(caseId), exhibits)
        if (reviewed == null || reviewed.id != exhibitId) throw wrongState("That exhibit is not the one before the court.")
        if (reason != null) {
            store.demoUpsertExhibit(reviewed.copy(objectionReason = reason))
            appendTurn(
                caseId, phase, speaker(role), body = "Objection! ${reason.title}.", exhibitId = exhibitId,
                meta = mapOf("objection" to JSONValue.Obj(mapOf("reason" to JSONValue.Str(reason.rawValue)))),
            )
        } else {
            appendTurn(caseId, phase, speaker(role), body = "No objection.", exhibitId = exhibitId, meta = mapOf("pass" to JSONValue.Bool(true)))
        }
        log("${role.rawValue} ${reason?.let { "objects (${it.rawValue})" } ?: "passes"} on ${reviewed.displayName}")
        commit(nextAfterTurn(c, store.turns(caseId), store.exhibits(caseId)), caseId, from = phase)
    }

    // MARK: Phase rules (mirror of trial.ts)

    fun enterPhase(c: Case, exhibits: List<Exhibit>, phase: TrialPhase?, prefix: List<JudgeCall> = emptyList()): Step {
        if (phase == null) return Step(CaseStatus.deliberating, null, null, prefix)
        val presenter = presenter(phase)
        if (presenter != null) {
            if (unpresented(c, exhibits, presenter).isEmpty()) return enterPhase(c, exhibits, phase.next, prefix)
            return Step(CaseStatus.trial, phase, presenter, prefix + JudgeCall.phaseLine(phase))
        }
        if (phase == TrialPhase.crossExamination) {
            return Step(CaseStatus.trial, phase, Role.plaintiff, prefix + JudgeCall.crossExamine(Role.plaintiff))
        }
        return Step(CaseStatus.trial, phase, phase.speakingSide, prefix + JudgeCall.phaseLine(phase))
    }

    /** Where the trial goes after the current owner acted. `turns`/`exhibits` include the action. */
    fun nextAfterTurn(c: Case, turns: List<Turn>, exhibits: List<Exhibit>, rest: Boolean = false): Step {
        val phase = c.phase
        val actor = c.phaseTurnOwner
        if (c.status != CaseStatus.trial || phase == null || actor == null) return Step(c.status, c.phase, c.phaseTurnOwner)
        val presenter = presenter(phase)
        if (presenter != null) {
            if (actor == presenter) {
                if (rest) return enterPhase(c, exhibits, phase.next)
                return Step(CaseStatus.trial, phase, presenter.other)
            }
            val prefix = mutableListOf<JudgeCall>()
            val reviewed = exhibitUnderReview(c, turns, exhibits)
            if (reviewed != null && reviewed.objectionReason != null && reviewed.objectionRuling == null) {
                prefix.add(JudgeCall.objectionRuling(reviewed.id))
            }
            if (unpresented(c, exhibits, presenter).isNotEmpty()) return Step(CaseStatus.trial, phase, presenter, prefix)
            return enterPhase(c, exhibits, phase.next, prefix).copy(autoRest = presenter)
        }
        if (phase == TrialPhase.crossExamination) {
            if (actor == Role.plaintiff) return Step(CaseStatus.trial, phase, Role.defendant, listOf(JudgeCall.crossExamine(Role.defendant)))
            return enterPhase(c, exhibits, phase.next)
        }
        return enterPhase(c, exhibits, phase.next)
    }

    // MARK: Applying steps

    /**
     * Persist `step`. With judge work pending the judge holds the floor (owner null) until the lines
     * are written, then the step's owner gets the turn.
     */
    private fun commit(step: Step, caseId: UUID, from: TrialPhase?) {
        val store = store ?: return
        var c = store.caseById(caseId) ?: return
        val pending = step.judgeCalls.isNotEmpty() || step.autoRest != null
        val now = stamp()
        c = c.copy(updatedAt = now)
        if (pending) {
            // Keep the phase until the judge has spoken; nobody may act meanwhile.
            store.demoUpsertCase(c.copy(phaseTurnOwner = null))
            schedule(1.2..2.0) { it.runJudgeCalls(step, caseId, from) }
        } else {
            store.demoUpsertCase(apply(step, c, now))
            advanced(caseId)
        }
    }

    private fun apply(step: Step, c: Case, now: Instant): Case {
        val base = c.copy(status = step.status, phase = step.phase, phaseTurnOwner = step.owner, updatedAt = now)
        return if (step.status == CaseStatus.deliberating) {
            base.copy(deadlineAt = null, deliberatingAt = now, panelProgress = 0)
        } else {
            base.copy(deadlineAt = now.plusSeconds(12 * 3600))
        }
    }

    private fun runJudgeCalls(step: Step, caseId: UUID, from: TrialPhase?) {
        val rulings = step.judgeCalls.filterIsInstance<JudgeCall.objectionRuling>()
        val others = step.judgeCalls.filter { it !is JudgeCall.objectionRuling }
        for (call in rulings) writeJudge(call, caseId, from)
        step.autoRest?.let { side ->
            appendTurn(
                caseId, from, speaker(side), body = "No further exhibits. I rest.",
                meta = mapOf("rest" to JSONValue.Bool(true), "auto" to JSONValue.Bool(true)),
            )
        }
        val finish: (DemoTrialSimulator) -> Unit = { sim ->
            for (call in others) sim.writeJudge(call, caseId, step.phase)
            val store = sim.store
            val c = store?.caseById(caseId)
            if (store != null && c != null) {
                val applied = sim.apply(step, c, sim.stamp())
                store.demoUpsertCase(applied)
                sim.log("→ ${applied.status.rawValue} ${applied.phase?.rawValue ?: "-"} owner ${applied.phaseTurnOwner?.rawValue ?: "-"}")
                sim.advanced(caseId)
            }
        }
        // Give a ruling a moment on stage before the next phase's line.
        if ((rulings.isNotEmpty() || step.autoRest != null) && others.isNotEmpty()) {
            schedule(1.2..1.8, finish)
        } else {
            finish(this)
        }
    }

    private fun writeJudge(call: JudgeCall, caseId: UUID, phase: TrialPhase?) {
        val store = store ?: return
        val prov: Map<String, JSONValue> = mapOf(
            "persona" to JSONValue.Str("wigsworth"), "model_ref" to JSONValue.Str("demo-simulator"), "source" to JSONValue.Str("demo"),
        )
        when (call) {
            is JudgeCall.phaseLine ->
                appendTurn(caseId, call.phase, Speaker.judge, body = pick(Lines.phase(call.phase)), aiCall = AICall.phaseLine, meta = prov)
            is JudgeCall.crossExamine -> {
                val bank = if (call.side == Role.plaintiff) Lines.crossPlaintiff else Lines.crossDefendant
                val count = if (config.random() < 0.5) 2 else 3
                val qs = shuffled(bank).take(count)
                val meta = prov.toMutableMap()
                meta["questions"] = JSONValue.Arr(qs.map { JSONValue.Str(it) })
                meta["side"] = JSONValue.Str(call.side.rawValue)
                appendTurn(
                    caseId, TrialPhase.crossExamination, Speaker.judge,
                    body = qs.mapIndexed { i, q -> "${i + 1}. $q" }.joinToString("\n"),
                    aiCall = AICall.crossExamine, meta = meta,
                )
            }
            is JudgeCall.objectionRuling -> {
                val ex = store.exhibits.firstOrNull { it.id == call.exhibitId } ?: return
                val reason = ex.objectionReason ?: return
                val ruling = if (config.random() < 0.5) ObjectionRuling.sustained else ObjectionRuling.overruled
                val note = pick(if (ruling == ObjectionRuling.sustained) Lines.sustained else Lines.overruled)
                store.demoUpsertExhibit(ex.copy(objectionRuling = ruling, objectionNote = note))
                val meta = prov.toMutableMap()
                meta["objection"] = JSONValue.Obj(mapOf("reason" to JSONValue.Str(reason.rawValue), "ruling" to JSONValue.Str(ruling.rawValue)))
                meta["exhibit_id"] = JSONValue.Str(call.exhibitId.toString().lowercase())
                appendTurn(
                    caseId, phase, Speaker.judge,
                    body = "${if (ruling == ObjectionRuling.sustained) "Sustained." else "Overruled."} $note",
                    exhibitId = call.exhibitId, aiCall = AICall.objectionRuling, meta = meta,
                )
                log("judge: ${ruling.rawValue} on ${ex.displayName}")
            }
        }
    }

    /** After a step lands: start deliberation, or queue whoever the simulator plays next. */
    private fun advanced(caseId: UUID) {
        val c = store?.caseById(caseId) ?: return
        if (c.status == CaseStatus.deliberating) {
            deliberate(caseId); return
        }
        queueActor(caseId)
    }

    private fun queueActor(caseId: UUID) {
        val store = store ?: return
        val me = store.me ?: return
        val c = store.caseById(caseId) ?: return
        if (c.status != CaseStatus.trial || c.settlementId != null) return
        val owner = c.phaseTurnOwner ?: return
        val mine = c.role(me.id) ?: return
        if (!(owner != mine || config.autoplay) || queuedActors.contains(caseId)) return
        queuedActors.add(caseId)
        schedule(1.5..3.0) { sim ->
            sim.queuedActors.remove(caseId)
            sim.act(caseId, owner)
        }
    }

    /** The simulated party (the partner, or me under autoplay) takes its turn. */
    private fun act(caseId: UUID, role: Role) {
        val store = store ?: return
        val c = store.caseById(caseId) ?: return
        val phase = c.phase
        if (c.status != CaseStatus.trial || c.settlementId != null || c.phaseTurnOwner != role || phase == null) return
        try {
            val presenter = presenter(phase)
            if (presenter != null) {
                if (role == presenter) {
                    val ex = unpresented(c, store.exhibits(caseId), role).firstOrNull()
                    if (ex != null) submit(caseId, role, pick(Lines.presenting(ex)), ex.id)
                    else submit(caseId, role, restLine(role), null)
                } else {
                    val ex = exhibitUnderReview(c, store.turns(caseId), store.exhibits(caseId))
                    if (ex != null) {
                        val passChance = if (role == store.me?.let { c.role(it.id) }) 0.5 else 0.6
                        val reason = if (config.random() < passChance) null else pick(ObjectionReason.entries)
                        `object`(caseId, role, ex.id, reason)
                    }
                }
                return
            }
            val body = when (phase) {
                TrialPhase.plaintiffOpening, TrialPhase.defendantOpening -> pick(if (role == Role.plaintiff) Lines.openingPlaintiff else Lines.openingDefendant)
                TrialPhase.crossExamination -> pick(Lines.crossAnswers)
                TrialPhase.plaintiffClosing, TrialPhase.defendantClosing -> pick(if (role == Role.plaintiff) Lines.closingPlaintiff else Lines.closingDefendant)
                else -> "Your Honour."
            }
            submit(caseId, role, body, null)
        } catch (e: Exception) {
            log("simulated ${role.rawValue} could not act: $e")
        }
    }

    // MARK: Deliberation & verdict

    private fun deliberate(caseId: UUID) {
        log("deliberating")
        // The demo ruling lands in seconds, not at the far-off court time: point the countdown at it.
        store?.caseById(caseId)?.let { c ->
            val eta: Double = when (config.speed) {
                Speed.normal -> 4 * 2.0 + 20
                Speed.fast -> 4 * 0.3 + 3
                Speed.instant -> 0.0
            }
            store?.demoUpsertCase(c.copy(trialAt = stamp().plusMillis((eta * 1000).toLong())))
        }
        fun tick(n: Int) {
            schedule(1.8..2.2) { sim ->
                val store = sim.store ?: return@schedule
                val c = store.caseById(caseId) ?: return@schedule
                if (c.status != CaseStatus.deliberating) return@schedule
                if (n <= 4) {
                    store.demoUpsertCase(c.copy(panelProgress = n, updatedAt = sim.stamp()))
                    sim.log("panel_progress $n")
                    tick(n + 1)
                } else {
                    sim.writeVerdict(caseId)
                }
            }
        }
        tick(1)
    }

    private fun writeVerdict(caseId: UUID) {
        val store = store ?: return
        val c = store.caseById(caseId) ?: return
        val now = stamp()
        // Weights: sustained objections cap at 1; everything presented gets 1…3.
        val presented = mutableListOf<Exhibit>()
        for (ex in store.exhibits(caseId)) {
            if (ex.presentedAt == null) continue
            val w = if (ex.objectionRuling == ObjectionRuling.sustained) (config.random() * 2).toInt() else 1 + (config.random() * 3).toInt()
            val weighted = ex.copy(weight = min(3, max(0, w)))
            store.demoUpsertExhibit(weighted)
            presented.add(weighted)
        }
        fun score(r: Role): Int = presented.count { it.ownerId == c.userId(r) && (it.weight ?: 0) > 0 }
        val p = score(Role.plaintiff)
        val d = score(Role.defendant)
        val winnerRole = if (p != d) (if (p > d) Role.plaintiff else Role.defendant) else (if (config.random() < 0.5) Role.plaintiff else Role.defendant)
        val winnerId = c.userId(winnerRole)
        val loserId = c.userId(winnerRole.other)

        val findings = presented.sortedByDescending { it.weight ?: 0 }.take(3).map { ex ->
            val side = c.role(ex.ownerId) ?: Role.plaintiff
            val w = ex.weight ?: 0
            val text = when {
                ex.objectionRuling == ObjectionRuling.sustained -> "Objection sustained; \"${ex.caption}\" carries little weight."
                w >= 3 -> "\"${ex.caption}\" is the most persuasive item in the record."
                w == 2 -> "\"${ex.caption}\" supports the ${side.rawValue}'s account."
                else -> "\"${ex.caption}\" adds colour, not much more."
            }
            VerdictFinding(exhibitId = ex.id, label = ex.displayName, side = side, finding = text, weight = w)
        }
        val sustainedCount = presented.count { it.objectionRuling == ObjectionRuling.sustained }
        val recap = "The plaintiff says: ${c.charge} The defendant disputes it. The court heard ${presented.size} exhibit${if (presented.size == 1) "" else "s"}" +
            if (sustainedCount > 0) ", $sustainedCount with an objection sustained." else " and no sustained objections."
        // Amendment j: the ruling no longer invents a sentence; the prevailing party chooses the judgement.
        val sentence = judgementPendingSentence
        val votes = JSONValue.Obj(mapOf(winnerRole.rawValue to JSONValue.Num(2.0), winnerRole.other.rawValue to JSONValue.Num(1.0), "tie" to JSONValue.Num(0.0)))
        val verdict = Verdict(
            id = UUID.randomUUID(), caseId = caseId, kind = VerdictKind.ruling, winnerId = winnerId, isTie = false, recap = recap,
            findings = findings, sentence = sentence, closingLine = pick(Lines.closingLines),
            panelSplit = "2-1", panelVotes = votes, confidenceLabel = "medium",
            modelRef = "demo-simulator", promptVersion = "demo", createdAt = now,
        )
        val reviews = listOf(
            JurorReview(
                id = UUID.randomUUID(), caseId = caseId, jurorRole = JurorRole.evidence,
                findings = JSONValue.Obj(mapOf("summary" to JSONValue.Str("The presented exhibits favour the ${winnerRole.rawValue}; ${presented.size} item${if (presented.size == 1) "" else "s"} reviewed."))),
                preferredWinnerId = winnerId, isTie = false, confidence = 0.78, modelRef = "demo-simulator", promptVersion = "demo", createdAt = now,
            ),
            JurorReview(
                id = UUID.randomUUID(), caseId = caseId, jurorRole = JurorRole.consistency,
                findings = JSONValue.Obj(mapOf("summary" to JSONValue.Str("The ${winnerRole.rawValue}'s account held together from opening to closing; the other side's shifted under cross-examination."))),
                preferredWinnerId = winnerId, isTie = false, confidence = 0.66, modelRef = "demo-simulator", promptVersion = "demo", createdAt = now,
            ),
            JurorReview(
                id = UUID.randomUUID(), caseId = caseId, jurorRole = JurorRole.fairness,
                findings = JSONValue.Obj(mapOf("summary" to JSONValue.Str("Both parties contributed. A lighter, shared remedy would be proportionate."))),
                preferredWinnerId = loserId, isTie = false, confidence = 0.52, modelRef = "demo-simulator", promptVersion = "demo", createdAt = now,
            ),
        )
        // Like RLS in the live app, the verdict rows only reach the client at the reveal.
        val wait: Double = when (config.speed) {
            Speed.normal -> 20.0
            Speed.fast -> 3.0
            Speed.instant -> 0.0
        }
        store.demoUpsertCase(c.copy(status = CaseStatus.awaitingVerdict, panelProgress = 4, trialAt = now.plusMillis((wait * 1000).toLong()), updatedAt = now))
        log("verdict written (${winnerRole.rawValue} wins) → awaiting_verdict, reveal in ${wait.toInt()} s")
        schedule(wait) { sim ->
            val s = sim.store ?: return@schedule
            val fresh = s.caseById(caseId) ?: return@schedule
            if (fresh.status != CaseStatus.awaitingVerdict) return@schedule
            val t = sim.stamp()
            s.demoAddVerdict(verdict, reviews)
            s.demoUpsertCase(fresh.copy(status = CaseStatus.verdict, verdictAt = t, updatedAt = t))
            sim.log("verdict revealed")
            sim.openJudgement(caseId, verdict)
        }
    }

    // MARK: Utilities

    private fun appendTurn(
        caseId: UUID,
        phase: TrialPhase?,
        speaker: Speaker,
        body: String,
        exhibitId: UUID? = null,
        aiCall: AICall? = null,
        meta: Map<String, JSONValue> = emptyMap(),
    ) {
        store?.demoAppendTurn(
            Turn(
                id = UUID.randomUUID(), caseId = caseId, phase = phase, speaker = speaker, body = body, exhibitId = exhibitId,
                aiCall = aiCall, meta = JSONValue.Obj(meta), createdAt = stamp(),
            ),
        )
    }

    /** Strictly increasing timestamps so the transcript order is stable. */
    private fun stamp(): Instant {
        val now = Instant.now()
        lastStamp = if (now.isAfter(lastStamp)) now else lastStamp.plusMillis(1)
        return lastStamp
    }

    private fun <T> pick(list: List<T>): T = list[min(list.size - 1, (config.random() * list.size).toInt())]

    private fun <T> shuffled(list: List<T>): List<T> = list.map { it to config.random() }.sortedBy { it.second }.map { it.first }

    private fun wrongState(message: String = "The case has moved on."): EdgeError = EdgeError(code = "wrong_state", message = message)

    private fun log(message: String) {
        runCatching { Log.d("DemoSim", "[DemoSim] $message") }
    }

    // MARK: - Court judgement (amendment j)

    /**
     * On reveal (mirror of the backend): the `judgements` row with the chooser (the winner; the
     * plaintiff on a guilty plea) and a round-0 set of four canned, theme-matched options.
     * A tie (amendment l) has no chooser: the court generates 2–3 compromise options, picks one and
     * delivers it at once (`chooser_id` / `selected_by` null); either partner may serve or decline it.
     */
    fun openJudgement(caseId: UUID, verdict: Verdict) {
        val store = store ?: return
        val c = store.caseById(caseId) ?: return
        if (store.judgement(caseId) != null) return
        val now = stamp()
        val theme = DemoJudgementCatalog.theme(c)
        if (verdict.isTie) {
            deliverCourtResolution(caseId, verdict, theme, now)
            return
        }
        val chooser = verdict.winnerId ?: c.plaintiffId
        store.demoSetJudgement(
            Judgement(
                caseId = caseId, verdictId = verdict.id, theme = theme.rawValue, status = JudgementStatus.pendingSelection,
                chooserId = chooser, createdAt = now, updatedAt = now,
            ),
        )
        store.demoSetJudgementOptions(optionSet(caseId, round = 0))
        log("judgement opened (theme ${theme.rawValue}); chooser is ${if (chooser == store.me?.id) "me" else "the partner"}")
        if (chooser != store.me?.id) queuePartnerChoice(caseId)
    }

    fun rerollJudgement(caseId: UUID): JudgementOptionSet {
        val store = store ?: throw wrongState()
        val j = store.judgement(caseId) ?: throw wrongState()
        if (j.status != JudgementStatus.pendingSelection) throw wrongState("The judgement has already been delivered.")
        if (j.chooserId != store.me?.id) throw EdgeError(code = "wrong_role", message = "Only the prevailing party chooses the judgement.")
        if (j.rerolls >= Judgement.maxRerolls) throw EdgeError(code = "limit_rerolls", message = EdgeErrors.rerollLimitMessage)
        val next = j.copy(rerolls = j.rerolls + 1, updatedAt = stamp())
        val set = optionSet(caseId, round = next.rerolls)
        store.demoSetJudgement(next)
        store.demoSetJudgementOptions(set)
        log("reroll_judgement → round ${next.rerolls}")
        return set
    }

    fun selectJudgement(caseId: UUID, optionId: String) {
        val store = store ?: throw wrongState()
        val me = store.me ?: throw wrongState()
        val j = store.judgement(caseId) ?: throw wrongState()
        if (j.chooserId != me.id) throw EdgeError(code = "wrong_role", message = "Only the prevailing party chooses the judgement.")
        applySelection(caseId, optionId)
    }

    fun respondJudgement(caseId: UUID, accept: Boolean) {
        val store = store ?: throw wrongState()
        val me = store.me ?: throw wrongState()
        val j = store.judgement(caseId) ?: throw wrongState()
        if (j.status != JudgementStatus.delivered) throw wrongState()
        // A court-chosen resolution (tie) may be declined by either partner; it is never "accepted".
        if (j.isCourtChosen) {
            if (accept) throw EdgeError(code = "wrong_state", message = "The court's resolution needs no acceptance. Mark it served when it's done.")
        } else if (j.chooserId == me.id) {
            throw EdgeError(code = "wrong_role", message = "The other party responds to the judgement.")
        }
        val now = stamp()
        val next = if (accept) j.copy(status = JudgementStatus.accepted, acceptedAt = now, updatedAt = now)
        else j.copy(status = JudgementStatus.declined, declinedAt = now, updatedAt = now)
        store.demoSetJudgement(next)
        log("respond_judgement → ${next.status.rawValue}")
    }

    fun markServed(caseId: UUID) {
        val store = store ?: throw wrongState()
        val me = store.me ?: throw wrongState()
        val j = store.judgement(caseId) ?: throw wrongState()
        if (j.status != JudgementStatus.delivered && j.status != JudgementStatus.accepted) throw wrongState()
        val now = stamp()
        store.demoSetJudgement(j.copy(status = JudgementStatus.served, servedAt = now, servedBy = me.id, updatedAt = now))
        log("mark_served → served")
    }

    /** Relaunch: a partner who is still to choose picks after a pause. */
    fun resumeJudgements() {
        val store = store ?: return
        val me = store.me ?: return
        for ((id, j) in store.judgements) {
            if (j.status == JudgementStatus.pendingSelection && j.chooserId != null && j.chooserId != me.id) queuePartnerChoice(id)
        }
    }

    /** The simulated partner chooses (the first option of the latest round) after a pause. */
    private fun queuePartnerChoice(caseId: UUID) {
        schedule(4.0..6.0) { sim ->
            val store = sim.store ?: return@schedule
            val j = store.judgement(caseId) ?: return@schedule
            if (j.status != JudgementStatus.pendingSelection) return@schedule
            val option = store.judgementOptions(caseId)?.options?.firstOrNull() ?: return@schedule
            runCatching { sim.applySelection(caseId, option.id) }
        }
    }

    /** select_judgement: status delivered, due date, and the judge's delivery turn (brief screen C template). */
    private fun applySelection(caseId: UUID, optionId: String) {
        val store = store ?: throw wrongState()
        val j = store.judgement(caseId) ?: throw wrongState()
        val c = store.caseById(caseId) ?: throw wrongState()
        if (j.status != JudgementStatus.pendingSelection) throw wrongState("The judgement has already been delivered.")
        val option = store.judgementOptions(caseId)?.options?.firstOrNull { it.id == optionId }
            ?: throw EdgeError(code = "invalid_option", message = "That option is no longer on offer. Choose from the current list.")
        val now = stamp()
        val tie = store.verdict(caseId)?.isTie == true
        val chooserId = j.chooserId ?: throw wrongState("The court has already chosen a resolution.")
        val winner = store.name(chooserId, fallback = "the plaintiff")
        val loserId = if (chooserId == c.plaintiffId) c.defendantId else c.plaintiffId
        val loser = store.name(loserId, fallback = "the defendant")
        val turnId = UUID.randomUUID()
        store.demoAppendTurn(
            Turn(
                id = turnId, caseId = caseId, speaker = Speaker.judge,
                body = DemoJudgementCatalog.deliveryText(winner, loser, option, tie),
                aiCall = AICall.judgementDelivery, meta = JSONValue.Obj(mapOf("judgement" to JSONValue.Bool(true))), createdAt = now,
            ),
        )
        store.demoSetJudgement(
            j.copy(
                status = JudgementStatus.delivered, selected = option, selectedBy = j.chooserId, selectedAt = now,
                deliveredTurnId = turnId, dueAt = now.plusSeconds(option.dueDays * 86_400L), updatedAt = now,
            ),
        )
        log("select_judgement ${option.id} → delivered")
        // The simulated loser accepts after reading the delivery.
        if (loserId != store.me?.id) {
            schedule(4.0..6.0) { sim -> runCatching { sim.partnerAccepts(caseId) } }
        }
    }

    /**
     * Tie: 2–3 compromise options, the court picks one (the first validated option, like the backend's
     * fallback) and delivers it immediately with the tie template. No option set is published: nobody chose.
     */
    private fun deliverCourtResolution(caseId: UUID, verdict: Verdict, theme: DemoJudgementCatalog.Theme, now: Instant) {
        val store = store ?: return
        val options = DemoJudgementCatalog.options(theme, tie = true, round = 0, winner = "", loser = "")
        val option = options.firstOrNull() ?: return
        val turnId = UUID.randomUUID()
        store.demoAppendTurn(
            Turn(
                id = turnId, caseId = caseId, speaker = Speaker.judge,
                body = DemoJudgementCatalog.deliveryText("", "", option, tie = true),
                aiCall = AICall.judgementDelivery, meta = JSONValue.Obj(mapOf("judgement" to JSONValue.Bool(true))), createdAt = now,
            ),
        )
        store.demoSetJudgement(
            Judgement(
                caseId = caseId, verdictId = verdict.id, theme = theme.rawValue, status = JudgementStatus.delivered,
                chooserId = null, selected = option, selectedBy = null, selectedAt = now,
                deliveredTurnId = turnId, dueAt = now.plusSeconds(option.dueDays * 86_400L),
                createdAt = now, updatedAt = now,
            ),
        )
        log("tie: court chose ${option.id} from ${options.size} compromise options → delivered")
    }

    private fun partnerAccepts(caseId: UUID) {
        val store = store ?: return
        val j = store.judgement(caseId) ?: return
        if (j.status != JudgementStatus.delivered) return
        val now = stamp()
        store.demoSetJudgement(j.copy(status = JudgementStatus.accepted, acceptedAt = now, updatedAt = now))
        log("partner accepts the judgement")
    }

    private fun optionSet(caseId: UUID, round: Int): JudgementOptionSet {
        val store = store
        val c = store?.caseById(caseId)
        val j = store?.judgement(caseId)
        val chooser = j?.chooserId ?: c?.plaintiffId ?: UUID.randomUUID()
        val loserId = c?.let { if (chooser == it.plaintiffId) it.defendantId else it.plaintiffId }
        val winner = store?.name(chooser, fallback = "the winner") ?: "the winner"
        val loser = loserId?.let { store?.name(it, fallback = "the other party") } ?: "the other party"
        val theme = c?.let(DemoJudgementCatalog::theme) ?: DemoJudgementCatalog.Theme.general
        val tie = store?.verdict(caseId)?.isTie == true
        return JudgementOptionSet(
            id = UUID.randomUUID(), caseId = caseId, round = round,
            options = DemoJudgementCatalog.options(theme, tie, round, winner, loser),
            generatedFor = chooser, createdAt = stamp(),
        )
    }

    // MARK: - Settle Outside Court (amendment n)

    /**
     * Three canned suggestions (quick / fair / peace) from the case's category pool, after a short
     * "the court is thinking" pause.
     */
    suspend fun generateSettlementOptions(caseId: UUID): List<SettlementSuggestion> {
        val c = store?.caseById(caseId) ?: throw EdgeError(code = "wrong_state", message = "The case has moved on.")
        val wait = when (config.speed) {
            Speed.normal -> 1.4
            Speed.fast -> 0.3
            Speed.instant -> 0.0
        }
        if (wait > 0) kotlinx.coroutines.delay((wait * 1000).toLong())
        return DemoSettlementCatalog.suggestions(DemoJudgementCatalog.theme(c))
    }

    fun proposeSettlement(caseId: UUID, body: String, source: SettlementSource, dueDays: Int) {
        val store = store
        val me = store?.me
        val c = store?.caseById(caseId)
        if (store == null || me == null || c == null) throw EdgeError(code = "wrong_state", message = "The case has moved on.")
        if (!SettlementRules.canPropose(c, me.id, store.settlement(caseId))) {
            throw EdgeError(code = "wrong_state", message = "A settlement can't be proposed at this stage.")
        }
        val now = stamp()
        val entry = SettlementRules.entryPoint(c.status)
        val remaining = c.deadlineAt?.let { max(0L, Duration.between(now, it).seconds).toInt() }
        val settlement = Settlement(
            id = UUID.randomUUID(), caseId = caseId, status = SettlementStatus.proposed, initiatedBy = me.id, currentRound = 1, entryPoint = entry,
            pausedFromStatus = c.status, pausedPhase = c.phase, pausedTurnOwner = c.phaseTurnOwner,
            pausedRemainingSeconds = remaining, expiresAt = now.plusSeconds(window(entry)),
            createdAt = now, updatedAt = now,
        )
        store.demoSetSettlement(settlement)
        store.demoUpsertOffer(offer(settlement, round = 1, by = me.id, body = body, source = source, dueDays = dueDays, at = now))
        // The court waits: the timer pauses and nobody acts until the offer resolves.
        store.demoUpsertCase(c.copy(deadlineAt = null, settlementId = settlement.id, updatedAt = now))
        log("propose_settlement → round 1 ($entry); timer paused with ${remaining?.toString() ?: "-"} s left")
        queuePartnerSettlementReply(caseId)
    }

    fun counterSettlement(caseId: UUID, body: String, source: SettlementSource, dueDays: Int) {
        val me = store?.me ?: throw EdgeError(code = "wrong_state", message = "The case has moved on.")
        applyCounter(caseId, me.id, body, source, dueDays)
        queuePartnerSettlementReply(caseId)
    }

    fun respondToSettlement(caseId: UUID, accept: Boolean) {
        val me = store?.me ?: throw EdgeError(code = "wrong_state", message = "The case has moved on.")
        applyResponse(caseId, me.id, accept)
    }

    fun withdrawSettlement(caseId: UUID) {
        val store = store
        val me = store?.me
        val s = store?.settlement(caseId)
        if (store == null || me == null || s == null || !s.isPending) throw EdgeError(code = "wrong_state", message = "This settlement has moved on.")
        if (SettlementRules.currentProposer(s, store.latestOffer(caseId), store.caseById(caseId)) != me.id) {
            throw EdgeError(code = "wrong_role", message = "Only the proposer can withdraw the offer.")
        }
        val now = stamp()
        val withdrawn = s.copy(status = SettlementStatus.withdrawn, withdrawnAt = now, updatedAt = now)
        store.demoSetSettlement(withdrawn)
        restoreCourt(caseId, withdrawn, now)
        log("withdraw_settlement → court resumes")
    }

    fun markSettlementFulfilled(caseId: UUID) {
        val store = store
        val me = store?.me
        val s = store?.settlement(caseId)
        if (store == null || me == null || s == null || s.status != SettlementStatus.accepted) {
            throw EdgeError(code = "wrong_state", message = "This settlement has moved on.")
        }
        val now = stamp()
        store.demoSetSettlement(s.copy(status = SettlementStatus.fulfilled, fulfilledAt = now, fulfilledBy = me.id, updatedAt = now))
        log("mark_settlement_fulfilled → fulfilled")
    }

    /** Relaunch: an offer waiting on the simulated partner gets its reply after a pause. */
    fun resumeSettlements() {
        val store = store ?: return
        val me = store.me ?: return
        for ((caseId, s) in store.settlements) {
            if (s.isPending && store.caseById(caseId)?.status?.isOpen == true) {
                if (!SettlementRules.awaitsResponse(me.id, s, store.latestOffer(caseId))) queuePartnerSettlementReply(caseId)
            }
        }
    }

    // MARK: Simulated partner

    /** ~4 s later the partner answers: accept (50 %), counter once (below round 3), else reject. */
    private fun queuePartnerSettlementReply(caseId: UUID) {
        val wait = when (config.speed) {
            Speed.normal -> 4.0
            Speed.fast -> 0.3
            Speed.instant -> 0.0
        }
        schedule(wait) { it.partnerReplies(caseId) }
    }

    fun partnerReplies(caseId: UUID) {
        val store = store ?: return
        val me = store.me ?: return
        val c = store.caseById(caseId) ?: return
        val s = store.settlement(caseId) ?: return
        if (!s.isPending || SettlementRules.awaitsResponse(me.id, s, store.latestOffer(caseId))) return
        val partnerId = if (c.plaintiffId == me.id) c.defendantId else c.plaintiffId
        val partnerCountered = store.settlementOffers(caseId).any { it.proposedBy == partnerId && it.roundNumber > 1 }
        val roll = config.random()
        try {
            if (roll < 0.5) {
                applyResponse(caseId, partnerId, accept = true)
            } else if (roll < 0.75 && SettlementRules.canCounter(s) && !partnerCountered) {
                val pick = DemoSettlementCatalog.suggestions(DemoJudgementCatalog.theme(c)).last()
                applyCounter(caseId, partnerId, pick.body, SettlementSource.ai, pick.dueDays, pick.category)
            } else {
                applyResponse(caseId, partnerId, accept = false)
            }
        } catch (e: Exception) {
            log("simulated partner could not answer the settlement: $e")
        }
    }

    // MARK: Transitions (mirror of the backend)

    private fun applyCounter(caseId: UUID, by: UUID, body: String, source: SettlementSource, dueDays: Int, category: String? = null) {
        val store = store
        val s = store?.settlement(caseId)
        if (store == null || s == null || !s.isPending) throw EdgeError(code = "wrong_state", message = "This settlement has moved on.")
        if (!SettlementRules.awaitsResponse(by, s, store.latestOffer(caseId))) {
            throw EdgeError(code = "wrong_role", message = "Only the receiver can counter.")
        }
        if (!SettlementRules.canCounter(s)) throw EdgeError(code = "limit_rounds", message = EdgeErrors.settlementRoundLimitMessage)
        val now = stamp()
        val next = s.copy(
            currentRound = s.currentRound + 1, status = SettlementStatus.countered, updatedAt = now,
            expiresAt = now.plusSeconds(window(s.entryPoint ?: "trial")),
        )
        store.demoSetSettlement(next)
        store.demoUpsertOffer(offer(next, next.currentRound, by, body, source, dueDays, category, now))
        log("counter_settlement → round ${next.currentRound}")
    }

    private fun applyResponse(caseId: UUID, by: UUID, accept: Boolean) {
        val store = store
        val s = store?.settlement(caseId)
        val c = store?.caseById(caseId)
        if (store == null || s == null || !s.isPending || c == null) throw EdgeError(code = "wrong_state", message = "This settlement has moved on.")
        if (!SettlementRules.awaitsResponse(by, s, store.latestOffer(caseId))) {
            throw EdgeError(code = "wrong_role", message = "Only the receiver can respond to this offer.")
        }
        val now = stamp()
        if (accept) {
            val terms = store.latestOffer(caseId)
            store.demoSetSettlement(
                s.copy(
                    status = SettlementStatus.accepted, acceptedAt = now, acceptedOfferId = terms?.id, updatedAt = now,
                    dueAt = now.plusSeconds((terms?.dueDays ?: 3) * 86_400L),
                ),
            )
            store.demoUpsertCase(
                c.copy(status = CaseStatus.closedSettled, closedAt = now, deadlineAt = null, phaseTurnOwner = null, settlementId = null, updatedAt = now),
            )
            appendTurn(
                caseId, null, Speaker.judge, body = DemoSettlementCatalog.flavourLine,
                meta = mapOf("settlement" to JSONValue.Bool(true), "source" to JSONValue.Str("demo")),
            )
            log("respond_to_settlement accept → closed_settled")
        } else {
            val rejected = s.copy(status = SettlementStatus.rejected, rejectedAt = now, updatedAt = now)
            store.demoSetSettlement(rejected)
            restoreCourt(caseId, rejected, now)
            log("respond_to_settlement reject → court resumes")
        }
    }

    /** Reject / withdraw / expire: the case resumes its paused phase with the remaining time (min 10 min). */
    private fun restoreCourt(caseId: UUID, s: Settlement, now: Instant) {
        val store = store ?: return
        var c = store.caseById(caseId) ?: return
        c = c.copy(settlementId = null, updatedAt = now)
        s.pausedRemainingSeconds?.let { remaining -> c = c.copy(deadlineAt = now.plusSeconds(max(remaining, 600).toLong())) }
        store.demoUpsertCase(c)
        if (c.status == CaseStatus.trial) queueActor(caseId)
    }

    private fun offer(
        s: Settlement,
        round: Int,
        by: UUID,
        body: String,
        source: SettlementSource,
        dueDays: Int,
        category: String? = null,
        at: Instant,
    ): SettlementOffer {
        val theme = store?.caseById(s.caseId)?.let(DemoJudgementCatalog::theme)?.rawValue
        return SettlementOffer(
            id = UUID.randomUUID(), settlementId = s.id, roundNumber = round, proposedBy = by, body = body, source = source,
            category = category ?: if (source == SettlementSource.ai) theme else "custom", dueDays = dueDays, createdAt = at, expiresAt = s.expiresAt,
        )
    }

    companion object {
        /** Swift `Verdict.judgementPendingSentence` (Features/Judgement/JudgementPresentation.kt): the fixed line the backend writes since amendment j. */
        val judgementPendingSentence: String get() = Verdict.judgementPendingSentence

        /** Swift `CourtroomLogic.restLine(for:)`: the default body when resting without typing anything. */
        fun restLine(role: Role): String = CourtroomLogic.restLine(role)

        fun presenter(phase: TrialPhase?): Role? = when (phase) {
            TrialPhase.plaintiffExhibits -> Role.plaintiff
            TrialPhase.defendantExhibits -> Role.defendant
            else -> null
        }

        fun speaker(role: Role): Speaker = if (role == Role.plaintiff) Speaker.plaintiff else Speaker.defendant

        fun unpresented(c: Case, exhibits: List<Exhibit>, side: Role): List<Exhibit> {
            val owner = c.userId(side)
            return exhibits.filter { it.caseId == c.id && it.ownerId == owner && it.presentedAt == null && it.type != ExhibitType.voice }
                .sortedWith(compareBy<Exhibit> { it.sort }.thenBy { it.label.index })
        }

        fun exhibitUnderReview(c: Case, turns: List<Turn>, exhibits: List<Exhibit>): Exhibit? {
            val presenter = presenter(c.phase) ?: return null
            val owner = c.userId(presenter)
            val mine = exhibits.filter { it.ownerId == owner && it.caseId == c.id }
            val last = turns.lastOrNull { it.phase == c.phase && it.speaker == speaker(presenter) && it.exhibitId != null }
            if (last != null) mine.firstOrNull { it.id == last.exhibitId }?.let { return it }
            return mine.filter { it.presentedAt != null }.maxByOrNull { it.presentedAt!! }
        }

        /** 24 h from the summons, 12 h in trial. */
        fun window(entryPoint: String): Long = if (entryPoint == "summons") 24 * 3600L else 12 * 3600L
    }
}

/**
 * Canned, theme-matched judgement options for the demo (brief §3 table). The live app gets these
 * from the backend generator; every detail is an imperative addressed to the party it binds.
 */
object DemoJudgementCatalog {
    enum class Theme(val rawValue: String) {
        food("food"), lateness("lateness"), chores("chores"), entertainment("entertainment"), social("social"), sleep("sleep"), general("general")
    }

    fun theme(c: Case): Theme = theme("${c.title} ${c.charge} ${c.remedyRequested}")

    fun theme(text: String): Theme {
        // Whole-word prefixes ("eat" must not match "great").
        val words = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        val table = listOf(
            Theme.food to listOf("pizza", "slice", "meal", "dinner", "takeaway", "cook", "food", "snack", "eat", "fridge", "lunch", "breakfast"),
            Theme.chores to listOf("dish", "laundry", "clean", "chore", "washing", "hoover", "bins", "tidy"),
            Theme.entertainment to listOf("movie", "film", "episode", "show", "game", "playlist", "spoiler", "series", "tv"),
            Theme.social to listOf("instagram", "like", "follow", "phone", "social", "text", "message"),
            Theme.sleep to listOf("sleep", "duvet", "snore", "alarm", "noise", "loud", "3am", "bed"),
            Theme.lateness to listOf("late", "plans", "waiting", "cancel", "forgot"),
        )
        for ((theme, keys) in table) if (words.any { w -> keys.any { w.startsWith(it) } }) return theme
        return Theme.general
    }

    /** Four options per round (three compromise resolutions on a tie), rotating through the pool. */
    fun options(theme: Theme, tie: Boolean, round: Int, winner: String, loser: String): List<JudgementOption> {
        val pool = if (tie) compromise(theme) else themed(theme) + (if (theme == Theme.general) emptyList() else general)
        val size = if (tie) 3 else 4
        val start = (round * size) % max(1, pool.size)
        val picked = (0 until min(size, pool.size)).map { pool[(start + it) % pool.size] }
        return picked.map { o ->
            JudgementOption(
                id = o.id, title = fill(o.title, winner, loser), detail = fill(o.detail, winner, loser),
                type = if (tie) JudgementOptionType.compromise else o.type, dueDays = o.days, generic = if (o.generic) true else null,
            )
        }
    }

    /** Brief screen C, templated like the backend's fallback. */
    fun deliveryText(winner: String, loser: String, option: JudgementOption, tie: Boolean): String {
        var order = option.detail.trim()
        while (order.endsWith(".")) order = order.dropLast(1)
        if (order.isNotEmpty()) order = order.first().lowercase() + order.drop(1)
        if (tie) {
            return "The court could not separate you. It has chosen a resolution for you both. Both parties are hereby ordered to $order. The court considers this matter settled."
        }
        return "The court finds for $winner. The prevailing party has selected their judgement. $loser is hereby ordered to $order. The court considers this matter settled."
    }

    private data class Seed(val id: String, val title: String, val detail: String, val type: JudgementOptionType, val days: Int, val generic: Boolean = false)

    private fun fill(s: String, w: String, l: String): String = s.replace("{W}", w).replace("{L}", l)

    private fun themed(theme: Theme): List<Seed> = when (theme) {
        Theme.food -> listOf(
            Seed("replace_slice", "Replace the last slice", "Buy {W} a fresh pizza within 3 days.", JudgementOptionType.directRemedy, 3),
            Seed("dinner_out", "Take {W} out for dinner this week", "Plan and book dinner out for the two of you within 7 days.", JudgementOptionType.effort, 7),
            Seed("favourite_meal", "Cook {W}'s favourite meal", "Cook {W}'s favourite meal at home within 5 days.", JudgementOptionType.effort, 5),
            Seed("next_takeaway", "{W} chooses the next takeaway", "Let {W} choose the next takeaway, with no veto.", JudgementOptionType.privilege, 7),
            Seed("dishes_three", "Dishes for three nights", "Take over the washing up for the next 3 nights.", JudgementOptionType.favour, 3),
            Seed("first_slice", "First slice goes to {W}", "Give {W} first pick at the next pizza night, crusts included.", JudgementOptionType.privilege, 7),
            Seed("ask_first", "Ask before the last bite", "Ask {W} before finishing any shared food, starting today.", JudgementOptionType.directRemedy, 7),
            Seed("snack_run", "A snack run for {W}", "Fetch {W} one snack of their choosing within 2 days.", JudgementOptionType.favour, 2),
            Seed("label_respect", "Honour the label", "Leave anything labelled with {W}'s name alone for the rest of the week.", JudgementOptionType.directRemedy, 7),
            Seed("pizza_night", "Host a pizza night", "Host a pizza night for the two of you within 7 days, with {W}'s toppings.", JudgementOptionType.effort, 7),
            Seed("breakfast", "Make {W} breakfast", "Make {W} breakfast one morning this week.", JudgementOptionType.effort, 7),
            Seed("dessert", "Dessert is on {L}", "Bring home {W}'s favourite dessert within 3 days.", JudgementOptionType.favour, 3),
        )
        Theme.lateness -> listOf(
            Seed("plan_date", "Plan the next date", "Plan the next date start to finish within 7 days.", JudgementOptionType.effort, 7),
            Seed("coffees", "The coffees are on {L}", "Buy {W}'s next two coffees.", JudgementOptionType.favour, 7),
            Seed("advance_notice", "Give advance notice", "Message {W} at least 30 minutes ahead whenever running late this week.", JudgementOptionType.directRemedy, 7),
            Seed("next_activity", "{W} chooses the next activity", "Let {W} choose the next thing you do together.", JudgementOptionType.privilege, 7),
        )
        Theme.chores -> listOf(
            Seed("washing_up", "Washing up for 3 days", "Take over the washing up for the next 3 days.", JudgementOptionType.directRemedy, 3),
            Seed("laundry", "Laundry this week", "Do the laundry this week, folding included.", JudgementOptionType.favour, 7),
            Seed("clean_room", "Clean the disputed room", "Clean the room in question within 3 days.", JudgementOptionType.directRemedy, 3),
            Seed("chore_free", "A chore-free evening for {W}", "Give {W} one evening off all chores this week.", JudgementOptionType.privilege, 7),
        )
        Theme.entertainment -> listOf(
            Seed("movie_pick", "{W} picks tonight's movie", "Let {W} pick the next movie night's film, without complaint.", JudgementOptionType.privilege, 3),
            Seed("no_veto", "No veto on the next episode", "Watch the next episode {W} chooses, no veto.", JudgementOptionType.privilege, 7),
            Seed("snacks", "Snacks are on {L}", "Supply the snacks for the next watch night.", JudgementOptionType.favour, 7),
            Seed("next_game", "{W} chooses the next game", "Let {W} choose the next game you play together.", JudgementOptionType.privilege, 7),
        )
        Theme.social -> listOf(
            Seed("quality_time", "Plan quality time", "Plan an evening of quality time together within 7 days.", JudgementOptionType.effort, 7),
            Seed("phone_free", "A phone-free dinner", "Have one dinner this week with both phones in another room.", JudgementOptionType.directRemedy, 7),
            Seed("reassurance", "Write a short reassurance note", "Write {W} a short, sincere note within 3 days.", JudgementOptionType.effort, 3),
            Seed("next_date", "{W} chooses the next date", "Let {W} choose the next date, and turn up on time.", JudgementOptionType.privilege, 7),
        )
        Theme.sleep -> listOf(
            Seed("headphones", "Headphones after 11pm", "Use headphones for any late-night listening this week.", JudgementOptionType.directRemedy, 7),
            Seed("morning_task", "Handle the morning task", "Take over the morning routine task for 3 days.", JudgementOptionType.favour, 3),
            Seed("quiet_room", "The quiet room for {W}", "Give {W} the quiet room for one evening this week.", JudgementOptionType.privilege, 7),
            Seed("breakfast_bed", "Breakfast in bed", "Bring {W} breakfast in bed one morning this week.", JudgementOptionType.effort, 7),
        )
        Theme.general -> general
    }

    private val general: List<Seed> = listOf(
        Seed("small_favour", "One small favour", "Do {W} one small favour of their choosing this week.", JudgementOptionType.favour, 7, generic = true),
        Seed("choose_activity", "{W} chooses the next activity", "Let {W} choose the next thing you do together.", JudgementOptionType.privilege, 7, generic = true),
        Seed("peace_offering", "Make a peace offering", "Make {W} a small peace offering within 3 days.", JudgementOptionType.effort, 3, generic = true),
        Seed("shared_task", "Take over a shared task", "Take over one reasonable shared task for the week.", JudgementOptionType.directRemedy, 7, generic = true),
        Seed("date_night", "Plan a date night", "Plan a date night for the two of you within 7 days.", JudgementOptionType.effort, 7, generic = true),
        Seed("coffee_run", "A coffee run", "Bring {W} a coffee within 2 days.", JudgementOptionType.favour, 2, generic = true),
        Seed("playlist", "{W} picks the playlist", "Let {W} choose the music for the next car journey.", JudgementOptionType.privilege, 7, generic = true),
        Seed("note", "A handwritten note", "Write {W} a short handwritten note within 3 days.", JudgementOptionType.effort, 3, generic = true),
    )

    private fun compromise(theme: Theme): List<Seed> {
        val food = listOf(
            Seed("split_next", "Split the next pizza evenly", "Split the next pizza exactly in half, crusts included.", JudgementOptionType.compromise, 7),
            Seed("cook_together", "Cook dinner together", "Cook one dinner together this week.", JudgementOptionType.compromise, 7),
            Seed("alternate_takeaway", "Take turns choosing takeaways", "Take turns choosing the next two takeaways.", JudgementOptionType.compromise, 7),
        )
        val shared = listOf(
            Seed("plan_together", "Plan something together", "Plan one evening together this week, half each.", JudgementOptionType.compromise, 7),
            Seed("take_turns", "Take turns choosing", "Take turns choosing the next two shared activities.", JudgementOptionType.compromise, 7),
            Seed("swap_chore", "Swap one chore each", "Swap one chore each for the week.", JudgementOptionType.compromise, 7),
        )
        return if (theme == Theme.food) food + shared else shared
    }
}

// MARK: - Line bank (Judge Wigsworth + the parties)

private object Lines {
    fun phase(p: TrialPhase): List<String> = when (p) {
        TrialPhase.plaintiffOpening -> listOf(
            "The court is in session. The plaintiff will open. Briefly, one hopes.",
            "Order. The plaintiff may state the grievance. The court has cleared its afternoon, if not its patience.",
        )
        TrialPhase.defendantOpening -> listOf(
            "The court has heard the accusation. The defendant may now explain themselves. Creatively, if necessary.",
            "Thank you. The defendant may respond. The court reminds all parties that sighing is not an argument.",
        )
        TrialPhase.plaintiffExhibits -> listOf(
            "Exhibits. The plaintiff will present the evidence one exhibit at a time, and the defendant may object to each. The court enjoys a good receipt.",
            "The plaintiff may now show the court its evidence, one exhibit at a time. The defendant may object to any of it. Evidence, not adjectives.",
        )
        TrialPhase.defendantExhibits -> listOf(
            "The defendant may now present their evidence, one exhibit at a time. The plaintiff may object to each. Vibes are not exhibits.",
            "The floor passes to the defendant's exhibits, shown one at a time. The plaintiff may object to any. The court's reading glasses are on.",
        )
        TrialPhase.crossExamination -> listOf(
            "Cross-examination. The court has questions.",
        )
        TrialPhase.plaintiffClosing -> listOf(
            "The record is nearly full. The plaintiff may close. The court suggests brevity; the court always suggests brevity.",
            "Closing statements. The plaintiff first. Make the court's job easier.",
        )
        TrialPhase.defendantClosing -> listOf(
            "Final word to the defendant. Make it count. The court is already reaching for its gavel.",
            "The defendant may close. After this, the record is sealed and the court retires to think very hard.",
        )
    }

    val openingPlaintiff = listOf(
        "Your Honour, this was not an accident. It was a pattern, and I have receipts.",
        "The facts are simple, and frankly embarrassing for the other side.",
        "I asked for one thing. One. The court will see how that went.",
    )
    val openingDefendant = listOf(
        "Your Honour, context matters, and the plaintiff has left out all of it.",
        "I'd like the court to know I was provoked. Mildly, but provoked.",
        "This is a misunderstanding that has been blown wildly out of proportion.",
    )

    fun presenting(ex: Exhibit): List<String> = listOf(
        "${ex.displayName}: ${ex.caption}. I'd ask the court to look closely.",
        "I present ${ex.displayName}, \"${ex.caption}\". It speaks for itself.",
        "${ex.displayName}. The court will note the details. All of them.",
    )

    val crossPlaintiff = listOf(
        "When did you first raise this, and how?",
        "Is there any version of events in which you share some of the blame?",
        "What outcome would genuinely settle this for you?",
        "Had this happened before, or is this the first offence?",
    )
    val crossDefendant = listOf(
        "Did you know how the plaintiff felt about this before it happened?",
        "If the roles were reversed, would you have filed this case?",
        "What would you do differently next time?",
        "Why should the court prefer your account to the exhibits?",
    )
    val crossAnswers = listOf(
        "1. Straight away, calmly. 2. Perhaps a very small share. 3. An apology and a fair fix.",
        "1. Yes, I knew, and I'm not proud of it. 2. Honestly, yes. 3. Ask first.",
        "1. The moment I noticed. 2. No. 3. That it doesn't happen again.",
        "1. Not in so many words. 2. Probably not. 3. Talk it through before it becomes a case.",
    )

    val closingPlaintiff = listOf(
        "The exhibits are clear. I ask the court for the remedy I requested, no more, no less.",
        "I've shown the pattern and the proof. The court can do the rest.",
    )
    val closingDefendant = listOf(
        "I've explained myself. I'd ask the court for proportion, and maybe a sense of humour.",
        "The plaintiff has feelings, not proof. I rest my case, and my innocence.",
    )

    val sustained = listOf(
        "The court will not give that exhibit much weight.",
        "That goes beyond what the exhibit can show. It is set aside.",
        "The objection is well made. The court will disregard the flourish.",
    )
    val overruled = listOf(
        "The exhibit stands; the objection was more theatre than law.",
        "The court finds it relevant enough. Proceed.",
        "Nice try. The exhibit remains before the court.",
    )

    val closingLines = listOf(
        "The court notes that fairness, like pizza, is best shared.",
        "So ordered. The court reminds both parties they chose each other.",
        "Justice is served. So, hopefully, is dinner.",
    )

    val defenceStatements = listOf(
        "I didn't know it mattered this much, and I was going to make it right.",
        "This has been exaggerated. I have a perfectly reasonable explanation.",
    )

    data class DefenceExhibit(val type: ExhibitType, val caption: String, val body: String)

    val defenceExhibits = listOf(
        DefenceExhibit(ExhibitType.text, "The message I sent", "\"I thought we were sharing?\" — sent 18:47"),
        DefenceExhibit(ExhibitType.receipt, "The timeline", "Hadn't eaten since lunch, came home at 23:40"),
    )
}

/**
 * Canned settlement suggestions per category (brief §5 table). The live app gets these from
 * `generate_settlement_options`, grounded in the record.
 */
object DemoSettlementCatalog {
    const val flavourLine = "The parties have spared the court the trouble. Miracles do happen."

    fun suggestions(theme: DemoJudgementCatalog.Theme): List<SettlementSuggestion> {
        val cat = theme.rawValue
        fun s(kind: SettlementSuggestionKind, body: String, days: Int, generic: Boolean = false) = SettlementSuggestion(
            id = "${cat}_${kind.rawValue}", kind = kind, body = body, category = if (generic) "general" else cat, dueDays = days,
            generic = if (generic) true else null,
        )
        val q = SettlementSuggestionKind.quick
        val f = SettlementSuggestionKind.fair
        val p = SettlementSuggestionKind.peace
        return when (theme) {
            DemoJudgementCatalog.Theme.food -> listOf(s(q, "Replace the meal.", 2), s(f, "Replace the meal and do the dishes.", 3), s(p, "Take them out for dinner this weekend.", 7))
            DemoJudgementCatalog.Theme.chores -> listOf(s(q, "Take over the dishes for 3 nights.", 3), s(f, "Handle the laundry this week.", 7), s(p, "Give them one chore-free evening.", 7))
            DemoJudgementCatalog.Theme.lateness -> listOf(s(q, "Buy the coffees next time.", 3), s(f, "Give advance notice when running late.", 7), s(p, "Plan the next date, start to finish.", 7))
            DemoJudgementCatalog.Theme.entertainment -> listOf(s(q, "They choose tonight's film.", 1), s(f, "No veto on the next episode, plus snacks.", 3), s(p, "Host a movie night with their favourite snacks.", 7))
            DemoJudgementCatalog.Theme.social -> listOf(s(q, "Clarify the post together.", 1), s(f, "Agree one simple boundary going forward.", 3), s(p, "Plan a relaxed check-in over coffee.", 7))
            DemoJudgementCatalog.Theme.sleep -> listOf(s(q, "Headphones after 11pm this week.", 7), s(f, "Swap sides of the bed for a night.", 3), s(p, "Breakfast in bed one morning this week.", 7))
            DemoJudgementCatalog.Theme.general -> listOf(
                s(q, "Say sorry and mean it.", 1, generic = true), s(f, "Do one small favour of their choosing.", 7, generic = true),
                s(p, "Plan a date night for the two of you.", 7, generic = true),
            )
        }
    }
}
