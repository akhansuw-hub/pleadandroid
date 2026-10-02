// Port of ArgueWin/Features/CaseDetail/CaseDetailView.swift.
//
// Read-only record: deliberation state or verdict, filings, exhibits, transcript. Also `TranscriptRow`, `VerdictCard`,
// `PanelSection`, `ExhibitDetailSheet`, `RecordLabel` and the case-side type tokens `CaseType`.
//
// SwiftUI → Compose: the NavigationStack bar is [CaseNavBar] (back chevron + the docket number as the principal
// title); `.refreshable` is a pull-to-refresh box; `.sheet(item:)` is [CaseSheetHost]; `LazyVGrid(.adaptive(100))`
// is laid out row by row inside the scrolled column (a lazy grid cannot nest in a scroll).
package app.plead.android.features.casedetail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.plead.android.app.AppRouter
import app.plead.android.app.DemoHarness
import app.plead.android.designsystem.AvatarBadge
import app.plead.android.designsystem.Chip
import app.plead.android.designsystem.ClosedStamp
import app.plead.android.designsystem.ExhibitTile
import app.plead.android.designsystem.InlineError
import app.plead.android.designsystem.PhaseChip
import app.plead.android.designsystem.PleadColor
import app.plead.android.designsystem.PleadRadius
import app.plead.android.designsystem.PleadSpacing
import app.plead.android.designsystem.PleadType
import app.plead.android.designsystem.PrimaryButton
import app.plead.android.designsystem.AWButtonKind
import app.plead.android.designsystem.RoleChip
import app.plead.android.designsystem.ScalesMark
import app.plead.android.designsystem.StatusRibbon
import app.plead.android.designsystem.TextStyleKind
import app.plead.android.designsystem.WeightDots
import app.plead.android.designsystem.awBackground
import app.plead.android.designsystem.awCard
import app.plead.android.designsystem.awCourtFile
import app.plead.android.designsystem.caseFilePressStyle
import app.plead.android.designsystem.italic
import app.plead.android.designsystem.monospacedDigit
import app.plead.android.features.cases.SettlementDocket
import app.plead.android.features.cases.courtFileButtonStyle
import app.plead.android.models.Case
import app.plead.android.models.CaseStatus
import app.plead.android.models.Exhibit
import app.plead.android.models.ExhibitType
import app.plead.android.models.JudgePersona
import app.plead.android.models.JurorReview
import app.plead.android.models.ObjectionRuling
import app.plead.android.models.Plea
import app.plead.android.models.Role
import app.plead.android.models.Speaker
import app.plead.android.models.Turn
import app.plead.android.models.Verdict
import app.plead.android.models.VerdictKind
import app.plead.android.services.CaseAction
import app.plead.android.services.CaseStore
import app.plead.android.models.EdgeError
import app.plead.android.services.docketNumber
import app.plead.android.services.isRevealed
import coil3.compose.SubcomposeAsyncImage
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Statics of the record screen (Swift `CaseDetailView` static members). */
object CaseDetailView {
    /** The plaintiff cannot see the defendant's evidence before the defence is filed. */
    fun defenceEvidenceSealed(kase: Case, myRole: Role?): Boolean =
        myRole == Role.plaintiff && (kase.status == CaseStatus.summoned || kase.status == CaseStatus.defence)
}

/**
 * The case record, pushed on the Home or Cases stack. [backTitle] / [onBack] draw the navigation bar's back button
 * (the tab's title, as iOS shows it).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaseDetailView(
    caseId: UUID,
    store: CaseStore,
    router: AppRouter,
    modifier: Modifier = Modifier,
    backTitle: String? = null,
    onBack: (() -> Unit)? = null,
) {
    var selectedExhibit by remember { mutableStateOf<Exhibit?>(null) }
    val kase = store.caseById(caseId)
    Column(modifier.fillMaxSize().awBackground()) {
        // Amendment u: the nav title is functional UI (SF Rounded semibold 16), not a display title.
        CaseNavBar(
            title = kase?.docketNumber,
            leading = {
                if (onBack != null) NavTextButton(backTitle ?: "Back", icon = "chevron.left", onClick = onBack)
            },
        )
        if (kase == null) {
            ContentUnavailable("Case not found", "questionmark.folder", description = "It may have been removed when the couple unlinked.")
        } else {
            CaseDetailContent(kase, store, router) { selectedExhibit = it }
        }
    }
    selectedExhibit?.let { ex -> ExhibitDetailSheet(ex, store) { selectedExhibit = null } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CaseDetailContent(kase: Case, store: CaseStore, router: AppRouter, onExhibit: (Exhibit) -> Unit) {
    val scope = rememberCoroutineScope()
    var confirmWithdraw by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val anchors = remember { ScrollAnchors() }
    var viewport by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    val plaintiff = store.name(kase.plaintiffId, fallback = "Plaintiff")
    val defendant = store.name(kase.defendantId, fallback = "Defendant")

    // Demo harness: `AWScroll panel|judgement|settlement` jumps to the juror cards / the judgement card for screenshots.
    LaunchedEffect(Unit) {
        val anchor = DemoHarness.scroll
        if (anchor != null && anchor in listOf("panel", "judgement", "settlement")) {
            delay(600)
            // Swift `scrollTo(anchor, anchor: .top)`: the card's top at the top of the visible area (under the nav bar).
            anchors.scrollTo(anchor, scroll, viewport, center = false, contentPaddingTop = with(density) { PleadSpacing.l.roundToPx() })
        }
    }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { scope.launch { refreshing = true; store.refresh(); refreshing = false } },
        modifier = Modifier.fillMaxSize().onSizeChanged { viewport = it.height },
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(PleadSpacing.l),
            verticalArrangement = Arrangement.spacedBy(PleadSpacing.xl),
        ) {
            CaseRecordHeader(kase, store, plaintiff, defendant)

            if (kase.status.isDeliberating) {
                DeliberationPanelSlot(kase, showsTitle = false)
            } else if (SettlementDocket.isPending(kase, store.settlement(kase.id))) {
                // Amendment n: the court waits while an offer is on the table.
                SettlementPendingCard(kase, store.settlement(kase.id), store.latestOffer(kase.id), store, router)
            } else if (store.me != null && kase.status.isOpen) {
                // Judgement steps live on the judgement card under the verdict; the button keeps the case's own step.
                val action = store.nextAction(kase)
                if (action != CaseAction.viewRecord && action != CaseAction.requestDefault && !action.isJudgement && !action.isSettlement) {
                    PrimaryButton(
                        action.title,
                        systemImage = "arrow.right",
                        kind = if (action.isActionable) AWButtonKind.primary else AWButtonKind.secondary,
                    ) { router.open(action, kase) }
                }
            }

            // Amendment n: settled out of court. No verdict, no judgement: the agreement instead.
            val settlement = store.settlement(kase.id)
            if (kase.status == CaseStatus.closedSettled && settlement != null) {
                Box(Modifier.scrollAnchor(anchors, "settlement")) { SettlementRecordSection(kase, settlement, store) }
            }
            val verdict = store.verdict(kase.id)
            if (kase.isRevealed && verdict != null) {
                VerdictCard(verdict, kase, store)
            }
            // Amendment l: the judgement's fulfilment block (VERDICT FINAL / Judgement / DUE · 3 DAYS)
            // with its actions, under the verdict.
            store.judgement(kase.id)?.let { judgement ->
                Box(Modifier.scrollAnchor(anchors, "judgement")) { JudgementStatusCardSlot(store, router, kase, judgement) }
            }
            if (kase.isRevealed && verdict != null) {
                val reviews = store.jurorReviews(kase.id)
                if (reviews.isNotEmpty()) Box(Modifier.scrollAnchor(anchors, "panel")) { PanelSection(reviews, kase, store) }
            }

            RecordSection("Filings") {
                Filing("$plaintiff's charge", kase.charge, Role.plaintiff)
                Filing("Requested remedy", kase.remedyRequested, Role.plaintiff)
                kase.plea?.let { plea -> Filing("Plea", if (plea == Plea.guilty) "Guilty" else "Not guilty", Role.defendant) }
                store.defenceStatement(kase.id)?.let { Filing("$defendant's defence", it, Role.defendant) }
                kase.counterClaim?.let { Filing("Counter-claim", it, Role.defendant) }
                (kase.trialAt ?: kase.proposedTrialAt)?.let { at ->
                    Filing(if (kase.trialAt != null) "Verdict reading" else "Proposed trial time", CaseDates.completeDateTime(at), null)
                }
            }

            ExhibitsSection(kase, store, kase.plaintiffId, "$plaintiff's exhibits", Role.plaintiff, onExhibit)
            ExhibitsSection(kase, store, kase.defendantId, "$defendant's exhibits", Role.defendant, onExhibit)
            if (CaseDetailView.defenceEvidenceSealed(kase, store.myRole(kase))) {
                // RLS hides the defendant's exhibits from the plaintiff until `file_defence`.
                Row(horizontalArrangement = Arrangement.spacedBy(PleadSpacing.s), verticalAlignment = Alignment.Top) {
                    Icon(symbolIcon("lock"), contentDescription = null, tint = PleadColor.subtleText, modifier = Modifier.size(15.dp))
                    Text("$defendant's exhibits stay sealed until the defence is filed.", style = PleadType.metadata, color = PleadColor.subtleText)
                }
            }

            val transcript = store.turns(kase.id).filter { it.phase != null || it.speaker == Speaker.judge }
            if (transcript.isNotEmpty()) {
                RecordSection("Transcript") {
                    Column(verticalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
                        transcript.forEach { TranscriptRow(it, kase, store) }
                    }
                }
            }

            if (kase.status.isOpen && store.myRole(kase) == Role.plaintiff &&
                kase.status in listOf(CaseStatus.summoned, CaseStatus.defence, CaseStatus.scheduling)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 44.dp)
                        .caseFilePressStyle(onClick = { if (!working) confirmWithdraw = true }),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Withdraw case", style = PleadType.uiButtonSecondary, color = PleadColor.burgundy)
                }
            }
            InlineError(error)
        }
    }

    if (confirmWithdraw) {
        CaseDialog(
            "Withdraw this case?",
            "It will be recorded as a mistrial. This can't be undone.",
            listOf(
                DialogAction("Withdraw case", destructive = true) {
                    working = true; error = null
                    scope.launch {
                        try { store.withdraw(kase) } catch (e: Exception) { error = (e as? EdgeError)?.message ?: "Couldn't reach the court." }
                        working = false
                    }
                },
            ),
            onDismiss = { confirmWithdraw = false },
        )
    }
}

@Composable
private fun CaseRecordHeader(kase: Case, store: CaseStore, plaintiff: String, defendant: String) {
    Box(Modifier.fillMaxWidth()) {
        Column(Modifier.awCourtFile(PleadSpacing.xl), verticalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
            StatusRibbon(
                SettlementDocket.ribbonTitle(kase, storeTitle = store.ribbonTitle(kase)),
                color = SettlementDocket.ribbonColor(kase),
                modifier = Modifier.offset(x = -PleadSpacing.xl),
            )
            Text(kase.docketNumber, style = PleadType.metadataMedium, color = PleadColor.walnut)
            SelectionContainer {
                Text(kase.title, style = PleadType.caseTitle, color = PleadColor.cocoa, modifier = Modifier.semantics { heading() })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(PleadSpacing.s), verticalAlignment = Alignment.CenterVertically) {
                store.profile(kase.plaintiffId)?.let { AvatarBadge(it.avatar, size = 28.dp) }
                Text("$plaintiff v. $defendant", style = PleadType.caseParties, color = PleadColor.subtleText, modifier = Modifier.weight(1f, fill = false))
                store.profile(kase.defendantId)?.let { AvatarBadge(it.avatar, size = 28.dp) }
            }
            Text(
                "Filed ${CaseDates.abbreviatedDate(kase.createdAt)}${kase.closedAt?.let { " · Closed ${CaseDates.abbreviatedDate(it)}" } ?: ""}",
                style = PleadType.metadata,
                color = PleadColor.subtleText,
            )
        }
        SettlementDocket.stamp(kase)?.let { stamp ->
            ClosedStamp(text = stamp, modifier = Modifier.align(Alignment.TopEnd).padding(PleadSpacing.l))
        }
    }
}

@Composable
private fun RecordSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(PleadSpacing.m)) {
        Text(title, style = PleadType.displayM, color = PleadColor.cocoa, modifier = Modifier.semantics { heading() })
        content()
    }
}

@Composable
private fun Filing(label: String, text: String, role: Role?) {
    Row(
        Modifier.awCard().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(PleadSpacing.m),
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(role?.let { PleadColor.role(it) } ?: PleadColor.separator, CircleShape))
        Column(verticalArrangement = Arrangement.spacedBy(PleadSpacing.xs)) {
            RecordLabel(label)
            SelectionContainer { Text(text, style = PleadType.body, color = PleadColor.cocoa) }
        }
    }
}

@Composable
private fun ExhibitsSection(kase: Case, store: CaseStore, owner: UUID, title: String, role: Role, onExhibit: (Exhibit) -> Unit) {
    val list = store.exhibits(kase.id, owner)
    if (list.isEmpty()) return
    RecordSection(title) {
        // `LazyVGrid(columns: [GridItem(.adaptive(minimum: 100), spacing: 12)])`.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val spacing = PleadSpacing.m
            val columns = max(1, ((maxWidth + spacing) / (100.dp + spacing)).toInt())
            Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
                list.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                        row.forEach { ex ->
                            ExhibitTile(
                                ex,
                                url = store.exhibitURLs[ex.id]?.toString(),
                                ownerRole = role,
                                modifier = Modifier.weight(1f).courtFileButtonStyle(onClick = { onExhibit(ex) }),
                            )
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

// MARK: - Transcript

/**
 * One transcript line. Judge = mahogany with cream serif text; parties = paper white with a role
 * chip; objections = parchment with an OBJECTION header.
 */
@Composable
fun TranscriptRow(turn: Turn, kase: Case, store: CaseStore, modifier: Modifier = Modifier) {
    val isJudge = turn.speaker == Speaker.judge
    val speakerRole: Role? = when (turn.speaker) {
        Speaker.plaintiff -> Role.plaintiff
        Speaker.defendant -> Role.defendant
        Speaker.judge -> null
    }
    val name = when (turn.speaker) {
        Speaker.judge -> (store.couple?.judgePersona ?: JudgePersona.wigsworth).displayName
        Speaker.plaintiff -> store.name(kase.plaintiffId, fallback = "Plaintiff")
        Speaker.defendant -> store.name(kase.defendantId, fallback = "Defendant")
    }
    val nameColor = if (isJudge) PleadColor.cream.copy(alpha = 0.8f) else PleadColor.subtleText
    val background = when {
        turn.isObjection -> PleadColor.parchment
        turn.isSafetyNotice -> PleadColor.paperWhite
        isJudge -> PleadColor.mahogany
        else -> PleadColor.paperWhite
    }
    val border = if (turn.isObjection) PleadColor.burgundy.copy(alpha = 0.25f) else if (isJudge) Color.Transparent else PleadColor.separator
    val shape = RoundedCornerShape(PleadRadius.bubble)
    Column(
        modifier
            .fillMaxWidth()
            .background(background, shape)
            .border(1.dp, border, shape)
            .padding(PleadSpacing.m + 2.dp),
        verticalArrangement = Arrangement.spacedBy(PleadSpacing.xs + 2.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(PleadSpacing.s), verticalAlignment = Alignment.CenterVertically) {
            if (turn.isObjection) {
                Text("OBJECTION", style = PleadType.labelCapsTracked, color = PleadColor.burgundy)
                turn.objectionRuling?.let { ruling ->
                    Chip(
                        if (ruling == ObjectionRuling.sustained) "Sustained" else "Overruled",
                        foreground = if (ruling == ObjectionRuling.sustained) PleadColor.burgundy else PleadColor.walnut,
                    )
                }
            } else {
                Text(name, style = CaseType.speakerName, color = nameColor)
                speakerRole?.let { RoleChip(it) }
                turn.phase?.let { PhaseChip(it.chipTitle, tint = if (isJudge) PleadColor.cream else PleadColor.walnut) }
            }
            Spacer(Modifier.weight(1f))
            if (isJudge) ScalesMark(size = 14.dp, color = PleadColor.gold)
        }
        // Amendment u: judge copy is editorial (Fraunces), the partners' own words stay SF Pro.
        val judgeVoice = isJudge && !turn.isObjection
        SelectionContainer {
            Text(
                turn.body,
                style = if (judgeVoice) PleadType.judgeSpeech else PleadType.body,
                color = if (judgeVoice) PleadColor.cream else PleadColor.cocoa,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// MARK: - Verdict

/** Statics of [VerdictCard] (Swift `VerdictCard` static members). */
object VerdictCard {
    /** The outage fallback ruling (`verdicts.is_fallback`): the backend retries the full panel. */
    fun isProvisional(v: Verdict): Boolean = v.isFallback == true
    const val provisionalNote = "Provisional ruling · the full panel could not sit in time"

    /** "PLAINTIFF WINS · 2-1 PANEL DECISION", "TIE · 1-1-1 PANEL", "GUILTY PLEA". */
    fun headline(v: Verdict, kase: Case): String {
        when (v.kind) {
            VerdictKind.guilty -> return "GUILTY PLEA · REMEDY GRANTED"
            VerdictKind.default -> return "DEFAULT JUDGMENT"
            else -> Unit
        }
        if (v.isTie) return v.panelSplitHeadline
        val w = v.winnerId ?: return v.panelSplitHeadline
        val side = kase.role(w) ?: return v.panelSplitHeadline
        val wins = if (side == Role.plaintiff) "PLAINTIFF WINS" else "DEFENDANT WINS"
        return if (v.panelSplit == null) wins else "$wins · ${v.panelSplitHeadline}"
    }
}

/**
 * The ruling outside the courtroom: panel split headline, confidence, recap, decisive
 * findings, the court judgement (amendment j; legacy rulings keep their sentence) and the closing line.
 */
@Composable
fun VerdictCard(verdict: Verdict, kase: Case, store: CaseStore, modifier: Modifier = Modifier) {
    val confidenceLabel = verdict.confidenceLabel?.lowercase()?.takeIf { it in listOf("high", "medium", "low") }
        ?.let { "${it.replaceFirstChar { c -> c.titlecase(Locale.ROOT) }} confidence" }
    val winnerLine = when {
        verdict.isTie -> "A tie. Both parties are ridiculous."
        verdict.winnerId == null -> "The court has ruled"
        verdict.winnerId == store.me?.id -> "You win"
        else -> "${store.name(verdict.winnerId!!, fallback = "Someone")} wins"
    }
    val judgeName = (store.couple?.judgePersona ?: JudgePersona.wigsworth).displayName
    Column(
        modifier
            .border(1.5.dp, PleadColor.gold.copy(alpha = 0.55f), RoundedCornerShape(PleadRadius.card))
            .awCard(PleadSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(PleadSpacing.l),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
            Row(horizontalArrangement = Arrangement.spacedBy(PleadSpacing.s), verticalAlignment = Alignment.CenterVertically) {
                ScalesMark(size = 18.dp)
                Text("VERDICT", style = PleadType.labelCapsTracked, color = PleadColor.gold)
                Spacer(Modifier.weight(1f))
                if (confidenceLabel != null) Chip(confidenceLabel, foreground = PleadColor.walnut, systemImage = "gauge.with.dots.needle.50percent")
            }
            Text(
                VerdictCard.headline(verdict, kase),
                style = CaseType.verdictHeadline.copy(letterSpacing = 0.6.sp),
                color = PleadColor.burgundy,
                modifier = Modifier.semantics { heading() },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(PleadSpacing.m), verticalAlignment = Alignment.CenterVertically) {
                WinnerArt(verdict, store)
                Text(winnerLine, style = PleadType.displayM, color = PleadColor.cocoa)
            }
            if (VerdictCard.isProvisional(verdict)) {
                Row(horizontalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
                    Icon(symbolIcon("info.circle"), contentDescription = null, tint = PleadColor.subtleText, modifier = Modifier.size(15.dp))
                    Text(VerdictCard.provisionalNote, style = PleadType.metadata, color = PleadColor.subtleText)
                }
            }
        }

        val conflict = verdict.majorityConflict
        if (!conflict.isNullOrEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(PleadColor.parchment, RoundedCornerShape(PleadRadius.tile))
                    .padding(PleadSpacing.m),
                verticalArrangement = Arrangement.spacedBy(PleadSpacing.xs),
            ) {
                RecordLabel("The judge departed from the panel")
                Text(conflict, style = PleadType.judgeSpeech, color = PleadColor.cocoa)
            }
        }

        if (verdict.recap.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(PleadSpacing.xs)) {
                RecordLabel("Recap")
                SelectionContainer { Text(verdict.recap, style = PleadType.body, color = PleadColor.cocoa) }
            }
        }

        if (verdict.findings.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(PleadSpacing.m)) {
                RecordLabel("Decisive findings")
                verdict.findings.forEach { f ->
                    Row(horizontalArrangement = Arrangement.spacedBy(PleadSpacing.m), modifier = Modifier.semantics(mergeDescendants = true) {}) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "${f.label} · ${if (f.side == Role.plaintiff) "Plaintiff" else "Defendant"}",
                                style = CaseType.speakerName,
                                color = PleadColor.role(f.side),
                            )
                            Text(f.finding, style = PleadType.body, color = PleadColor.cocoa)
                        }
                        WeightDots(f.weight, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }

        // With a judgement row, the fulfilment card under the verdict carries it; legacy rulings keep their sentence.
        if (store.judgement(kase.id) == null) {
            VerdictJudgementLineSlot(verdict, null)
        }

        Column(
            Modifier.semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(PleadSpacing.xs),
        ) {
            Text("“${verdict.closingLine}”", style = CaseType.judgeQuote, color = PleadColor.mahogany)
            Text("— $judgeName", style = PleadType.metadata, color = PleadColor.subtleText)
        }
    }
}

@Composable
private fun WinnerArt(verdict: Verdict, store: CaseStore) {
    val me = store.me
    val partner = store.partner
    val winner = verdict.winnerId?.let { store.profile(it) }
    if (verdict.isTie && me != null && partner != null) {
        Row(horizontalArrangement = Arrangement.spacedBy((-10).dp)) {
            AvatarBadge(me.avatar, size = 48.dp)
            AvatarBadge(partner.avatar, size = 48.dp)
        }
    } else if (winner != null) {
        Box(contentAlignment = Alignment.TopCenter) {
            AvatarBadge(winner.avatar, size = 52.dp)
            Icon(
                symbolIcon("crown.fill"),
                contentDescription = null,
                tint = PleadColor.gold,
                modifier = Modifier.size(13.dp).offset(y = (-11).dp),
            )
        }
    }
}

// MARK: - Panel

/**
 * "THE PANEL": three small juror cards ("Juror 01 · Evidence"), each with its one-line summary
 * and preferred outcome. Only shown once the case is revealed.
 */
@Composable
fun PanelSection(reviews: List<JurorReview>, kase: Case, store: CaseStore, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(PleadSpacing.m)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("The panel", style = PleadType.displayM, color = PleadColor.cocoa, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.weight(1f))
            Text("Three independent jurors", style = PleadType.metadata, color = PleadColor.subtleText)
        }
        reviews.forEach { review -> JurorCard(review, PanelSection.preference(review, kase, store)) }
    }
}

object PanelSection {
    /** Swift's `(text: String, role: Role?)` preference tuple. */
    data class Preference(val text: String, val role: Role?)

    fun preference(r: JurorReview, kase: Case, store: CaseStore): Preference {
        val id = r.preferredWinnerId
        if (r.isTie || id == null) return Preference("Favoured a tie", null)
        val role = kase.role(id)
        val name = if (id == store.me?.id) "you" else store.name(id, fallback = if (role == Role.plaintiff) "the plaintiff" else "the defendant")
        return Preference("Favoured $name", role)
    }
}

@Composable
private fun JurorCard(review: JurorReview, preference: PanelSection.Preference) {
    val percent = (review.confidence * 100).roundToInt()
    Column(
        Modifier.awCourtFile(PleadSpacing.l).semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(PleadSpacing.s),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(PleadSpacing.s), verticalAlignment = Alignment.CenterVertically) {
            Text("Juror ${review.jurorRole.number}", style = CaseType.jurorName, color = PleadColor.cocoa)
            Text("·", color = PleadColor.subtleText)
            Text(review.jurorRole.mandate, style = PleadType.metadataMedium, color = PleadColor.walnut)
            Spacer(Modifier.weight(1f))
            Text(
                "$percent%",
                style = PleadType.metadata.monospacedDigit(),
                color = PleadColor.subtleText,
                modifier = Modifier.clearAndSetSemantics { contentDescription = "Confidence $percent percent" },
            )
        }
        val summary = review.summary
        if (!summary.isNullOrEmpty()) Text(summary, style = PleadType.body, color = PleadColor.cocoa)
        CaseChip(
            preference.text,
            foreground = preference.role?.let { PleadColor.role(it) } ?: PleadColor.subtleText,
            systemImage = if (preference.role == null) "equal" else "hand.point.right.fill",
        )
    }
}

// MARK: - Exhibit detail

@Composable
private fun ExhibitDetailSheet(exhibit: Exhibit, store: CaseStore, onDismiss: () -> Unit) {
    CaseSheetHost(onDismissRequest = onDismiss, partial = true) {
        CaseNavBar(
            title = exhibit.displayName,
            trailing = { NavTextButton("Done", bold = true, onClick = onDismiss) },
        )
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(PleadSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(PleadSpacing.l),
        ) {
            store.exhibitURLs[exhibit.id]?.let { url ->
                SubcomposeAsyncImage(
                    model = url.toString(),
                    contentDescription = exhibit.caption,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().background(Color.Transparent, RoundedCornerShape(PleadRadius.tile)),
                    loading = {
                        Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = PleadColor.subtleText, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                        }
                    },
                )
            }
            exhibit.body?.let { body ->
                SelectionContainer {
                    Text(
                        body,
                        style = if (exhibit.type == ExhibitType.receipt) CaseType.receipt else CaseType.exhibitQuote,
                        color = PleadColor.cocoa,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(PleadColor.parchment, RoundedCornerShape(PleadRadius.tile))
                            .padding(PleadSpacing.l),
                    )
                }
            }
            Text(exhibit.caption, style = PleadType.titleM, color = PleadColor.cocoa)
            exhibit.occurredAt?.let { at -> LabelRow(CaseDates.completeDateTime(at), "calendar", PleadType.body, PleadColor.subtleText) }
            exhibit.objectionReason?.let { reason ->
                val ruling = exhibit.objectionRuling?.let { " — ${it.rawValue.replaceFirstChar { c -> c.titlecase(Locale.ROOT) }}" } ?: ""
                LabelRow("Objection: ${reason.title}$ruling", "exclamationmark.bubble.fill", PleadType.body, PleadColor.burgundy)
            }
            exhibit.objectionNote?.let { Text(it, style = PleadType.judgeSpeech, color = PleadColor.subtleText) }
        }
    }
}

/** Swift `Label(text, systemImage:)`. */
@Composable
internal fun LabelRow(text: String, systemImage: String, style: androidx.compose.ui.text.TextStyle, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(PleadSpacing.s), verticalAlignment = Alignment.CenterVertically) {
        Icon(symbolIcon(systemImage), contentDescription = null, tint = color, modifier = Modifier.size(17.dp))
        Text(text, style = style, color = color, overflow = TextOverflow.Clip)
    }
}

// MARK: - Record label & case type

/**
 * Court-record label (CHARGE, REQUESTED REMEDY, PLEA, form field labels): `PleadType.labelCaps`,
 * uppercased and tracked (amendment u). Used across the case-side screens instead of `SectionLabel`.
 */
@Composable
fun RecordLabel(text: String, modifier: Modifier = Modifier, color: Color = PleadColor.subtleText) {
    Text(
        text.uppercase(),
        style = PleadType.labelCapsTracked,
        color = color,
        modifier = modifier.fillMaxWidth().semantics { heading() },
    )
}

/**
 * Case-side tokens the shared `PleadType` table does not name (amendment u). Built from its own
 * factories so they scale with the font size and fall back to the system serif with the display face.
 */
object CaseType {
    /** Docket row case title (Fraunces, between displayM and displayL). */
    val rowTitle = PleadType.display(20f, weight = FontWeight.SemiBold, relativeTo = TextStyleKind.title3)

    /** Home's active case card title. */
    val cardTitle = PleadType.display(24f, weight = FontWeight.SemiBold, relativeTo = TextStyleKind.title2)

    /** "PLAINTIFF WINS · 2-1 PANEL DECISION" on the record's verdict card. */
    val verdictHeadline = PleadType.display(17f, weight = FontWeight.Bold, relativeTo = TextStyleKind.headline)

    /** The judge's closing line / settlement flavour line. */
    val judgeQuote = PleadType.display(18f, weight = FontWeight.Normal, italic = true, relativeTo = TextStyleKind.body)

    /** "Juror 01". */
    val jurorName = PleadType.display(15f, weight = FontWeight.SemiBold, relativeTo = TextStyleKind.subheadline)

    /** Transcript speaker names, finding labels, offer headings (SF Rounded semibold 13). */
    val speakerName = PleadType.ui(13f, FontWeight.SemiBold, relativeTo = TextStyleKind.footnote)

    /** Exhibit titles ("Exhibit A"): SF Rounded semibold. */
    val exhibitTitle = PleadType.ui(15f, FontWeight.SemiBold, relativeTo = TextStyleKind.subheadline)

    /** User-written terms that need emphasis (accepted offer, pending offer): SF Pro, never Fraunces bold. */
    val bodyEmphasis = PleadType.text(17f, FontWeight.SemiBold, relativeTo = TextStyleKind.body)

    /** A quoted text exhibit in the detail sheet (user content stays SF Pro). */
    val exhibitQuote = PleadType.text(19f, FontWeight.Normal, relativeTo = TextStyleKind.title3)
    val receipt = PleadType.ui(16f, FontWeight.SemiBold, relativeTo = TextStyleKind.body).copy(fontFamily = FontFamily.Monospace)

    /** Form step titles ("The charge"): SF Rounded, a screen heading rather than a display moment. */
    val stepTitle = PleadType.ui(28f, FontWeight.Bold, relativeTo = TextStyleKind.title)

    /** Home hero line and record counter (SF Rounded bold; heavy is retired by the weight rules). */
    val heroLine = PleadType.ui(30f, FontWeight.Bold, relativeTo = TextStyleKind.largeTitle)
    val recordTally = PleadType.ui(28f, FontWeight.Bold, relativeTo = TextStyleKind.title).monospacedDigit()
    val inviteCode = PleadType.ui(34f, FontWeight.Bold, relativeTo = TextStyleKind.largeTitle).copy(fontFamily = FontFamily.Monospace)

    /** "DUE · 3 DAYS" style fulfilment labels on Home. */
    val dueLabel = PleadType.ui(13f, FontWeight.Bold, relativeTo = TextStyleKind.footnote).monospacedDigit()

    /** Home's closing principle line. */
    val principle = PleadType.text(15f, FontWeight.Normal, relativeTo = TextStyleKind.subheadline).italic()
}
