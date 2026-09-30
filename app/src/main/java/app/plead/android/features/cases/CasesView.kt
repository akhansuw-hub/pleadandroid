// Port of ArgueWin/Features/Cases/CasesView.swift.
//
// The docket (CONTRACTS-v2 amendment af): "The docket", Open / Closed with counts derived from the cases,
// every file a `CaseFileCard`. Open: action-required first (nearest deadline first), then waiting /
// scheduling / deliberating. Closed: verdicts and settlements, newest first, each with its judgement
// fulfilment slip or settlement status row hanging underneath. No win–loss record here (it lives on Us).
//
// Also `CasesTab` (the Cases tab's stack: the docket with case records pushed on top), `CourtFileButtonStyle`,
// `ClosedCourtFileEntry` and `SettlementDocketSlip`.
package app.plead.android.features.cases

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.plead.android.app.AppModel
import app.plead.android.app.AppRouter
import app.plead.android.app.AppSheet
import app.plead.android.app.AppTab
import app.plead.android.app.DemoHarness
import app.plead.android.app.TabNavHost
import app.plead.android.designsystem.CaseFileAction
import app.plead.android.designsystem.CaseFileCard
import app.plead.android.designsystem.CaseFileDocket
import app.plead.android.designsystem.CaseFileRow
import app.plead.android.designsystem.CaseFileStatus
import app.plead.android.designsystem.CourtFilePalette
import app.plead.android.designsystem.CourtTabHeader
import app.plead.android.designsystem.PleadColor
import app.plead.android.designsystem.PleadMotion
import app.plead.android.designsystem.PleadRadius
import app.plead.android.designsystem.PleadSpacing
import app.plead.android.designsystem.PleadType
import app.plead.android.designsystem.ScalesMark
import app.plead.android.designsystem.accessibilityReduceMotion
import app.plead.android.features.casedetail.CaseDetailView
import app.plead.android.features.casedetail.JudgementFulfilmentSlipSlot
import app.plead.android.features.casedetail.SegmentedPicker
import app.plead.android.features.casedetail.SettlementStatusRowSlot
import app.plead.android.models.Case
import app.plead.android.models.CaseStatus
import app.plead.android.services.CaseStore
import app.plead.android.services.caseFileAction
import app.plead.android.services.caseFileParties
import app.plead.android.services.caseFileStatus
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Cases tab: the docket with case records pushed on top (iOS `NavigationStack(path: $router.casesPath)`).
 * The integrator mounts this in place of the Cases `TabNavHost` in `MainTabScreen`.
 */
@Composable
fun CasesTab(model: AppModel, modifier: Modifier = Modifier) {
    val router = model.router
    val store = model.store
    Box(modifier.fillMaxSize()) {
        TabNavHost(
            path = router.casesPath,
            onPathChange = { router.casesPath = it },
            root = { CasesView(store, router) },
            caseDetail = { id ->
                CaseDetailView(id, store, router, modifier = Modifier.statusBarsPadding(), backTitle = "Cases") {
                    router.casesPath = router.casesPath.dropLast(1)
                }
            },
        )
    }
}

/** Statics of the docket (Swift `CasesView` nested types). */
object CasesView {
    enum class DocketSection(val rawValue: String) {
        `open`("open"), closed("closed");

        val id: String get() = rawValue
        val title: String get() = if (this == open) "Open" else "Closed"

        /** "Open 4", "Closed 8". */
        fun label(count: Int): String = "$title $count"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CasesView(store: CaseStore, router: AppRouter, modifier: Modifier = Modifier) {
    var section by rememberSaveable { mutableStateOf(CasesView.DocketSection.open) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val counts = CaseFileDocket.counts(store.cases)

    // Demo harness: `AWDocketFilter closed` opens on the Closed files (older values
    // all|won|lost|tied|settled also mean Closed now that the outcome filter is gone).
    LaunchedEffect(Unit) {
        DemoHarness.docketFilter?.let { section = if (it == "open") CasesView.DocketSection.open else CasesView.DocketSection.closed }
        SettlementDocket.openCaseIfAsked(router, store)
        SettlementDocket.dismissLaunchPromptIfAsked(router)
    }

    Box(modifier.fillMaxSize().background(CourtFilePalette.background)) {
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { refreshing = true; store.refresh(); refreshing = false } },
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
        ) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = PleadSpacing.xxl + PleadSpacing.xl)) {
                CourtTabHeader(title = "The docket")

                SegmentedPicker(
                    options = listOf(
                        CasesView.DocketSection.open.label(counts.open),
                        CasesView.DocketSection.closed.label(counts.closed),
                    ),
                    selected = section.ordinal,
                    onSelect = { section = CasesView.DocketSection.entries[it] },
                    modifier = Modifier
                        .testTag("docket.sections")
                        .padding(horizontal = CourtTabHeader.gutter)
                        .padding(top = PleadSpacing.s, bottom = PleadSpacing.l),
                )

                val error = store.loadError
                if (error != null && store.cases.isEmpty()) {
                    DocketNotice("The docket couldn't load", error, "Try again" to { scope.launch { store.refresh() } })
                } else if (!store.hasLoaded && store.cases.isEmpty()) {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = PleadSpacing.xxl),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(PleadSpacing.s),
                    ) {
                        CircularProgressIndicator(color = CourtFilePalette.burgundy, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                        Text("Opening the docket…", style = PleadType.body, color = PleadColor.subtleText)
                    }
                } else {
                    Crossfade(section, animationSpec = tween(200, easing = PleadMotion.easeInOut), label = "docketSection") { s ->
                        when (s) {
                            CasesView.DocketSection.open -> OpenList(store, router)
                            CasesView.DocketSection.closed -> ClosedList(store, router)
                        }
                    }
                }
            }
        }
    }
}

// MARK: Open

@Composable
private fun OpenList(store: CaseStore, router: AppRouter) {
    val open = store.cases.filter { it.status.isOpen }
    if (open.isEmpty()) {
        if (store.isSolo) {
            DocketNotice("No open cases", "Invite your partner to open the docket.", "Summon your partner" to { router.sheet = AppSheet.invite })
        } else {
            DocketNotice("No open cases", "When one of you brings a case, it is filed here.", "Bring a new case" to {
                // Same route as Home: the open-case cap is explained there.
                if (store.filingBlocker != null) router.tab = AppTab.home else router.sheet = AppSheet.fileCase
            })
        }
        return
    }
    // `TimelineView(.everyMinute)`.
    val now by produceState(Instant.now()) {
        while (true) {
            delay(60_000 - (System.currentTimeMillis() % 60_000))
            value = Instant.now()
        }
    }
    val rows = CaseFileDocket.sortOpen(open.map { CaseFileRow(it, store.caseFileStatus(it, now)) })
    Column(Modifier.padding(horizontal = CourtTabHeader.gutter), verticalArrangement = Arrangement.spacedBy(PleadSpacing.m)) {
        rows.forEach { row -> OpenCard(row.kase, row.status, store, router) }
    }
}

@Composable
private fun OpenCard(kase: Case, status: CaseFileStatus, store: CaseStore, router: AppRouter) {
    val action = store.caseFileAction(kase)
    CaseFileCard(
        number = kase.caseNumber,
        title = kase.title,
        parties = store.caseFileParties(kase),
        status = status,
        me = store.me?.avatar,
        partner = store.opponentId(kase)?.let { store.profile(it) }?.avatar,
        myRole = store.myRole(kase),
        action = action?.let { a -> CaseFileAction(if (status.nextStep.isEmpty()) a.title else status.nextStep) { router.open(a, kase) } },
        detail = SettlementDocket.pendingChip(kase, store.settlement(kase.id)),
        onOpen = {
            // Tap on an open file: its record, or (amendment n) the settlement sheet while an offer is pending.
            val route = SettlementDocket.route(kase, store.settlement(kase.id), pendingForMe = store.pendingSettlementForMe(kase.id))
            if (route == SettlementDocket.RowRoute.record) router.casesPath = router.casesPath + kase.id else router.openSettlement(route, kase.id)
        },
    )
}

// MARK: Closed

@Composable
private fun ClosedList(store: CaseStore, router: AppRouter) {
    val closed = CaseFileDocket.sortClosed(store.cases.filter { it.status.isClosed })
    if (closed.isEmpty()) {
        DocketNotice("No closed cases", "Every ruling and settlement lands here.", null)
        return
    }
    Column(Modifier.padding(horizontal = CourtTabHeader.gutter), verticalArrangement = Arrangement.spacedBy(PleadSpacing.m)) {
        closed.forEach { ClosedCourtFileEntry(it, store, router) }
    }
}

// MARK: Empty / notices

@Composable
private fun DocketNotice(title: String, body: String, action: Pair<String, () -> Unit>?) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = PleadSpacing.xxl).padding(horizontal = CourtTabHeader.gutter),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PleadSpacing.s),
    ) {
        ScalesMark(size = 40.dp, color = CourtFilePalette.mahogany)
        Text(title, style = PleadType.displayM, color = CourtFilePalette.wine, textAlign = TextAlign.Center)
        Text(body, style = PleadType.body, color = CourtFilePalette.mahogany, textAlign = TextAlign.Center)
        if (action != null) {
            Box(
                Modifier
                    .defaultMinSize(minHeight = 44.dp)
                    .testTag("docket.empty.action")
                    .clickable(onClick = action.second),
                contentAlignment = Alignment.Center,
            ) {
                Text(action.first, style = PleadType.uiButtonSecondary, color = CourtFilePalette.burgundy)
            }
        }
    }
}

/** Swift `CourtFileButtonStyle`: subtle press feedback for full-card links (scale on press-in, no highlight flash). */
@Composable
fun Modifier.courtFileButtonStyle(onClick: () -> Unit, onClickLabel: String? = null): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduceMotion = accessibilityReduceMotion()
    val scale by animateFloatAsState(if (pressed && !reduceMotion) 0.98f else 1f, tween(120, easing = PleadMotion.easeOut), label = "courtFilePressScale")
    val alpha by animateFloatAsState(if (pressed) 0.92f else 1f, tween(120, easing = PleadMotion.easeOut), label = "courtFilePressAlpha")
    return this
        .scale(scale)
        .alpha(alpha)
        .clickable(interactionSource = interaction, indication = null, onClickLabel = onClickLabel, onClick = onClick)
}

/**
 * A closed case on the docket: its case file, with the judgement's fulfilment slip hanging underneath
 * (amendment l), or, settled out of court (amendment n), the settlement's status row (no judgement).
 * Tap opens the record.
 */
@Composable
fun ClosedCourtFileEntry(kase: Case, store: CaseStore, router: AppRouter, modifier: Modifier = Modifier) {
    Column(modifier.testTag("docket.closedRow")) {
        CaseFileCard(
            number = kase.caseNumber,
            title = kase.title,
            parties = store.caseFileParties(kase),
            status = store.caseFileStatus(kase),
            me = store.me?.avatar,
            partner = store.opponentId(kase)?.let { store.profile(it) }?.avatar,
            myRole = store.myRole(kase),
            detail = if (kase.status == CaseStatus.closedSettled) null else store.outcomeLine(kase),
            onOpen = { router.casesPath = router.casesPath + kase.id },
        )
        if (kase.status == CaseStatus.closedSettled) {
            store.settlement(kase.id)?.let { settlement ->
                SettlementDocketSlip { SettlementStatusRowSlot(settlement, store.latestOffer(kase.id), Instant.now()) }
            }
        } else {
            store.judgement(kase.id)?.let { JudgementFulfilmentSlipSlot(store, kase, it) }
        }
    }
}

/** The slip under a settled court file: parchment-on-paper with a walnut rule (not the verdict's gold). */
@Composable
fun SettlementDocketSlip(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(bottomStart = PleadRadius.tile, bottomEnd = PleadRadius.tile)
    Box(
        modifier
            .padding(horizontal = PleadSpacing.m)
            .fillMaxWidth()
            .clip(shape)
            .background(PleadColor.paperWhite, shape)
            .border(1.dp, PleadColor.walnut.copy(alpha = 0.18f), shape),
    ) {
        Box(Modifier.padding(start = PleadSpacing.m + 3.dp, end = PleadSpacing.m, top = PleadSpacing.s + 2.dp, bottom = PleadSpacing.s + 2.dp)) {
            content()
        }
        Box(Modifier.matchParentSize()) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(PleadColor.walnut.copy(alpha = 0.7f)))
        }
    }
}

