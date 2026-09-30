// Port of ArgueWin/Courtroom/CourtTranscript.swift: the full scrollable transcript (dock button or pull-down on the
// scene), and the closed-court empty state.
package app.plead.android.courtroom

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.plead.android.designsystem.CourtFont
import app.plead.android.designsystem.PleadColor
import app.plead.android.designsystem.PleadRadius
import app.plead.android.designsystem.PleadSpacing
import app.plead.android.designsystem.PleadType
import app.plead.android.designsystem.pleadShadow
import app.plead.android.models.Role
import app.plead.android.models.TrialPhase
import kotlin.math.min

/** The transcript (sheet content): mahogany bar with a cream Done, the record on the court backdrop. */
@Composable
fun CourtTranscriptView(state: CourtroomState, onDismiss: () -> Unit) {
    val list = rememberLazyListState()
    // Header + (empty line) + one row per turn and a divider per phase change: scroll to the last turn on first show.
    val rows = buildList {
        state.turns.forEachIndexed { index, turn ->
            val phase = turn.phase
            if (phase != null && (index == 0 || state.turns[index - 1].phase != phase)) add(phase)
            add(turn)
        }
    }
    LaunchedEffect(Unit) {
        if (state.turns.isNotEmpty()) list.scrollToItem(rows.size) // header is item 0
    }
    Column(Modifier.fillMaxSize().background(PleadColor.courtBackdrop)) {
        CourtSheetTopBar(
            title = "Transcript", trailingTitle = "Done", onTrailing = onDismiss,
            background = PleadColor.mahogany, tint = PleadColor.cream, titleColor = PleadColor.cream,
        )
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(PleadSpacing.l),
            verticalArrangement = Arrangement.spacedBy(PleadSpacing.m),
        ) {
            item { CaseHeader(state) }
            if (state.turns.isEmpty()) {
                item {
                    Text(
                        "Nothing has been said yet.",
                        style = CourtFont.body,
                        color = CourtColor.creamSoft,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = PleadSpacing.xxl),
                    )
                }
            }
            items(rows.size) { i ->
                when (val row = rows[i]) {
                    is TrialPhase -> PhaseDivider(row)
                    is app.plead.android.models.Turn -> CourtBubbleRow(CourtBubbleModel(row, state))
                }
            }
        }
    }
}

@Composable
private fun CaseHeader(state: CourtroomState) {
    val parties = "${state.profile(Role.plaintiff).displayName} v ${state.profile(Role.defendant).displayName}"
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = PleadSpacing.s)
            .clearAndSetSemantics { contentDescription = "${state.kase.formattedNumber}. ${state.kase.title}. $parties" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            ScalesGlyph(size = 13.dp)
            Text(
                state.kase.formattedNumber.uppercase(),
                style = CourtFont.legal.copy(letterSpacing = PleadType.capsTracking.sp),
                color = CourtColor.creamSoft,
            )
        }
        Text(state.kase.title, style = CourtFont.caseTitle, color = PleadColor.cream, textAlign = TextAlign.Center)
        Text(parties, style = CourtFont.caseParties, color = CourtColor.creamSoft)
    }
}

@Composable
private fun PhaseDivider(phase: TrialPhase) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = PleadSpacing.xs).semantics { heading() },
        horizontalArrangement = Arrangement.spacedBy(PleadSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(CourtColor.panelRim))
        Text(
            CourtroomLogic.phaseTitle(phase).uppercase(),
            style = CourtFont.legal.copy(letterSpacing = PleadType.capsTracking.sp),
            color = CourtColor.creamSoft,
            maxLines = 1,
        )
        Box(Modifier.weight(1f).height(1.dp).background(CourtColor.panelRim))
    }
}

/**
 * No case is in the courtroom: the same painted room with the lamps down, the judge's chair empty and a CLOSED card on
 * the easel. Laid out in the full size it is given (the host extends it under the status bar and the tab bar).
 */
@Composable
fun CourtroomEmptyState(modifier: Modifier = Modifier, insets: CourtInsets = CourtInsets.zero) {
    BoxWithConstraints(modifier) {
        val full = Size(maxWidth.value, maxHeight.value)
        val z = CourtroomZones(full)
        val easel = z.rect(CourtroomZones.easel)
        Box(Modifier.fillMaxSize()) {
            CourtroomBackground(size = full, closed = true)

            // CLOSED sign hung on the easel.
            Box(
                Modifier.frameIn(
                    androidx.compose.ui.geometry.Rect(
                        easel.center.x - easel.width * 0.41f, easel.center.y - easel.height * 0.35f,
                        easel.center.x + easel.width * 0.41f, easel.center.y + easel.height * 0.35f,
                    ),
                ).goldFrame(radius = 8.dp).accessibilityHidden(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ScalesGlyph(size = 16.dp)
                    Text("CLOSED", style = CourtFont.displayM.copy(letterSpacing = 3.sp), color = PleadColor.cream)
                }
            }

            val cardShape = RoundedCornerShape(PleadRadius.card)
            Column(
                Modifier
                    .position(full.width / 2f, min(z.y(0.74f), full.height - insets.bottom - 110f))
                    .padding(horizontal = PleadSpacing.l)
                    .widthIn(max = 360.dp)
                    .pleadShadow(Color.Black.copy(alpha = 0.35f), radius = 14.dp, y = 6.dp, shape = cardShape)
                    .background(PleadColor.parchment, cardShape)
                    .border(1.dp, PleadColor.walnut.copy(alpha = 0.5f), cardShape)
                    .padding(PleadSpacing.xl)
                    .semantics(mergeDescendants = true) { },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(PleadSpacing.s),
            ) {
                Text(
                    "Court is not in session.",
                    style = CourtFont.displayM,
                    color = PleadColor.cocoa,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    "When a case reaches trial, the courtroom opens here. File a case from Home to summon your partner.",
                    style = CourtFont.callout,
                    color = PleadColor.walnut,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
