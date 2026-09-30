// Port of ArgueWin/Courtroom/CourtJudgement.swift: the winner-selected court judgement in the courtroom
// (CONTRACTS-v2 amendment j, brief screens A and C):
//   JudgementDeliveryBubble  the large mahogany judge bubble that reads the delivery (ALL RISE eyebrow, gold scales,
//                            serif cream text), tail down at the judge
//   JudgementDeliveryCard    parchment card: COURT JUDGEMENT, selected title + detail, due line, status chip, gold
//                            SERVED stamp once served
//   CourtServedStamp         the small gold SERVED stamp
//   JudgementDetailSheet     everything in full (the scene truncates on small screens / large type)
// Bubbles and cards only fade. Colours per CONTRACTS-v2 §4.
package app.plead.android.courtroom

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.plead.android.designsystem.CourtFont
import app.plead.android.designsystem.PleadColor
import app.plead.android.designsystem.PleadCopy
import app.plead.android.designsystem.PleadRadius
import app.plead.android.designsystem.PleadSpacing
import app.plead.android.designsystem.PleadType
import app.plead.android.designsystem.fixedSp
import app.plead.android.designsystem.monospacedDigit
import app.plead.android.designsystem.pleadShadow
import app.plead.android.models.JudgePersona
import app.plead.android.models.Judgement
import app.plead.android.models.JudgementStatus
import java.time.Instant
import java.util.UUID

/** Cases whose delivery moment has already buzzed this launch (one light haptic, first appearance only). */
object JudgementHapticMemory {
    val delivered: MutableSet<UUID> = mutableSetOf()

    fun deliveryAppeared(caseId: UUID, view: View) {
        if (delivered.contains(caseId)) return
        delivered.add(caseId)
        CourtHaptics.light(view)
    }
}

// MARK: - Delivery bubble

@Composable
fun JudgementDeliveryBubble(text: String, persona: JudgePersona, modifier: Modifier = Modifier, compressible: Boolean = true) {
    val shape = CourtBubbleShape(tail = CourtTail.down)
    Column(
        modifier
            .pleadShadow(Color.Black.copy(alpha = 0.35f), radius = 8.dp, y = 4.dp, shape = shape)
            .background(PleadColor.mahogany.copy(alpha = 0.96f), shape)
            .border(1.5.dp, PleadColor.gold.copy(alpha = 0.55f), shape)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = (13f + CourtBubbleShape.tailSize).dp)
            .clearAndSetSemantics { contentDescription = "All rise. ${persona.displayName}: $text" },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(PleadCopy.allRise, style = CourtFont.legalLarge.copy(letterSpacing = 2.2.sp), color = PleadColor.gold, maxLines = 1)
            Spacer(Modifier.weight(1f).padding(start = 4.dp))
            ScalesGlyph(size = 13.dp)
            Text(persona.displayName, style = CourtFont.judgeName, color = CourtColor.creamSoft, maxLines = 1)
        }
        ScaledText(
            text,
            style = CourtFont.judgeSpeech.copy(lineHeight = (CourtFont.judgeSpeech.fontSize.value * 1.2f + 1f).sp),
            color = PleadColor.cream,
            minimumScaleFactor = if (compressible) 0.8f else 1f,
            maxLines = Int.MAX_VALUE,
        )
    }
}

// MARK: - Card

@Composable
fun JudgementDeliveryCard(judgement: Judgement, modifier: Modifier = Modifier, now: Instant = Instant.now(), compact: Boolean = false) {
    val title = judgement.selected?.title ?: "Court judgement"
    val detail = judgement.selected?.detail?.takeIf { it.isNotEmpty() && it != title }
    val due = CourtroomLogic.dueLine(judgement, now)
    val label = buildList {
        add("${if (judgement.isCourtChosen) "Court resolution" else "Court judgement"}: $title.")
        detail?.let { add(it) }
        due?.let { add(it.replace(":", "") + ".") }
        add("Status: ${judgement.status.title}.")
    }.joinToString(" ")
    val shape = RoundedCornerShape(PleadRadius.tile)
    Column(
        modifier
            .fillMaxWidth()
            .pleadShadow(Color.Black.copy(alpha = 0.35f), radius = 8.dp, y = 4.dp, shape = shape)
            .background(PleadColor.parchment, shape)
            .drawWithContent {
                drawContent()
                strokeBorder(PleadRadius.tile.toPx(), inset = 3.dp.toPx(), width = 1.dp.toPx(), color = PleadColor.gold.copy(alpha = 0.55f))
                strokeBorder(PleadRadius.tile.toPx(), inset = 0f, width = 1.dp.toPx(), color = PleadColor.walnut.copy(alpha = 0.5f))
            }
            .padding(horizontal = if (compact) 10.dp else PleadSpacing.l, vertical = if (compact) 9.dp else PleadSpacing.l)
            .clearAndSetSemantics { contentDescription = label },
        verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            ScalesGlyph(color = PleadColor.burgundy, size = if (compact) 11.dp else 13.dp)
            ScaledText(
                if (judgement.isCourtChosen) "COURT RESOLUTION" else "COURT JUDGEMENT",
                style = CourtFont.legal.copy(letterSpacing = PleadType.capsTracking.sp),
                color = PleadColor.burgundy,
                minimumScaleFactor = 0.8f,
            )
        }
        ScaledText(
            title,
            style = if (compact) CourtFont.displayS else CourtFont.displayM,
            color = PleadColor.cocoa,
            minimumScaleFactor = 0.85f,
            maxLines = if (compact) 2 else Int.MAX_VALUE,
        )
        if (detail != null) {
            Text(
                detail,
                style = if (compact) CourtFont.small else CourtFont.footnote,
                color = PleadColor.walnut,
                maxLines = if (compact) 3 else Int.MAX_VALUE,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = if (compact) Modifier.weight(1f, fill = false) else Modifier,
            )
        }
        if (compact) Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (due != null) {
                ScaledText(
                    due,
                    style = (if (compact) CourtFont.caption2 else CourtFont.caption).monospacedDigit(),
                    color = PleadColor.cocoa,
                    minimumScaleFactor = 0.8f,
                )
            }
            Spacer(Modifier.weight(1f))
            if (judgement.status == JudgementStatus.served) {
                // The gold stamp replaces the chip, pressed in at a slight angle.
                CourtServedStamp(small = compact, modifier = Modifier.rotate(-7f))
            } else {
                JudgementStatusChip(status = judgement.status, compact = compact)
            }
        }
    }
}

/** Status chip: burgundy outline while open, solid gold once served, neutral when declined. */
@Composable
fun JudgementStatusChip(status: JudgementStatus, modifier: Modifier = Modifier, compact: Boolean = false) {
    val (fg, bg, stroke) = when (status) {
        JudgementStatus.served -> Triple(PleadColor.cocoa, PleadColor.gold, PleadColor.gold)
        JudgementStatus.accepted -> Triple(PleadColor.cream, PleadColor.walnut, PleadColor.walnut)
        JudgementStatus.declined -> Triple(CourtColor.neutralSubtle, CourtColor.neutralCard, CourtColor.neutralStroke)
        JudgementStatus.delivered, JudgementStatus.pendingSelection -> Triple(PleadColor.burgundy, Color.Transparent, PleadColor.burgundy.copy(alpha = 0.6f))
    }
    val shape = RoundedCornerShape(5.dp)
    Text(
        if (status == JudgementStatus.pendingSelection) "AWAITING" else status.title.uppercase(),
        style = CourtFont.legal.copy(letterSpacing = PleadType.capsTracking.sp),
        color = fg,
        maxLines = 1,
        softWrap = false,
        modifier = modifier
            .clearAndSetSemantics { }
            .background(bg, shape)
            .border(1.dp, stroke, shape)
            .padding(horizontal = if (compact) 5.dp else 7.dp, vertical = 2.dp),
    )
}

/** Small gold SERVED stamp (double rule, heavy caps). Appears with a fade; no slam. */
@Composable
fun CourtServedStamp(modifier: Modifier = Modifier, small: Boolean = false) {
    val shape = RoundedCornerShape(5.dp)
    Text(
        "SERVED",
        style = TextStyle(
            fontFamily = FontFamily.Default, fontWeight = FontWeight.Black, fontSize = fixedSp(if (small) 11f else 14f),
            letterSpacing = (if (small) 1.4f else 2f).sp,
        ),
        color = PleadColor.gold,
        maxLines = 1,
        softWrap = false,
        modifier = modifier
            .clearAndSetSemantics { contentDescription = "Served" }
            .pleadShadow(Color.Black.copy(alpha = 0.3f), radius = 2.dp, y = 1.dp, shape = shape)
            .background(PleadColor.mahogany.copy(alpha = 0.92f), shape)
            .drawWithContent {
                drawContent()
                val r = 5.dp.toPx()
                strokeBorder(r, inset = 0f, width = (if (small) 1.5.dp else 2.dp).toPx(), color = PleadColor.gold)
                strokeBorder(r, inset = (if (small) 2.5.dp else 3.5.dp).toPx(), width = 0.75.dp.toPx(), color = PleadColor.gold.copy(alpha = 0.6f))
            }
            .padding(horizontal = if (small) 6.dp else 9.dp, vertical = if (small) 2.dp else 4.dp),
    )
}

// MARK: - Full text

/** The delivery and judgement in full (tap the bubble or card in the scene). Sheet content. */
@Composable
fun JudgementDetailSheet(state: CourtroomState, onDismiss: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(PleadColor.cream)) {
        CourtSheetTopBar(title = "Court judgement", trailingTitle = "Done", onTrailing = onDismiss)
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(PleadSpacing.l),
            verticalArrangement = Arrangement.spacedBy(PleadSpacing.l),
        ) {
            CourtroomLogic.deliveryText(state)?.let { text ->
                JudgementDeliveryBubble(text = text, persona = state.judgePersona, compressible = false)
            }
            val j = state.judgement
            if (j != null && j.selected != null) {
                JudgementDeliveryCard(judgement = j, now = state.now)
            }
            Text(
                "Judgements are playful and honour-based. Accepting is a promise to each other, not a legal commitment.",
                style = CourtFont.footnote,
                color = PleadColor.walnut,
            )
        }
    }
}
