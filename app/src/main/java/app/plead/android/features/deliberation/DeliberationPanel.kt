// Port of ArgueWin/Features/Deliberation/DeliberationPanel.swift.
package app.plead.android.features.deliberation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.plead.android.designsystem.AWButtonKind
import app.plead.android.designsystem.Color
import app.plead.android.designsystem.LegalLabel
import app.plead.android.designsystem.PleadColor
import app.plead.android.designsystem.PleadCopy
import app.plead.android.designsystem.PleadRadius
import app.plead.android.designsystem.PleadSpacing
import app.plead.android.designsystem.PleadType
import app.plead.android.designsystem.PrimaryButton
import app.plead.android.designsystem.SFSymbol
import app.plead.android.designsystem.ScalesMark
import app.plead.android.designsystem.TextStyleKind
import app.plead.android.designsystem.awBackground
import app.plead.android.designsystem.pleadShadow
import app.plead.android.models.Case
import app.plead.android.models.CaseStatus
import app.plead.android.services.PreviewData
import app.plead.android.services.deliberationStepsDone
import app.plead.android.services.docketTitle

/**
 * "THE COURT IS DELIBERATING": the four panel status lines lit up to `cases.panel_progress`. Used on Home and in Case
 * detail while a case is `deliberating` or `awaiting_verdict`. (The Court tab's in-scene version is the courtroom's
 * own.) A deep mahogany surface: the one court-coloured card outside Court. No countdown: the verdict is revealed as
 * soon as the panel has ruled (amendment av), so the reading is always moments away.
 */
@Composable
fun DeliberationPanel(
    kase: Case,
    modifier: Modifier = Modifier,
    /** Show "Case #021 · Title" under the header (Home); the detail screen already has it. */
    showsTitle: Boolean = true,
    /** Optional trailing action ("Watch the deliberation"). */
    actionTitle: String? = null,
    action: (() -> Unit)? = null,
) {
    val done = kase.deliberationStepsDone
    val shape = RoundedCornerShape(PleadRadius.card)
    Column(
        modifier
            .fillMaxWidth()
            .pleadShadow(PleadColor.mahogany.copy(alpha = 0.25f), radius = 12.dp, y = 6.dp, shape = shape)
            .background(Brush.verticalGradient(listOf(PleadColor.mahogany, Color(hex = 0x4A1C17))), shape)
            .border(1.dp, PleadColor.gold.copy(alpha = 0.28f), shape)
            .padding(PleadSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(PleadSpacing.l),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(PleadSpacing.s),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = PleadCopy.deliberating
                    heading()
                },
            ) {
                ScalesMark(size = 22.dp, color = PleadColor.gold)
                LegalLabel(PleadCopy.deliberating, color = PleadColor.cream, size = 13f)
            }
            if (showsTitle) {
                Text(
                    kase.docketTitle,
                    style = PleadType.display(17f, weight = FontWeight.SemiBold, relativeTo = TextStyleKind.headline),
                    color = PleadColor.cream.copy(alpha = 0.85f),
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(PleadSpacing.m)) {
            for (step in 1 until PleadCopy.deliberationStatus.size) {
                StatusLine(
                    text = PleadCopy.deliberationStatus[step],
                    state = when {
                        step <= done -> LineState.done
                        step == done + 1 && kase.status == CaseStatus.deliberating -> LineState.active
                        else -> LineState.pending
                    },
                )
            }
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(PleadColor.cream.copy(alpha = 0.14f)))

        Text(
            "The ruling will be read as soon as it is ready.",
            style = PleadType.judgeSpeech,
            color = PleadColor.cream.copy(alpha = 0.9f),
        )

        if (actionTitle != null && action != null) {
            PrimaryButton(actionTitle, systemImage = "building.columns", kind = AWButtonKind.onDark, action = action)
        }
    }
}

private enum class LineState { done, active, pending }

@Composable
private fun StatusLine(text: String, state: LineState) {
    val stateWord = when (state) {
        LineState.done -> "done"
        LineState.active -> "in progress"
        LineState.pending -> "waiting"
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(PleadSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clearAndSetSemantics { contentDescription = "$text, $stateWord" },
    ) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            when (state) {
                LineState.done -> {
                    Box(Modifier.size(20.dp).background(PleadColor.cream, CircleShape))
                    Icon(SFSymbol.icon("checkmark"), contentDescription = null, tint = PleadColor.mahogany, modifier = Modifier.size(12.dp))
                }
                LineState.active -> {
                    Box(Modifier.size(20.dp).border(1.5.dp, PleadColor.cream.copy(alpha = 0.6f), CircleShape))
                    CircularProgressIndicator(color = PleadColor.cream, strokeWidth = 1.5.dp, modifier = Modifier.size(10.dp))
                }
                LineState.pending -> Box(Modifier.size(20.dp).border(1.5.dp, PleadColor.cream.copy(alpha = 0.25f), CircleShape))
            }
        }
        Text(
            text,
            style = PleadType.ui(15f, if (state == LineState.done) FontWeight.SemiBold else FontWeight.Medium, relativeTo = TextStyleKind.subheadline),
            color = PleadColor.cream.copy(alpha = if (state == LineState.pending) 0.42f else 1f),
        )
        Spacer(Modifier.weight(1f))
    }
}

@Preview(name = "Deliberating · 2 of 4", widthDp = 402, heightDp = 900)
@Composable
private fun DeliberationPanelPreview() {
    Column(
        Modifier.awBackground().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DeliberationPanel(kase = PreviewData.deliberatingCase, actionTitle = "Watch the deliberation") {}
        DeliberationPanel(kase = PreviewData.awaitingCase, showsTitle = false)
    }
}
