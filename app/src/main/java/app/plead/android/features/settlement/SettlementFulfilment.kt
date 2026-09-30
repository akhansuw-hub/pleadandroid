// Port of ArgueWin/Features/Settlement/SettlementFulfilment.swift.
//
// Settlement fulfilment (brief §7, amendment n). A settled case is closed; the agreement's progress is
// tracked separately and is honour-based: overdue changes nothing on the record.
//
//   Agreement: Replace the meal + dinner date
//   FULFILMENT: Outstanding · Due in 3 days   → SETTLEMENT FULFILLED ✓ (gold) · FULFILMENT: Overdue
//
// For the docket / Home / record (stable parameters, drop-in):
//   SettlementStatusRow(settlement, offer, now)             compact block under a docket row
//   SettlementFulfilmentCard(settlement, offer, store, now) the record's card, with "Mark as fulfilled"
// Never the word SERVED here.
package app.plead.android.features.settlement

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.plead.android.designsystem.InlineError
import app.plead.android.designsystem.LegalLabel
import app.plead.android.designsystem.PleadColor
import app.plead.android.designsystem.PleadSpacing
import app.plead.android.designsystem.PleadType
import app.plead.android.designsystem.TextStyleKind
import app.plead.android.designsystem.awCourtFile
import app.plead.android.designsystem.monospacedDigit
import app.plead.android.models.Settlement
import app.plead.android.models.SettlementOffer
import app.plead.android.models.SettlementStatus
import app.plead.android.services.CaseStore
import app.plead.android.services.EdgeErrors
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlinx.coroutines.launch

/** Where an agreement stands, for the fulfilment line (pure; unit tested). */
sealed class SettlementFulfilment {
    /** Accepted, due in `days` calendar days (0 = today). */
    data class due(val days: Int) : SettlementFulfilment()

    /** Accepted and past its due moment. */
    data object overdue : SettlementFulfilment()

    /** Accepted without a due date. */
    data object outstanding : SettlementFulfilment()
    data object fulfilled : SettlementFulfilment()

    /** Not an agreement (pending / rejected / withdrawn / expired). */
    data object none : SettlementFulfilment()

    /** "FULFILMENT: Outstanding · Due in 3 days", "SETTLEMENT FULFILLED ✓", "FULFILMENT: Overdue". */
    val label: String
        get() = when (this) {
            is due -> "FULFILMENT: Outstanding · ${dueText(days)}"
            overdue -> "FULFILMENT: Overdue"
            outstanding -> "FULFILMENT: Outstanding"
            fulfilled -> "SETTLEMENT FULFILLED ✓"
            none -> ""
        }

    /** The card's plainer line: "Outstanding · Due in 3 days". */
    val cardLine: String
        get() = when (this) {
            is due -> "Outstanding · ${dueText(days)}"
            overdue -> "Overdue · no penalty, just a nudge"
            outstanding -> "Outstanding"
            fulfilled -> "Fulfilled"
            none -> ""
        }

    /** Gold once fulfilled, burgundy when overdue, walnut while outstanding. */
    val tint: Color
        get() = when (this) {
            fulfilled -> PleadColor.gold
            overdue -> PleadColor.burgundy
            else -> PleadColor.walnut
        }

    val isOutstanding: Boolean
        get() = when (this) {
            is due, overdue, outstanding -> true
            else -> false
        }

    companion object {
        /** Swift `of(_:now:calendar:)`: the calendar is the device's, i.e. its time zone. */
        fun of(s: Settlement, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): SettlementFulfilment =
            when (s.status) {
                SettlementStatus.fulfilled -> fulfilled
                SettlementStatus.accepted -> {
                    val due = s.dueAt
                    when {
                        due == null -> outstanding
                        due.isBefore(now) -> overdue
                        else -> due(max(0, ChronoUnit.DAYS.between(now.atZone(zone).toLocalDate(), due.atZone(zone).toLocalDate()).toInt()))
                    }
                }
                else -> none
            }

        fun dueText(days: Int): String = when (days) {
            0 -> "Due today"
            1 -> "Due tomorrow"
            else -> "Due in $days days"
        }
    }
}

/** Compact block for a docket / Home row: `Agreement: …` and the fulfilment line. */
@Composable
fun SettlementStatusRow(settlement: Settlement, offer: SettlementOffer?, modifier: Modifier = Modifier, now: Instant = Instant.now()) {
    val f = SettlementFulfilment.of(settlement, now)
    Column(modifier.fillMaxWidth().semantics(mergeDescendants = true) { }, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = PleadColor.walnut)) { append("Agreement: ") }
                withStyle(SpanStyle(color = PleadColor.cocoa)) { append(offer?.body ?: "the agreed terms") }
            },
            style = PleadType.text(14f, FontWeight.Medium, TextStyleKind.subheadline),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (f != SettlementFulfilment.none) {
            Text(
                f.label,
                style = PleadType.labelCaps.monospacedDigit().copy(letterSpacing = PleadType.capsTracking.sp),
                color = f.tint,
            )
        }
    }
}

/**
 * The record's fuller card: SETTLED OUT OF COURT, the agreement, the fulfilment line and
 * "Mark as fulfilled" (either partner, one confirmation).
 */
@Composable
fun SettlementFulfilmentCard(
    settlement: Settlement,
    offer: SettlementOffer?,
    store: CaseStore,
    modifier: Modifier = Modifier,
    now: Instant = Instant.now(),
) {
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var done by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val f = SettlementFulfilment.of(settlement, now)

    fun mark() {
        working = true; error = null
        scope.launch {
            try {
                store.markSettlementFulfilled(settlement.caseId)
                done += 1
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = EdgeErrors.settlementMessage(e)
            }
            working = false
        }
    }

    Column(modifier.awCourtFile(), verticalArrangement = Arrangement.spacedBy(PleadSpacing.m)) {
        Row(horizontalArrangement = Arrangement.spacedBy(PleadSpacing.s), verticalAlignment = Alignment.CenterVertically) {
            SettlementSeal(size = 30.dp)
            LegalLabel("Settled out of court", color = PleadColor.walnut, size = 11f)
            Spacer(Modifier.weight(1f))
        }
        Column(verticalArrangement = Arrangement.spacedBy(PleadSpacing.xs)) {
            LegalLabel("The agreement", color = PleadColor.burgundy, size = 10f)
            SelectionContainer {
                Text(offer?.body ?: "The terms you both accepted.", style = SettlementType.terms, color = PleadColor.cocoa)
            }
        }
        if (f == SettlementFulfilment.fulfilled) {
            Text(
                f.label,
                style = PleadType.display(14f, FontWeight.Bold, relativeTo = TextStyleKind.footnote).copy(letterSpacing = 1.6.sp),
                color = PleadColor.gold,
                modifier = Modifier
                    .padding(vertical = PleadSpacing.xs)
                    .rotate(-4f)
                    .border(2.dp, PleadColor.gold, RoundedCornerShape(5.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
                    .clearAndSetSemantics { contentDescription = "Settlement fulfilled" },
            )
        } else if (f != SettlementFulfilment.none) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    f.label,
                    style = PleadType.ui(13f, FontWeight.Bold, TextStyleKind.footnote).monospacedDigit().copy(letterSpacing = PleadType.capsTracking.sp),
                    color = f.tint,
                )
                Text("Honour-based. Nothing changes on the record if it runs late.", style = PleadType.metadata, color = PleadColor.subtleText)
            }
            ActionButton("Mark as fulfilled", icon = Icons.Filled.Check, isLoading = working) { confirm = true }
            InlineError(error)
        }
    }
    SuccessFeedback(done)
    ConfirmationDialog(
        visible = confirm,
        title = "Mark the agreement as fulfilled?",
        message = "The case stays closed. Your partner will be told it's done.",
        actions = listOf(DialogAction("Mark as fulfilled") { mark() }, DialogAction("Not yet", cancel = true)),
        onDismiss = { confirm = false },
    )
}
