// Port of ArgueWin/Features/Settlement/SettlementAcceptedView.swift.
package app.plead.android.features.settlement

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.plead.android.app.AppModel
import app.plead.android.designsystem.LegalLabel
import app.plead.android.designsystem.PleadColor
import app.plead.android.designsystem.PleadRadius
import app.plead.android.designsystem.PleadSpacing
import app.plead.android.designsystem.PleadType
import app.plead.android.designsystem.PrimaryButton
import app.plead.android.designsystem.TextStyleKind
import app.plead.android.designsystem.awBottomBar
import app.plead.android.designsystem.awCard
import app.plead.android.models.JudgePersona
import app.plead.android.services.docketTitle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * CASE SETTLED · OUT OF COURT (brief §7). Presented once per device when a settlement is accepted
 * (by me, or by my partner while I'm in the app): the parchment seal, the agreement, its due date and
 * the judge's flavour line. No verdict, no winner.
 */
@Composable
fun SettlementAcceptedView(caseId: UUID, model: AppModel, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val store = model.store
    val settlement = store.settlement(caseId)
    val offer = store.latestOffer(caseId)
    SheetScaffold(
        cancelTitle = null,
        onCancel = onDismiss,
        modifier = modifier,
        bottomBar = { Box(Modifier.awBottomBar()) { PrimaryButton("Continue") { onDismiss() } } },
    ) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PleadSpacing.l)
                .padding(bottom = PleadSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(PleadSpacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.padding(top = PleadSpacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(PleadSpacing.xs),
            ) {
                Text(
                    "CASE SETTLED",
                    style = PleadType.displayXL.copy(letterSpacing = 1.sp),
                    color = PleadColor.cocoa,
                    modifier = Modifier.semantics { heading(); testTag = "settlement.acceptedTitle" },
                )
                LegalLabel("Out of court", color = PleadColor.walnut, size = 13f)
                store.caseById(caseId)?.let { kase ->
                    Text(
                        kase.docketTitle,
                        style = PleadType.metadataMedium,
                        color = PleadColor.subtleText,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = PleadSpacing.xs),
                    )
                }
            }

            SettlementSeal(size = 150.dp, caption = "Settled out of court", stamped = true, modifier = Modifier.padding(vertical = PleadSpacing.s))

            Column(Modifier.awCard(padding = PleadSpacing.l + 2.dp), verticalArrangement = Arrangement.spacedBy(PleadSpacing.m)) {
                LegalLabel("The agreement", color = PleadColor.burgundy, size = 11f)
                SelectionContainer {
                    // The couple's own words: the UI sans, never Fraunces (typography brief §4).
                    Text(offer?.body ?: "The terms you both accepted.", style = PleadType.text(20f, FontWeight.Medium, TextStyleKind.title3), color = PleadColor.cocoa)
                }
                Text(SettlementCopy.noWinner, style = PleadType.body, color = PleadColor.subtleText)
            }

            if (settlement != null) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(PleadColor.parchment, RoundedCornerShape(PleadRadius.card))
                        .padding(PleadSpacing.l)
                        .semantics(mergeDescendants = true) { },
                    verticalArrangement = Arrangement.spacedBy(PleadSpacing.s),
                ) {
                    LegalLabel("Fulfilment", color = PleadColor.walnut, size = 11f)
                    Text(SettlementFulfilment.of(settlement).cardLine, style = PleadType.titleM, color = PleadColor.cocoa)
                    settlement.dueAt?.let { due ->
                        Text("Due ${SettlementAcceptedDates.weekdayDayMonth(due)}", style = PleadType.metadata, color = PleadColor.subtleText)
                    }
                }
            }

            JudgeLine(store.couple?.judgePersona ?: JudgePersona.wigsworth)
        }
    }
    LaunchedEffect(settlement?.id) { settlement?.id?.let(store::markSettlementCelebrated) }
}

@Composable
private fun JudgeLine(persona: JudgePersona) {
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { },
        horizontalArrangement = Arrangement.spacedBy(PleadSpacing.m),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(36.dp).background(PleadColor.parchment, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.AccountBalance, contentDescription = null, tint = PleadColor.mahogany, modifier = Modifier.size(18.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(PleadSpacing.xs)) {
            Text(persona.displayName, style = PleadType.ui(12f, FontWeight.SemiBold, TextStyleKind.caption), color = PleadColor.walnut)
            Text("“${SettlementCopy.flavour}”", style = PleadType.judgeSpeech, color = PleadColor.cocoa)
        }
    }
}

/** `date.formatted(.dateTime.weekday(.wide).day().month(.wide))`: "Thursday 1 October" (locale-ordered). */
object SettlementAcceptedDates {
    fun weekdayDayMonth(date: Instant, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): String {
        val pattern = runCatching { DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM") }.getOrNull() ?: "EEEE d MMMM"
        return DateTimeFormatter.ofPattern(pattern, locale).format(date.atZone(zone))
    }
}
