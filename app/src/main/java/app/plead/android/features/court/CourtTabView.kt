// Port of ArgueWin/Features/Court/CourtTabView.swift: the Court tab HOST (wave 2b).
//
// Court tab: mounts the courtroom's `CourtroomScene` for the case in trial / deliberating / awaiting_verdict /
// verdict, else `CourtroomEmptyState`. Amendment n: a case settled out of court stays on stage when routed here,
// or when it settled from the trial and nothing else is in court (the judge's flavour line, the seal, "Back to
// docket").
//
// Wave 2b ports the decision logic (which case, fixture vs live, the router-side actions, the fixture stage's
// late-join and replay clocks). Wave 3a (courtroom/) replaces the two clearly labelled placeholders below:
//   • `CourtroomScenePlaceholder(...)`  → `CourtroomScene(state = state(kase, me, partner, now), actions, entrance)`
//     with `CourtroomState` built as in `CourtTabView.swift` `state(_:me:partner:now:)`, `CourtroomActions` from
//     the store calls + the router actions in `CourtTabView` below, the `WidgetSnapshotStore.markVerdictOpened`
//     task when `kase.isRevealed`, and `entranceMode` (`CourtFixtureStage.entranceMode`, see its doc).
//   • `CourtroomEmptyStatePlaceholder()` → `CourtroomEmptyState()` (Courtroom/CourtTranscript.swift).
//   • `CourtFixturePlaceholder(...)`    → `CourtroomScene(state = CourtFixtures…, actions = .noop, entrance)`.
// After the wave-2a merge `CaseStore` implements `CourtTabStore` (every member already exists on it).
package app.plead.android.features.court

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.plead.android.app.AppRouter
import app.plead.android.app.AppSheet
import app.plead.android.app.AppTab
import app.plead.android.app.DemoHarness
import app.plead.android.designsystem.PleadColor
import app.plead.android.designsystem.PleadType
import app.plead.android.designsystem.awBackground
import app.plead.android.models.Case
import app.plead.android.models.CaseStatus
import app.plead.android.models.Profile
import app.plead.android.models.Settlement
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.delay

/** The `CaseStore` members the Court tab reads (Swift `@Environment(CaseStore.self)`). */
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

    // MARK: Router-side actions (the store-side ones — submitTurn, raiseObjection, respondJudgement, markServed —
    // are wired by wave 3a into `CourtroomActions`).

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

/** Court tab. Debug builds with `AWCourtFixture` show a fixed fixture stage instead of the live court. */
@Composable
fun CourtTabView(store: CourtTabStore, router: AppRouter, modifier: Modifier = Modifier) {
    val fixture = CourtFixtureStage.requested
    if (fixture != null) {
        CourtFixtureStage(name = fixture, modifier = modifier)
    } else {
        CourtTabLive(store, router, modifier)
    }
}

@Composable
private fun CourtTabLive(store: CourtTabStore, router: AppRouter, modifier: Modifier) {
    // `TimelineView(.periodic(from: .now, by: 30))`: `now` drives pure logic only (deadline passed etc.); the dock
    // countdown ticks itself.
    val now by produceState(Instant.now()) {
        while (true) {
            delay(30_000)
            value = Instant.now()
        }
    }
    val kase = CourtTabView.kase(store, router.courtCaseId, now)
    val me = store.me
    val partner = store.partner
    if (kase != null && me != null && partner != null) {
        key(kase.id) {
            CourtroomScenePlaceholder(kase = kase, me = me, partner = partner, now = now, modifier = modifier)
        }
    } else {
        CourtroomEmptyStatePlaceholder(modifier.fillMaxSize().awBackground())
    }
}

/**
 * Motion captures (amendment x): `AWCourtFixture opening|evidence|cross|ruling|deliberation|verdict|replay` puts the
 * Court tab on a fixed `CourtFixtures` state (Aria v Sam). `replay` plays the record one turn at a time
 * (`AWCourtReplayFrom 3`, `AWCourtReplayStep 1.6`). Debug builds only (DemoHarness returns null in release).
 *
 * Entrance captures (amendment ac): `AWCourtEntrance replay` plays the shared court entrance on every open;
 * `late` also replays, with the defendant absent until ~3.2 s in; `<seconds>` freezes it that far in. Case call
 * (amendment ad): `caseCall` holds the NOW HEARING card, `introduction` the judge's introduction. The mapping to
 * 3a's `CourtEntranceMode` (Swift `CourtFixtureStage.entranceMode`):
 *   null → auto · "replay" / "late" → replay · "caseCall" → holdCaseCall · "introduction" → holdIntroduction ·
 *   a number → hold(seconds) · anything else → auto.
 */
object CourtFixtureStage {
    val requested: String? get() = DemoHarness.courtFixture
    val entranceFlag: String? get() = DemoHarness.courtEntrance

    /** `AWCourtEntrance <seconds>`: the hold time, when the flag is a number (Swift `TimeInterval(f)`). */
    val entranceHoldSeconds: Double? get() = entranceFlag?.toDoubleOrNull()

    /** The replay's first turn count: `AWCourtReplayFrom` when > 0, else 3. */
    val replayFrom: Int get() = DemoHarness.courtReplayFrom.let { if (it > 0) it else 3 }

    /** Seconds between replayed turns: `AWCourtReplayStep` when > 0, else 1.6. */
    val replayStep: Double get() = DemoHarness.courtReplayStep.let { if (it > 0) it else 1.6 }

    /** Swift `state` switch: the fixture names 3a maps to `CourtFixtures` states (anything else = replay). */
    val fixtureNames = listOf("opening", "evidence", "cross", "ruling", "deliberation", "verdict", "replay")
}

/**
 * The fixture stage. [totalTurns] is `CourtFixtures.allTurns.size` (wave 3a); until then the replay clock has no
 * record to step through and holds at its first count.
 */
@Composable
fun CourtFixtureStage(name: String, modifier: Modifier = Modifier, totalTurns: Int? = null) {
    var count by remember { mutableIntStateOf(CourtFixtureStage.replayFrom) }
    var lateJoined by remember { mutableStateOf(false) }
    LaunchedEffect("late") {
        if (CourtFixtureStage.entranceFlag != "late") return@LaunchedEffect
        delay(3_200)
        lateJoined = true
    }
    LaunchedEffect(name) {
        if (name != "replay" || totalTurns == null) return@LaunchedEffect
        while (count < totalTurns - 1) {
            delay((CourtFixtureStage.replayStep * 1000).toLong())
            count += 1
        }
    }
    // `AWCourtEntrance late`: the defendant has not joined until the late task flips (presentRoles = [.plaintiff]).
    val defendantPresent = CourtFixtureStage.entranceFlag != "late" || lateJoined
    CourtFixturePlaceholder(name = name, count = count, defendantPresent = defendantPresent, modifier = modifier)
}

// MARK: - Placeholders (wave 3a replaces these)

/** PLACEHOLDER for `CourtroomScene` (wave 3a). */
@Composable
fun CourtroomScenePlaceholder(kase: Case, me: Profile, partner: Profile, now: Instant, modifier: Modifier = Modifier) {
    CourtPlaceholder(
        title = kase.title,
        lines = listOf("CourtroomScene · ${kase.status.rawValue}", "${me.displayName} v. ${partner.displayName}", "Port wave 3a"),
        modifier = modifier,
    )
}

/** PLACEHOLDER for `CourtroomEmptyState` (wave 3a). */
@Composable
fun CourtroomEmptyStatePlaceholder(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Court", style = PleadType.displayL, color = PleadColor.text)
            Text("CourtroomEmptyState · Port wave 3a", style = PleadType.metadata, color = PleadColor.subtleText)
        }
    }
}

/** PLACEHOLDER for the fixture `CourtroomScene` (wave 3a). */
@Composable
fun CourtFixturePlaceholder(name: String, count: Int, defendantPresent: Boolean, modifier: Modifier = Modifier) {
    CourtPlaceholder(
        title = "Fixture: $name",
        lines = listOf(
            "turns $count · defendant ${if (defendantPresent) "present" else "absent"}",
            "entrance ${CourtFixtureStage.entranceFlag ?: "auto"}",
            "Port wave 3a",
        ),
        modifier = modifier,
    )
}

@Composable
private fun CourtPlaceholder(title: String, lines: List<String>, modifier: Modifier) {
    Box(modifier.fillMaxSize().background(PleadColor.courtBackdrop).padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = PleadType.displayM, color = PleadColor.cream, textAlign = TextAlign.Center)
            lines.forEach { Text(it, style = PleadType.metadata, color = PleadColor.cream.copy(alpha = 0.7f), textAlign = TextAlign.Center) }
        }
    }
}

@Preview(name = "Court · empty", widthDp = 402, heightDp = 700)
@Composable
private fun CourtTabEmptyPreview() {
    val store = remember {
        object : CourtTabStore {
            override val me: Profile? = null
            override val partner: Profile? = null
            override val courtroomCase: Case? = null
            override val closedCases: List<Case> = emptyList()
            override fun caseById(id: UUID): Case? = null
            override fun settlement(caseId: UUID): Settlement? = null
            override fun pendingSettlementForMe(caseId: UUID): Boolean = false
        }
    }
    CourtTabView(store = store, router = remember { AppRouter() })
}
