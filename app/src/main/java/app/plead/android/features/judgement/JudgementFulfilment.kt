// Port of ArgueWin/Features/Judgement/JudgementFulfilment.swift.
//
// Judgement fulfilment (CONTRACTS-v2 amendment l). A case is closed once the appeal window ends,
// whatever happened to its judgement; the judgement's own progress is a separate block:
//
//   VERDICT FINAL
//   Judgement: Take them out for dinner
//   DUE · 3 DAYS          → SERVED ✓ (gold) · DECLINED (muted) · OVERDUE · 2 DAYS (burgundy)
//                           · AWAITING THE COURT / AWAITING ALEX'S CHOICE while pending
//
// `JudgementFulfilment` is the pure state → label mapping (unit tested); `JudgementFulfilmentBlock`
// renders it as a slip under a docket row (`slip`) or as the head of the record's judgement card (`full`).
package app.plead.android.features.judgement

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.plead.android.app.AppRouter
import app.plead.android.app.AppSheet
import app.plead.android.designsystem.AWButtonKind
import app.plead.android.designsystem.InlineError
import app.plead.android.designsystem.LegalLabel
import app.plead.android.designsystem.PleadColor
import app.plead.android.designsystem.PleadRadius
import app.plead.android.designsystem.PleadSpacing
import app.plead.android.designsystem.PleadType
import app.plead.android.designsystem.ScalesMark
import app.plead.android.designsystem.TextStyleKind
import app.plead.android.designsystem.awCourtFile
import app.plead.android.designsystem.monospacedDigit
import app.plead.android.features.settlement.ActionButton
import app.plead.android.features.settlement.ConfirmationDialog
import app.plead.android.features.settlement.DialogAction
import app.plead.android.features.settlement.QuietTextButton
import app.plead.android.features.settlement.SuccessFeedback
import app.plead.android.features.settlement.pressScaleClickable
import app.plead.android.models.Case
import app.plead.android.models.Judgement
import app.plead.android.models.JudgementStatus
import app.plead.android.services.CaseStore
import app.plead.android.services.EdgeErrors
import app.plead.android.services.docketTitle
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlinx.coroutines.launch

/** Where a judgement stands, for the fulfilment line. */
sealed class JudgementFulfilment {
    /** Tie, the court has not picked its resolution yet. */
    data object awaitingCourt : JudgementFulfilment()

    /** A partner is choosing: `name` null = me. */
    data class awaitingChoice(val name: String?) : JudgementFulfilment()

    /** Delivered / accepted, due in `days` calendar days (0 = today). */
    data class due(val days: Int) : JudgementFulfilment()

    /** Delivered / accepted, past its due moment by `days` calendar days (0 = earlier today). */
    data class overdue(val days: Int) : JudgementFulfilment()

    /** Delivered / accepted with no due date. */
    data object outstanding : JudgementFulfilment()
    data object served : JudgementFulfilment()
    data object declined : JudgementFulfilment()

    /** "DUE · 3 DAYS", "SERVED ✓", "OVERDUE · 2 DAYS", "AWAITING SAM'S CHOICE"… */
    val label: String
        get() {
            fun days(n: Int) = if (n == 1) "1 DAY" else "$n DAYS"
            return when (this) {
                awaitingCourt -> "AWAITING THE COURT"
                is awaitingChoice -> name?.let { "AWAITING ${it.uppercase()}'S CHOICE" } ?: "AWAITING YOUR CHOICE"
                is due -> if (days == 0) "DUE · TODAY" else "DUE · ${days(days)}"
                is overdue -> if (days == 0) "OVERDUE" else "OVERDUE · ${days(days)}"
                outstanding -> "OUTSTANDING"
                served -> "SERVED ✓"
                declined -> "DECLINED"
            }
        }

    /** Gold once served, burgundy when overdue, muted when declined or waiting, walnut while due. */
    val tint: Color
        get() = when (this) {
            served -> PleadColor.gold
            is overdue -> PleadColor.burgundy
            declined, awaitingCourt, is awaitingChoice -> PleadColor.subtleText
            is due, outstanding -> PleadColor.walnut
        }

    /** Swift `String.capitalized` of the label, " · " read as a pause. */
    val accessibilityText: String
        get() = when (this) {
            served -> "Served"
            else -> label.replace(" · ", ", ").lowercase(Locale.ROOT).split(" ").joinToString(" ") { w ->
                w.replaceFirstChar { it.titlecase(Locale.ROOT) }
            }
        }

    companion object {
        fun of(
            j: Judgement,
            me: UUID?,
            chooserName: String?,
            now: Instant = Instant.now(),
            zone: ZoneId = ZoneId.systemDefault(),
        ): JudgementFulfilment = when (j.status) {
            JudgementStatus.served -> served
            JudgementStatus.declined -> declined
            JudgementStatus.pendingSelection -> {
                val chooser = j.chooserId
                if (chooser == null) awaitingCourt else awaitingChoice(if (chooser == me) null else (chooserName ?: "the winner"))
            }
            JudgementStatus.delivered, JudgementStatus.accepted -> {
                val due = j.dueAt
                val today = now.atZone(zone).toLocalDate()
                when {
                    due == null -> outstanding
                    due.isBefore(now) -> overdue(max(0, ChronoUnit.DAYS.between(due.atZone(zone).toLocalDate(), today).toInt()))
                    else -> due(max(0, ChronoUnit.DAYS.between(today, due.atZone(zone).toLocalDate()).toInt()))
                }
            }
        }
    }
}

/** Ties are court-chosen resolutions; everything else is a judgement. */
val Judgement.noun: String get() = if (isCourtChosen) "Resolution" else "Judgement"

/** The fulfilment block: heading, "Judgement: …", status line. */
object JudgementFulfilmentBlock {
    enum class Style { slip, full }

    /** "VERDICT FINAL" once the case has closed; while the appeal window is open the block names itself. */
    fun heading(kase: Case, judgement: Judgement): String {
        if (kase.status.isClosed) return "Verdict final"
        return if (judgement.isCourtChosen) "Court resolution" else "Court judgement"
    }
}

/** The fulfilment block: heading, "Judgement: …", status line. */
@Composable
fun JudgementFulfilmentBlock(
    kase: Case,
    judgement: Judgement,
    store: CaseStore,
    modifier: Modifier = Modifier,
    style: JudgementFulfilmentBlock.Style = JudgementFulfilmentBlock.Style.slip,
) {
    val full = style == JudgementFulfilmentBlock.Style.full
    val f = JudgementFulfilment.of(
        judgement, store.me?.id,
        chooserName = judgement.chooserId?.let { store.name(it, fallback = "the winner") },
    )
    Column(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) { },
        verticalArrangement = Arrangement.spacedBy(if (full) PleadSpacing.s else 3.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(PleadSpacing.xs + 2.dp), verticalAlignment = Alignment.CenterVertically) {
            ScalesMark(size = if (full) 18.dp else 12.dp)
            LegalLabel(
                JudgementFulfilmentBlock.heading(kase, judgement),
                color = if (full) PleadColor.gold else PleadColor.walnut,
                size = if (full) 12f else 10f,
            )
        }
        val title = judgement.selected?.title
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = PleadColor.walnut)) { append("${judgement.noun}: ") }
                withStyle(SpanStyle(color = if (title == null) PleadColor.subtleText else PleadColor.cocoa)) { append(title ?: "not yet chosen") }
            },
            style = if (full) PleadType.displayM else JudgementType.slipTitle,
            maxLines = if (full) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
            modifier = if (full) Modifier.semantics { heading() } else Modifier,
        )
        val detail = judgement.selected?.detail
        if (full && !detail.isNullOrEmpty() && detail != judgement.selected?.title) {
            SelectionContainer { Text(detail, style = PleadType.body, color = PleadColor.cocoa) }
        }
        Text(
            f.label,
            style = (if (full) JudgementType.statusCaps else PleadType.labelCaps).monospacedDigit().copy(letterSpacing = PleadType.capsTracking.sp),
            color = f.tint,
            modifier = Modifier
                .padding(top = if (full) PleadSpacing.xs else 1.dp)
                .clearAndSetSemantics { contentDescription = f.accessibilityText },
        )
    }
}

/** The slip hanging under a closed docket row: parchment-on-paper, gold rule down the left. */
@Composable
fun JudgementFulfilmentSlip(kase: Case, judgement: Judgement, store: CaseStore, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(bottomStart = PleadRadius.tile, bottomEnd = PleadRadius.tile)
    Box(
        modifier
            .padding(horizontal = PleadSpacing.m)
            .fillMaxWidth()
            .clip(shape)
            .background(PleadColor.paperWhite, shape)
            .border(1.dp, PleadColor.walnut.copy(alpha = 0.18f), shape)
            .height(IntrinsicSize.Min),
    ) {
        JudgementFulfilmentBlock(
            kase, judgement, store,
            style = JudgementFulfilmentBlock.Style.slip,
            modifier = Modifier.padding(start = PleadSpacing.m + 3.dp, end = PleadSpacing.m, top = PleadSpacing.s + 2.dp, bottom = PleadSpacing.s + 2.dp),
        )
        Box(Modifier.width(3.dp).fillMaxHeight().background(PleadColor.gold.copy(alpha = 0.8f)).align(Alignment.CenterStart))
    }
}

// MARK: - Actions (shared by the record's card and Home's outstanding card)

/**
 * The buttons this party may press for a judgement ([JudgementCardAction]): primary first, then
 * secondary ones, then the quiet Decline. [primaryOnly] (Home) shows the first action alone.
 */
@Composable
fun JudgementActionButtons(
    kase: Case,
    judgement: Judgement,
    store: CaseStore,
    router: AppRouter,
    modifier: Modifier = Modifier,
    primaryOnly: Boolean = false,
) {
    var working by remember { mutableStateOf<JudgementCardAction?>(null) }
    var confirmDecline by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var succeeded by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    val all = store.me?.let { JudgementCardAction.actions(judgement, it.id) } ?: emptyList()
    val actions = if (primaryOnly) all.take(1) else all
    val noun = if (judgement.isCourtChosen) "resolution" else "judgement"

    fun perform(action: JudgementCardAction) {
        if (action == JudgementCardAction.choose) {
            router.sheet = AppSheet.chooseJudgement(kase.id); return
        }
        working = action; error = null
        scope.launch {
            try {
                when (action) {
                    JudgementCardAction.accept -> store.respondJudgement(kase.id, accept = true)
                    JudgementCardAction.decline -> store.respondJudgement(kase.id, accept = false)
                    JudgementCardAction.markServed -> store.markServed(kase.id)
                    JudgementCardAction.choose -> Unit
                }
                if (action != JudgementCardAction.decline) succeeded += 1
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = EdgeErrors.judgementMessage(e)
            }
            working = null
        }
    }

    if (actions.isEmpty()) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
        actions.filter { it != JudgementCardAction.decline }.forEach { action ->
            ActionButton(
                JudgementActionCopy.title(action),
                icon = JudgementActionCopy.icon(action),
                kind = if (action == actions.first()) AWButtonKind.primary else AWButtonKind.secondary,
                isLoading = working == action,
                enabled = working == null || working == action,
            ) { perform(action) }
        }
        if (actions.contains(JudgementCardAction.decline)) {
            QuietTextButton("Decline", style = JudgementType.quietAction, enabled = working == null) { confirmDecline = true }
        }
        InlineError(error)
    }
    SuccessFeedback(succeeded)
    ConfirmationDialog(
        visible = confirmDecline,
        title = "Decline the $noun?",
        message = "Judgements are honour-based. The court will note it, without comment.",
        actions = listOf(DialogAction("Decline $noun", destructive = true) { perform(JudgementCardAction.decline) }),
        onDismiss = { confirmDecline = false },
    )
}

/** Titles and symbols of the judgement actions. */
object JudgementActionCopy {
    fun title(a: JudgementCardAction): String = when (a) {
        JudgementCardAction.choose -> "CHOOSE JUDGEMENT"
        JudgementCardAction.accept -> "ACCEPT JUDGEMENT"
        JudgementCardAction.markServed -> "MARK AS SERVED"
        JudgementCardAction.decline -> "Decline"
    }

    /** arrow.right / hand.thumbsup.fill / checkmark.seal.fill. */
    fun icon(a: JudgementCardAction): ImageVector? = when (a) {
        JudgementCardAction.choose -> Icons.AutoMirrored.Filled.ArrowForward
        JudgementCardAction.accept -> Icons.Filled.ThumbUp
        JudgementCardAction.markServed -> Icons.Filled.Verified
        JudgementCardAction.decline -> null
    }
}

// MARK: - Home

/**
 * Home's "Outstanding judgement" card: a delivered / accepted judgement I must act on or am waiting
 * on (plus a choice still owed on a closed case). Disappears once served or declined.
 */
@Composable
fun OutstandingJudgementCard(kase: Case, judgement: Judgement, store: CaseStore, router: AppRouter, modifier: Modifier = Modifier) {
    val f = JudgementFulfilment.of(
        judgement, store.me?.id,
        chooserName = judgement.chooserId?.let { store.name(it, fallback = "the winner") },
    )
    Box(modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(Modifier.awCourtFile(padding = PleadSpacing.l), verticalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
            val title = judgement.selected?.title ?: "not yet chosen"
            Column(
                Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {
                        onClick(label = "Opens the case record") { router.showRecord(kase.id); true }
                    }
                    .pressScaleClickable(pressedAlpha = 0.92f) { router.showRecord(kase.id) },
                verticalArrangement = Arrangement.spacedBy(PleadSpacing.xs + 2.dp),
            ) {
                LegalLabel("Outstanding ${judgement.noun.lowercase()}", color = PleadColor.walnut, size = 11f)
                Text(kase.docketTitle, style = PleadType.metadataMedium, color = PleadColor.walnut, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = PleadColor.walnut)) { append("${judgement.noun}: ") }
                        withStyle(SpanStyle(color = PleadColor.cocoa)) { append(title) }
                    },
                    style = JudgementType.cardTitle,
                )
                Text(
                    f.label,
                    style = JudgementType.statusCaps.monospacedDigit().copy(letterSpacing = PleadType.capsTracking.sp),
                    color = f.tint,
                    modifier = Modifier.clearAndSetSemantics { contentDescription = f.accessibilityText },
                )
            }
            JudgementActionButtons(kase, judgement, store, router, primaryOnly = true, modifier = Modifier.padding(top = PleadSpacing.xs))
        }
        // Gold rule down the leading edge, clipped to the file's corners.
        Box(
            Modifier
                .fillMaxHeight()
                .width(5.dp)
                .clip(RoundedCornerShape(topStart = PleadRadius.card, bottomStart = PleadRadius.card))
                .background(PleadColor.gold.copy(alpha = 0.85f)),
        )
    }
}

// MARK: - Type

/**
 * Judgement-screen type built from the Plead tokens (amendment u): judgement titles are Fraunces,
 * status lines are tracked caps, quiet actions the UI sans semibold.
 */
object JudgementType {
    /** A judgement title on a card (Home, verdict card): Fraunces semibold 18. */
    val cardTitle: TextStyle = PleadType.display(18f, FontWeight.SemiBold, relativeTo = TextStyleKind.headline)

    /** The judgement line on a docket slip: Fraunces semibold 15. */
    val slipTitle: TextStyle = PleadType.display(15f, FontWeight.SemiBold, relativeTo = TextStyleKind.subheadline)

    /** "DUE IN 3 DAYS" on the full card: labelCaps one step up (13). */
    val statusCaps: TextStyle = PleadType.ui(13f, FontWeight.Bold, TextStyleKind.footnote)

    /** Quiet text actions (Decline). */
    val quietAction: TextStyle = PleadType.ui(15f, FontWeight.SemiBold, TextStyleKind.subheadline)
}
