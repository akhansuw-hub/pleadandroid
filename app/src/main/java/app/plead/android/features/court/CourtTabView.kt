// Port of ArgueWin/Features/Court/CourtTabView.swift.
//
// Court tab: mounts the courtroom's `CourtroomScene` for the case in trial / deliberating / awaiting_verdict / verdict,
// else `CourtroomEmptyState`. Amendment n: a case settled out of court stays on stage when routed here, or when it
// settled from the trial and nothing else is in court (the judge's flavour line, the seal, "Back to docket").
//
// Wave 2b ported the decision logic (which case, fixture vs live, the router-side actions, the fixture stage's late-join
// and replay clocks); wave 3a mounts the real courtroom. The integrator mounts `CourtTab(model)` as the Court tab body
// in `MainTabScreen`.
//
// Android layout: the tab body ends above the tab bar, but the iOS court is laid out in the full screen (under the
// status bar and the translucent tab bar). `CourtFullScreen` measures the body that much taller (drawn under the
// paper-white tab bar, which MainTabScreen draws after it) and hands the scene the same insets iOS reads.
package app.plead.android.features.court

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import app.plead.android.app.AppModel
import app.plead.android.app.AppRouter
import app.plead.android.app.AppSheet
import app.plead.android.app.AppTab
import app.plead.android.app.DemoHarness
import app.plead.android.courtroom.CourtEntranceMode
import app.plead.android.courtroom.CourtFixtures
import app.plead.android.courtroom.CourtInsets
import app.plead.android.courtroom.CourtroomActions
import app.plead.android.courtroom.CourtroomEmptyState
import app.plead.android.courtroom.CourtroomLogic
import app.plead.android.courtroom.CourtroomScene
import app.plead.android.courtroom.CourtroomState
import app.plead.android.designsystem.PleadColor
import app.plead.android.designsystem.awBackground
import app.plead.android.models.Case
import app.plead.android.models.CaseStatus
import app.plead.android.models.JudgePersona
import app.plead.android.models.Profile
import app.plead.android.models.Role
import app.plead.android.models.Settlement
import app.plead.android.models.TrialPhase
import app.plead.android.services.CaseStore
import app.plead.android.services.PreviewData
import app.plead.android.services.WidgetSnapshotStore
import app.plead.android.services.court
import app.plead.android.services.isRevealed
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.delay

/** The `CaseStore` members the Court tab's decision logic reads (Swift `@Environment(CaseStore.self)`). */
interface CourtTabStore {
    val me: Profile?
    val partner: Profile?

    /** The case in trial / deliberating / awaiting verdict / verdict, if any. */
    val courtroomCase: Case?

    /** Closed cases, newest first. */
    val closedCases: List<Case>
    fun caseById(id: UUID): Case?
    fun settlement(caseId: UUID): Settlement?

    /** An offer waits for me on this case (the response sheet, not the room). */
    fun pendingSettlementForMe(caseId: UUID): Boolean
}

/** The Court tab's decision logic and router actions (Swift `CourtTabView` members). */
object CourtTabView {
    /** A trial that just settled counts for this long, so the Court tab doesn't blink to empty. */
    val recentlySettledWindow: Duration = Duration.ofHours(12)

    /** Explicitly routed case (push / Home button) if it's still in the courtroom; else the latest. */
    fun kase(store: CourtTabStore, courtCaseId: UUID?, now: Instant = Instant.now()): Case? {
        if (courtCaseId != null) {
            val c = store.caseById(courtCaseId)
            if (c != null && (c.status.isInCourtroom || c.status == CaseStatus.closedSettled)) return c
        }
        return store.courtroomCase ?: recentlySettledTrialCase(store, now)
    }

    /** A trial that just settled (within the last 12 hours), so the Court tab doesn't blink to empty. */
    fun recentlySettledTrialCase(store: CourtTabStore, now: Instant = Instant.now()): Case? =
        store.closedCases.firstOrNull { c ->
            c.status == CaseStatus.closedSettled && store.settlement(c.id)?.entryPoint == "trial" &&
                (c.closedAt ?: c.updatedAt).isAfter(now.minus(recentlySettledWindow))
        }

    /** Amendment ac: the shared court entrance plays once per case (debug: `AWCourtEntrance`, see CourtFixtureStage). */
    val entranceMode: CourtEntranceMode get() = CourtFixtureStage.entranceMode

    /** Swift `state(_:me:partner:now:)`: the courtroom's input for a live case. */
    fun state(store: CaseStore, kase: Case, me: Profile, partner: Profile, now: Instant): CourtroomState {
        val exhibits = store.exhibits(kase.id)
        val turns = store.turns(kase.id)
        val ids = exhibits.map { it.id }.toSet()
        return CourtroomState(
            kase = kase,
            turns = turns,
            exhibits = exhibits,
            me = me,
            partner = partner,
            myRole = kase.role(me.id),
            exhibitURLs = store.exhibitURLs.filterKeys { it in ids },
            now = now,
            verdict = if (kase.isRevealed) store.verdict(kase.id) else null,
            judgePersona = store.couple?.judgePersona ?: JudgePersona.wigsworth,
            deliberationProgress = kase.panelProgress,
            judgement = if (kase.isRevealed) store.judgement(kase.id) else null,
            settlement = store.settlement(kase.id),
            settlementOffer = store.latestOffer(kase.id),
            canProposeSettlement = store.canProposeSettlement(kase.id),
            // Amendment ac: the other side's podium stays empty until they have joined (spoken in the trial).
            presentRoles = CourtroomLogic.presentRoles(kase = kase, turns = turns, myRole = kase.role(me.id)),
        )
    }

    /** Swift `actions`: the store calls and the router actions for the case on stage. */
    fun actions(store: CaseStore, router: AppRouter, caseId: () -> UUID?): CourtroomActions = CourtroomActions(
        submitTurn = { body, exhibitId ->
            caseId()?.let { store.submitTurn(caseId = it, body = body, exhibitId = exhibitId) }
        },
        raiseObjection = { exhibitId, reason ->
            caseId()?.let { store.raiseObjection(caseId = it, exhibitId = exhibitId, reason = reason) }
        },
        // Amendment j: the verdict sequence's CHOOSE JUDGEMENT presents screen B as a root sheet.
        chooseJudgement = { chooseJudgement(router, caseId()) },
        respondJudgement = { accept -> caseId()?.let { store.respondJudgement(caseId = it, accept = accept) } },
        markServed = { caseId()?.let { store.markServed(caseId = it) } },
        // Amendment n: propose → the Settlement Room; open → the response sheet when an offer waits for me, else the
        // room (its waiting / withdraw view).
        proposeSettlement = { proposeSettlement(router, caseId()) },
        openSettlement = { openSettlement(store.court, router, caseId()) },
        backToDocket = { backToDocket(router) },
    )

    // MARK: Router-side actions

    /** Amendment j: the verdict sequence's CHOOSE JUDGEMENT presents screen B as a root sheet. */
    fun chooseJudgement(router: AppRouter, caseId: UUID?) {
        caseId ?: return
        router.sheet = AppSheet.chooseJudgement(caseId)
    }

    /** Amendment n: propose → the Settlement Room. */
    fun proposeSettlement(router: AppRouter, caseId: UUID?) {
        caseId ?: return
        router.sheet = AppSheet.settlementRoom(caseId)
    }

    /** Open → the response sheet when an offer waits for me, else the room (its waiting / withdraw view). */
    fun openSettlement(store: CourtTabStore, router: AppRouter, caseId: UUID?) {
        caseId ?: return
        router.sheet = if (store.pendingSettlementForMe(caseId)) AppSheet.settlementResponse(caseId) else AppSheet.settlementRoom(caseId)
    }

    fun backToDocket(router: AppRouter) {
        router.courtCaseId = null
        router.tab = AppTab.cases
    }
}

/** Height of `PleadTabBar` above the navigation bar (49 dp row + the 0.5 dp hairline), which the court draws under. */
object CourtTabLayout {
    val tabBarHeight = 49.5.dp
}

/**
 * The Court tab body, for `MainTabScreen` (`AppTab.court -> CourtTab(model)`): the courtroom for the case in court,
 * the closed court otherwise, or a debug fixture stage.
 */
@Composable
fun CourtTab(model: AppModel, modifier: Modifier = Modifier) {
    CourtTabView(store = model.store, router = model.router, modifier = modifier)
}

/** Court tab. Debug builds with `AWCourtFixture` show a fixed fixture stage instead of the live court. */
@Composable
fun CourtTabView(store: CaseStore, router: AppRouter, modifier: Modifier = Modifier) {
    CourtFullScreen(modifier) { insets ->
        val fixture = CourtFixtureStage.requested
        if (fixture != null) {
            CourtFixtureStage(name = fixture, insets = insets)
        } else {
            CourtTabLive(store, router, insets)
        }
    }
}

/**
 * Lays [content] out in the full screen: the body measured `CourtTabLayout.tabBarHeight` + the navigation bar taller
 * than the space it is given (drawn under the tab bar), with the status bar and that bleed as `CourtInsets`.
 */
@Composable
fun CourtFullScreen(modifier: Modifier = Modifier, content: @Composable (CourtInsets) -> Unit) {
    val density = LocalDensity.current
    val top = WindowInsets.statusBars.getTop(density) / density.density
    val bleed = CourtTabLayout.tabBarHeight.value + WindowInsets.navigationBars.getBottom(density) / density.density
    Box(
        modifier
            .layout { measurable, constraints ->
                val extra = bleed.dp.roundToPx()
                val w = constraints.maxWidth
                val h = constraints.maxHeight
                val p = measurable.measure(Constraints.fixed(w, h + extra))
                layout(w, h) { p.place(0, 0) }
            }
            .fillMaxSize()
            .background(PleadColor.courtBackdrop),
    ) {
        content(CourtInsets(top = top, bottom = bleed))
    }
}

@Composable
private fun CourtTabLive(store: CaseStore, router: AppRouter, insets: CourtInsets) {
    // `TimelineView(.periodic(from: .now, by: 30))`: `now` drives pure logic only (deadline passed etc.); the dock
    // countdown ticks itself.
    val now by produceState(Instant.now()) {
        while (true) {
            delay(30_000)
            value = Instant.now()
        }
    }
    val kase = CourtTabView.kase(store.court, router.courtCaseId, now)
    val me = store.me
    val partner = store.partner
    if (kase != null && me != null && partner != null) {
        key(kase.id) {
            val caseId = kase.id
            val actions = remember(store, router, caseId) { CourtTabView.actions(store, router) { caseId } }
            CourtroomScene(
                state = CourtTabView.state(store, kase, me, partner, now),
                actions = actions,
                entrance = CourtTabView.entranceMode,
                insets = insets,
            )
            // Widgets / the court-session notification: a revealed verdict shown in court counts as opened.
            LaunchedEffect("${kase.id}-${kase.status.rawValue}") {
                if (kase.isRevealed) WidgetSnapshotStore.shared.markVerdictOpened(kase.id)
            }
        }
    } else {
        CourtroomEmptyState(modifier = Modifier.fillMaxSize().awBackground(), insets = insets)
    }
}

/**
 * Motion captures (amendment x): `AWCourtFixture opening|evidence|cross|ruling|deliberation|verdict|replay` puts the
 * Court tab on a fixed `CourtFixtures` state (Aria v Sam). `replay` plays the record one turn at a time
 * (`AWCourtReplayFrom 3`, `AWCourtReplayStep 1.6`). Debug builds only (DemoHarness returns null in release).
 *
 * Entrance captures (amendment ac): `AWCourtEntrance replay` plays the shared court entrance on every open (live court
 * or fixture); `late` also replays, with the defendant absent until ~3.2 s in, when they walk in; `<seconds>` (e.g.
 * `0.1`, `0.6`, `1.3`, `2.2`) freezes it that far in for stills. Case call (amendment ad): `replay` / `late` also call
 * the case after the entrance; `caseCall` holds the ready court on the NOW HEARING card, `introduction` on the judge's
 * fully revealed introduction (stills; taps ignored).
 */
object CourtFixtureStage {
    val requested: String? get() = DemoHarness.courtFixture
    val entranceFlag: String? get() = DemoHarness.courtEntrance

    val entranceMode: CourtEntranceMode
        get() {
            val f = entranceFlag ?: return CourtEntranceMode.auto
            if (f == "replay" || f == "late") return CourtEntranceMode.replay
            if (f == "caseCall") return CourtEntranceMode.holdCaseCall
            if (f == "introduction") return CourtEntranceMode.holdIntroduction
            f.toDoubleOrNull()?.let { return CourtEntranceMode.hold(it) }
            return CourtEntranceMode.auto
        }

    /** `AWCourtEntrance <seconds>`: the hold time, when the flag is a number (Swift `TimeInterval(f)`). */
    val entranceHoldSeconds: Double? get() = entranceFlag?.toDoubleOrNull()

    /** The replay's first turn count: `AWCourtReplayFrom` when > 0, else 3. */
    val replayFrom: Int get() = DemoHarness.courtReplayFrom.let { if (it > 0) it else 3 }

    /** Seconds between replayed turns: `AWCourtReplayStep` when > 0, else 1.6. */
    val replayStep: Double get() = DemoHarness.courtReplayStep.let { if (it > 0) it else 1.6 }

    /** The fixture names mapped to `CourtFixtures` states (anything else = replay). */
    val fixtureNames = listOf("opening", "evidence", "cross", "ruling", "deliberation", "verdict", "replay")

    /** Swift `state`: the fixed state for [name] (`count` = the replay's turns so far). */
    fun state(name: String, count: Int): CourtroomState = when (name) {
        "opening" -> CourtFixtures.state(TrialPhase.plaintiffOpening, owner = Role.plaintiff, turns = 1)
        "evidence" -> CourtFixtures.state(TrialPhase.plaintiffExhibits, owner = Role.defendant, turns = 6)
        "cross" -> CourtFixtures.crossExam
        "ruling" -> CourtFixtures.state(TrialPhase.plaintiffExhibits, owner = Role.plaintiff, turns = 8)
        "deliberation" -> CourtFixtures.deliberating
        "verdict" -> CourtFixtures.verdictIn
        else -> replayState(count)
    }

    /** replay: the phase of the newest turn; the floor with the side that speaks next. */
    fun replayState(count: Int): CourtroomState {
        val turns = CourtFixtures.allTurns.take(count)
        val phase = turns.lastOrNull()?.phase ?: TrialPhase.plaintiffOpening
        val next = CourtFixtures.allTurns.drop(count).firstOrNull()
        val owner: Role = next?.let { CourtroomLogic.role(it.speaker) } ?: phase.speakingSide ?: Role.plaintiff
        return CourtFixtures.state(phase, owner = owner, turns = count)
    }

    /** `AWCourtEntrance late`: the defendant has not joined until the late task flips. */
    fun lateState(s: CourtroomState, lateJoined: Boolean): CourtroomState =
        if (entranceFlag == "late" && !lateJoined) s.copy(presentRoles = setOf(Role.plaintiff)) else s
}

/** The fixture stage (`CourtFixtures`, no-op actions). */
@Composable
fun CourtFixtureStage(name: String, modifier: Modifier = Modifier, insets: CourtInsets = CourtInsets.zero) {
    var count by remember { mutableIntStateOf(CourtFixtureStage.replayFrom) }
    var lateJoined by remember { mutableStateOf(false) }
    LaunchedEffect("late") {
        if (CourtFixtureStage.entranceFlag != "late") return@LaunchedEffect
        delay(3_200)
        lateJoined = true
    }
    LaunchedEffect(name) {
        if (name != "replay") return@LaunchedEffect
        while (count < CourtFixtures.allTurns.size - 1) {
            delay((CourtFixtureStage.replayStep * 1000).toLong())
            count += 1
        }
    }
    CourtroomScene(
        state = CourtFixtureStage.lateState(CourtFixtureStage.state(name, count), lateJoined),
        actions = CourtroomActions.noop,
        modifier = modifier,
        entrance = CourtFixtureStage.entranceMode,
        insets = insets,
    )
}

@Preview(name = "Court · trial", widthDp = 402, heightDp = 874)
@Composable
private fun CourtTabTrialPreview() {
    CourtTabView(store = remember { PreviewData.store() }, router = remember { AppRouter() })
}

@Preview(name = "Court · empty", widthDp = 402, heightDp = 874)
@Composable
private fun CourtTabEmptyPreview() {
    CourtTabView(store = remember { PreviewData.emptyStore() }, router = remember { AppRouter() })
}
