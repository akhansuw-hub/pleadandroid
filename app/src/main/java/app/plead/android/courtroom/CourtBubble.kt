// Port of ArgueWin/Courtroom/CourtBubble.swift: courtroom speech bubbles (CONTRACTS-v2 §4):
//   judge      Deep Mahogany fill, Warm Cream text, tiny gold scales; serif italic for rulings
//   party      Paper White fill, Dark Cocoa text, role chip (burgundy PLAINTIFF / walnut DEFENDANT) + phase chip
//   objection  Parchment card, bold burgundy OBJECTION header, SUSTAINED / OVERRULED stamp
//   safety     plain neutral system card, no persona styling
// In the scene a bubble enters with `CourtRevealBubble` (fade + scale + rise) and its text reveals line by line
// through `reveal` (amendment x); the transcript passes no reveal and shows text at once.
@file:Suppress("ClassName")

package app.plead.android.courtroom

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.plead.android.designsystem.CourtFont
import app.plead.android.designsystem.ExhibitThumbnail
import app.plead.android.designsystem.PleadColor
import app.plead.android.designsystem.PleadRadius
import app.plead.android.designsystem.PleadType
import app.plead.android.designsystem.SFSymbol
import app.plead.android.designsystem.TextStyleKind
import app.plead.android.designsystem.monospacedDigit
import app.plead.android.designsystem.pleadShadow
import app.plead.android.models.Exhibit
import app.plead.android.models.JudgePersona
import app.plead.android.models.ObjectionReason
import app.plead.android.models.ObjectionRuling
import app.plead.android.models.Role
import app.plead.android.models.Speaker
import app.plead.android.models.Turn
import java.net.URI
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

sealed class CourtTail {
    data object down : CourtTail()
    data object bottomLeading : CourtTail()
    data object bottomTrailing : CourtTail()
    data object none : CourtTail()

    /**
     * Pokes out of the left / right edge at `y` points from the top (bubble in the centre column, speaker beside it).
     * Drawn outside the frame, so it takes no layout space.
     */
    data class sideLeading(val y: Float) : CourtTail()
    data class sideTrailing(val y: Float) : CourtTail()

    val isSide: Boolean get() = this is sideLeading || this is sideTrailing
}

/** Rounded bubble (radius PleadRadius.bubble) with a small tail pointing at the speaker. */
class CourtBubbleShape(
    val tail: CourtTail,
    /** Horizontal tail position for `down`, as a fraction of the width. */
    val tailX: Float = 0.5f,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val d = density.density
        val t = tailSize * d
        val bodyH = if (tail == CourtTail.none || tail.isSide) size.height else size.height - t
        val radius = min(PleadRadius.bubble.value * d, bodyH / 2f)
        val box = Path()
        box.addRoundRect(RoundRect(0f, 0f, size.width, bodyH, radius, radius))
        val minX = 0f
        val maxX = size.width
        val maxY = bodyH
        val tp = Path()
        val x: Float
        when (tail) {
            CourtTail.none -> return Outline.Generic(box)
            is CourtTail.sideLeading, is CourtTail.sideTrailing -> {
                val leading = tail is CourtTail.sideLeading
                val y = (if (tail is CourtTail.sideLeading) tail.y else (tail as CourtTail.sideTrailing).y) * d
                val cy = min(max(y, radius + 8 * d), bodyH - radius - 4 * d)
                val edge = if (leading) minX + 1 * d else maxX - 1 * d
                val dir = if (leading) -1f else 1f
                tp.moveTo(edge, cy - 8 * d)
                tp.lineTo(edge, cy + 8 * d)
                tp.lineTo(edge + dir * (t + 1 * d), cy + 7 * d)
                tp.close()
                box.addPath(tp)
                return Outline.Generic(box)
            }
            CourtTail.down -> x = min(max(minX + size.width * tailX, minX + radius + 10 * d), maxX - radius - 10 * d)
            CourtTail.bottomLeading -> x = minX + 30 * d
            CourtTail.bottomTrailing -> x = maxX - 30 * d
        }
        // Chunky, slightly stepped tail: reads as pixel-adjacent without being a pixel font.
        tp.moveTo(x - 9 * d, maxY - 1 * d)
        tp.lineTo(x + 9 * d, maxY - 1 * d)
        val tipDx = when (tail) {
            CourtTail.bottomTrailing -> 4f
            CourtTail.bottomLeading -> -4f
            else -> 0f
        }
        tp.lineTo(x + tipDx * d, size.height)
        tp.close()
        box.addPath(tp)
        return Outline.Generic(box)
    }

    companion object {
        const val tailSize: Float = 9f
    }
}

/** Fill + centred stroke of a bubble shape (SwiftUI `.background { shape.fill; shape.stroke }`). */
internal fun Modifier.bubbleBackground(shape: Shape, fill: Color, stroke: Color, lineWidth: Dp): Modifier = drawBehind {
    val outline = shape.createOutline(size, layoutDirection, this)
    drawOutline(outline, fill)
    drawOutline(outline, stroke, style = Stroke(lineWidth.toPx()))
}

/** Resolved presentation for one turn (names, chips). Built from state. */
class CourtBubbleModel(val turn: Turn, state: CourtroomState) {
    val kind: CourtBubbleKind = CourtroomLogic.bubbleKind(turn)
    val speakerName: String
    val chip: String? = CourtroomLogic.chipTitle(turn, state.exhibits)
    val questions: List<String> = if (turn.speaker == Speaker.judge) CourtroomLogic.questions(turn) else emptyList()
    val isRuling: Boolean = CourtroomLogic.isRulingText(turn)

    /** For objection cards: the judge's ruling on that exhibit, once known. */
    val ruling: ObjectionRuling?
    val judgePersona: JudgePersona = state.judgePersona

    /** The exhibit this turn refers to (party bubbles show it inline when the easel is mini). */
    val exhibit: Exhibit? = state.exhibit(turn.exhibitId)
    val exhibitURL: URI? = turn.exhibitId?.let { state.exhibitURLs[it] }

    val id: UUID get() = turn.id

    init {
        val role = CourtroomLogic.role(turn.speaker)
        speakerName = if (role != null) state.profile(role).displayName else state.judgePersona.displayName
        val ex = state.exhibit(turn.exhibitId)
        ruling = if (kind is CourtBubbleKind.objection && ex != null) CourtroomLogic.stamps(ex, state.turns).ruling else null
    }

    val side: Role?
        get() = when (val k = kind) {
            is CourtBubbleKind.party -> k.role
            is CourtBubbleKind.objection -> k.role
            is CourtBubbleKind.pass -> k.role
            else -> null
        }

    val phaseName: String? get() = turn.phase?.let { CourtroomLogic.phaseTitle(it) }

    /**
     * VoiceOver: speaker (and side), phase, then the words. "Aria, plaintiff, Plaintiff's exhibits, Exhibit A: …";
     * "Judge Wigsworth, Cross-examination. Question 1: …".
     */
    val accessibilityText: String
        get() {
            val phase = phaseName?.let { ", $it" } ?: ""
            val exhibitPart = exhibit?.let { ", ${it.displayName}" } ?: ""
            val roleName = side?.let { ", ${CourtroomLogic.roleTitle(it).lowercase()}" } ?: ""
            return when (val k = kind) {
                CourtBubbleKind.safety -> "Notice from the court: ${turn.body}"
                is CourtBubbleKind.objection -> {
                    val r = k.reason?.let { " Reason: ${it.title}." } ?: ""
                    val rul = ruling?.let { " ${it.rawValue.swiftCapitalized()}." } ?: ""
                    "Objection from $speakerName$roleName$phase$exhibitPart.$r ${turn.body}$rul"
                }
                is CourtBubbleKind.pass ->
                    "$speakerName$roleName$phase: does not object${exhibit?.let { " to ${it.displayName}" } ?: ""}."
                is CourtBubbleKind.judgeRuling -> {
                    // "Sustained." once (the ruling text usually opens with it).
                    val r = k.ruling?.rawValue?.swiftCapitalized()
                        ?.let { if (turn.body.lowercase().startsWith(it.lowercase())) null else " $it." } ?: ""
                    "$speakerName$phase, ruling$exhibitPart.$r ${turn.body}"
                }
                CourtBubbleKind.judge -> {
                    if (questions.isNotEmpty()) {
                        val qs = questions.mapIndexed { i, q -> "Question ${i + 1}: $q" }.joinToString(". ")
                        "$speakerName$phase. $qs"
                    } else {
                        "$speakerName$phase: ${turn.body}"
                    }
                }
                is CourtBubbleKind.party -> "$speakerName$roleName$phase$exhibitPart: ${turn.body}"
            }
        }

    override fun equals(other: Any?): Boolean =
        other is CourtBubbleModel && other.turn == turn && other.kind == kind && other.speakerName == speakerName &&
            other.chip == chip && other.questions == questions && other.ruling == ruling &&
            other.judgePersona == judgePersona && other.exhibit == exhibit && other.exhibitURL == exhibitURL

    override fun hashCode(): Int = listOf(turn, kind, speakerName, chip, questions, ruling, judgePersona, exhibit, exhibitURL).hashCode()
}

@Composable
fun CourtBubbleView(
    model: CourtBubbleModel,
    modifier: Modifier = Modifier,
    /** Limit lines in the scene; the transcript passes null. */
    lineLimit: Int? = null,
    /** Tail override (the scene points judge bubbles down at the bench). */
    tail: CourtTail? = null,
    tailX: Float = 0.5f,
    /** Judge fill opacity (the scene lets the painted banner show faintly through). */
    fillOpacity: Float = 1f,
    /** Lines per numbered question (defaults to 2 in the scene, unlimited in the transcript). */
    questionLineLimit: Int? = null,
    /** Scene judge bubble: body text may give up lines to fit the height it is offered. */
    compressible: Boolean = false,
    /** Scene party bubble on a tight screen: name, role and one line of text on a single row. */
    compactParty: Boolean = false,
    /** Party bubble merged with the exhibit it presents: a thumbnail row under the text. */
    showsExhibitRow: Boolean = false,
    /** Staged line reveal (scene only). Visual only: the accessibility label is complete from the start. */
    reveal: CourtTextReveal? = null,
) {
    val resolvedTail: CourtTail = tail ?: when (val k = model.kind) {
        CourtBubbleKind.judge, is CourtBubbleKind.judgeRuling -> CourtTail.none
        is CourtBubbleKind.party -> if (k.role == Role.plaintiff) CourtTail.bottomLeading else CourtTail.bottomTrailing
        else -> CourtTail.none
    }
    val bottomPad = if (resolvedTail == CourtTail.none || resolvedTail.isSide) 12.dp else (12 + CourtBubbleShape.tailSize).dp
    val p = BubbleParams(model, lineLimit, resolvedTail, tailX, fillOpacity, questionLineLimit, compressible, compactParty, showsExhibitRow, reveal, bottomPad)
    Box(modifier.clearAndSetSemantics { contentDescription = model.accessibilityText }) {
        when (val k = model.kind) {
            CourtBubbleKind.safety -> CourtSafetyCard(body = model.turn.body)
            is CourtBubbleKind.pass -> PassLine(model)
            is CourtBubbleKind.objection -> ObjectionCard(p, k.reason)
            CourtBubbleKind.judge, is CourtBubbleKind.judgeRuling -> JudgeBubble(p)
            is CourtBubbleKind.party -> PartyBubble(p, k.role)
        }
    }
}

private class BubbleParams(
    val model: CourtBubbleModel,
    val lineLimit: Int?,
    val tail: CourtTail,
    val tailX: Float,
    val fillOpacity: Float,
    val questionLineLimit: Int?,
    val compressible: Boolean,
    val compactParty: Boolean,
    val showsExhibitRow: Boolean,
    val reveal: CourtTextReveal?,
    val bottomPad: Dp,
)

// MARK: Judge

@Composable
private fun JudgeBubble(p: BubbleParams) {
    val m = p.model
    val shape = CourtBubbleShape(p.tail, p.tailX)
    Column(
        Modifier
            .fillMaxWidth()
            .pleadShadow(Color.Black.copy(alpha = 0.3f), radius = 6.dp, y = 3.dp, shape = shape)
            .bubbleBackground(shape, PleadColor.mahogany.copy(alpha = p.fillOpacity), PleadColor.cocoa, 1.5.dp)
            .padding(start = 14.dp, end = 14.dp, top = 11.dp, bottom = p.bottomPad),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            ScalesGlyph(size = 13.dp)
            Text(m.speakerName, style = CourtFont.judgeName, color = CourtColor.creamSoft, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.weight(1f).width(4.dp))
            val k = m.kind
            if (k is CourtBubbleKind.judgeRuling && k.ruling != null) {
                Text(
                    k.ruling.rawValue.uppercase(),
                    style = CourtFont.legal.copy(letterSpacing = PleadType.capsTracking.sp),
                    color = PleadColor.cream,
                    maxLines = 1,
                    modifier = Modifier
                        .border(1.5.dp, PleadColor.cream.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            } else if (m.chip != null) {
                CourtPhaseChip(title = m.chip, tint = CourtColor.creamSoft)
            }
        }
        if (m.questions.isEmpty()) {
            if (m.turn.body.isNotEmpty()) {
                CourtRevealText(
                    m.turn.body,
                    style = if (m.isRuling) CourtFont.ruling else CourtFont.judgeSpeech,
                    color = PleadColor.cream,
                    reveal = p.reveal,
                    maxLines = p.lineLimit ?: Int.MAX_VALUE,
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                m.questions.forEachIndexed { i, q ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CourtRevealText(
                            "${i + 1}.",
                            style = CourtFont.caption.monospacedDigit(),
                            color = CourtColor.creamSoft,
                            reveal = p.reveal,
                            block = i,
                            modifier = Modifier.alignByBaseline(),
                        )
                        CourtRevealText(
                            q,
                            style = CourtFont.judgeQuestion,
                            color = PleadColor.cream,
                            reveal = p.reveal,
                            block = i,
                            maxLines = p.questionLineLimit ?: (if (p.lineLimit != null) 2 else Int.MAX_VALUE),
                            modifier = Modifier.alignByBaseline(),
                        )
                    }
                }
            }
        }
    }
}

// MARK: Party

@Composable
private fun PartyBubble(p: BubbleParams, role: Role) {
    val m = p.model
    val shape = CourtBubbleShape(p.tail, p.tailX)
    if (p.compactParty) {
        Row(
            Modifier
                .fillMaxWidth()
                .pleadShadow(Color.Black.copy(alpha = 0.28f), radius = 6.dp, y = 3.dp, shape = shape)
                .bubbleBackground(shape, PleadColor.paperWhite, PleadColor.cocoa.copy(alpha = 0.85f), 1.5.dp)
                .padding(
                    start = 12.dp, end = 12.dp, top = 9.dp,
                    bottom = if (p.tail == CourtTail.none || p.tail.isSide) 9.dp else (9 + CourtBubbleShape.tailSize).dp,
                ),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(m.speakerName, style = CourtFont.partyName, color = PleadColor.cocoa, maxLines = 1, softWrap = false)
            CourtRoleChip(role)
            CourtRevealText(
                m.turn.body.ifEmpty { m.chip ?: "" },
                style = CourtFont.speech,
                color = PleadColor.cocoa,
                reveal = p.reveal,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }
    } else {
        Column(
            Modifier
                .fillMaxWidth()
                .pleadShadow(Color.Black.copy(alpha = 0.28f), radius = 6.dp, y = 3.dp, shape = shape)
                .bubbleBackground(shape, PleadColor.paperWhite, PleadColor.cocoa.copy(alpha = 0.85f), 1.5.dp)
                .padding(start = 14.dp, end = 14.dp, top = 11.dp, bottom = p.bottomPad),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Narrow (centre-column) bubbles drop the phase chip rather than squeezing the name.
            ViewThatFits(candidates = listOf({ PartyHeader(m, role, m.chip) }, { PartyHeader(m, role, null) }))
            if (m.turn.body.isNotEmpty()) {
                CourtRevealText(
                    m.turn.body,
                    style = CourtFont.speech,
                    color = PleadColor.cocoa,
                    reveal = p.reveal,
                    maxLines = p.lineLimit ?: Int.MAX_VALUE,
                    modifier = if (p.compressible) Modifier.weight(1f, fill = false) else Modifier,
                )
            }
            if (p.showsExhibitRow && m.exhibit != null) {
                ExhibitRow(m, m.exhibit)
            }
        }
    }
}

@Composable
private fun PartyHeader(m: CourtBubbleModel, role: Role, chip: String?) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(m.speakerName, style = CourtFont.partyName, color = PleadColor.cocoa, maxLines = 1, softWrap = false)
        CourtRoleChip(role)
        if (chip != null) {
            Spacer(Modifier.width(4.dp).weight(1f))
            CourtPhaseChip(title = chip, tint = PleadColor.walnut)
        }
    }
}

@Composable
private fun ExhibitRow(m: CourtBubbleModel, ex: Exhibit) {
    val thumbShape = RoundedCornerShape(7.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .background(PleadColor.parchment.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
            .padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExhibitThumbnail(
            type = ex.type, caption = ex.caption, body = ex.body, imageURL = m.exhibitURL?.toString(), compact = true,
            modifier = Modifier
                .size(44.dp)
                .clip(thumbShape)
                .border(1.dp, PleadColor.walnut.copy(alpha = 0.35f), thumbShape),
        )
        Column(verticalArrangement = Arrangement.spacedBy(1.dp), modifier = Modifier.weight(1f)) {
            Text(
                ex.displayName.uppercase(),
                style = CourtFont.legal.copy(letterSpacing = PleadType.capsTracking.sp),
                color = PleadColor.burgundy,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                ex.caption.ifEmpty { ex.body ?: "" },
                style = CourtFont.exhibitTitle,
                color = PleadColor.walnut,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// MARK: Objection

@Composable
private fun ObjectionCard(p: BubbleParams, reason: ObjectionReason?) {
    val m = p.model
    val shape = RoundedCornerShape(PleadRadius.tile)
    Box {
        Box(
            Modifier
                .fillMaxWidth()
                .pleadShadow(Color.Black.copy(alpha = 0.28f), radius = 6.dp, y = 3.dp, shape = shape)
                .background(PleadColor.parchment, shape)
                .border(2.dp, PleadColor.burgundy.copy(alpha = 0.8f), shape)
                .padding(horizontal = 14.dp, vertical = if (p.compactParty) 9.dp else 11.dp),
        ) {
            if (p.compactParty) {
                // One row on a tight screen; the full objection is a tap away in the transcript. The reason matters
                // more than the name, so the name goes first when space runs out.
                ViewThatFits(candidates = listOf({ CompactObjection(m, reason, name = true) }, { CompactObjection(m, reason, name = false) }))
            } else {
                ObjectionBody(p, reason)
            }
        }
        // Transcript only: in the scene the stamp lives inside the easel card.
        if (m.ruling != null && p.lineLimit == null) {
            CourtStamp.ruling(m.ruling, modifier = Modifier.align(Alignment.BottomEnd).offset(x = (-10).dp, y = 12.dp))
        }
    }
}

@Composable
private fun CompactObjection(m: CourtBubbleModel, reason: ObjectionReason?, name: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("OBJECTION", style = CourtFont.headline.copy(letterSpacing = 1.2.sp), color = PleadColor.burgundy, maxLines = 1, softWrap = false)
        if (name) {
            Text(m.speakerName, style = CourtFont.partyName, color = PleadColor.cocoa, maxLines = 1, softWrap = false)
        }
        m.side?.let { CourtRoleChip(it) }
        if (reason != null) {
            Text("· ${reason.title}", style = CourtFont.caption, color = PleadColor.walnut, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun ObjectionBody(p: BubbleParams, reason: ObjectionReason?) {
    val m = p.model
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text("OBJECTION", style = CourtFont.headline.copy(letterSpacing = 1.5.sp), color = PleadColor.burgundy,
                modifier = Modifier.alignByBaseline())
            Spacer(Modifier.width(4.dp).weight(1f))
            val chip = if (m.turn.exhibitId != null) m.chip else null
            if (chip != null && chip != "Objection") {
                CourtPhaseChip(title = chip, tint = PleadColor.walnut, modifier = Modifier.alignByBaseline())
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(m.speakerName, style = CourtFont.partyName, color = PleadColor.cocoa)
            m.side?.let { CourtRoleChip(it) }
            if (reason != null) {
                Text("· ${reason.title}", style = CourtFont.caption, color = PleadColor.walnut, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (m.turn.body.isNotEmpty()) {
            CourtRevealText(
                m.turn.body,
                style = CourtFont.speech,
                color = PleadColor.cocoa,
                reveal = p.reveal,
                maxLines = p.lineLimit ?: Int.MAX_VALUE,
            )
        }
    }
}

// MARK: Pass

@Composable
private fun PassLine(m: CourtBubbleModel) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .pleadShadow(Color.Black.copy(alpha = 0.2f), radius = 4.dp, y = 2.dp, shape = shape)
            .background(PleadColor.paperWhite, shape)
            .border(1.dp, PleadColor.cocoa.copy(alpha = 0.35f), shape)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(SFSymbol.icon("hand.raised.slash"), contentDescription = null, tint = PleadColor.cocoa, modifier = Modifier.size(13.dp))
        Text("${m.speakerName} does not object.", style = CourtFont.footnote, color = PleadColor.cocoa)
    }
}

// MARK: - Safety valve (plain, no persona)

@Composable
fun CourtSafetyCard(body: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(PleadRadius.tile)
    Column(
        modifier
            .fillMaxWidth()
            .background(CourtColor.neutralCard, shape)
            .border(1.dp, CourtColor.neutralStroke, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "A note from Plead",
            style = PleadType.text(17f, FontWeight.SemiBold, relativeTo = TextStyleKind.headline),
            color = CourtColor.neutralText,
            modifier = Modifier.semantics { heading() },
        )
        Text(body, style = PleadType.text(17f, relativeTo = TextStyleKind.body), color = CourtColor.neutralText)
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val sub = PleadType.text(15f, relativeTo = TextStyleKind.subheadline)
                Text("If you need support (UK):", style = sub.copy(fontWeight = FontWeight.SemiBold), color = CourtColor.neutralSubtle)
                Text("Refuge: 0808 2000 247 (24 hours)", style = sub, color = CourtColor.neutralSubtle)
                Text("Samaritans: 116 123", style = sub, color = CourtColor.neutralSubtle)
                Text("Relate: relate.org.uk", style = sub, color = CourtColor.neutralSubtle)
            }
        }
    }
}

/** Transcript row: judge centred, plaintiff left, defendant right. */
@Composable
fun CourtBubbleRow(model: CourtBubbleModel, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth()) {
        when (model.side) {
            Role.defendant -> Spacer(Modifier.width(40.dp))
            null -> Spacer(Modifier.width(12.dp))
            else -> Unit
        }
        SelectionContainer(Modifier.weight(1f)) {
            Box(
                Modifier.fillMaxWidth(),
                contentAlignment = when (model.side) {
                    Role.plaintiff -> Alignment.CenterStart
                    Role.defendant -> Alignment.CenterEnd
                    null -> Alignment.Center
                },
            ) {
                CourtBubbleView(model)
            }
        }
        when (model.side) {
            Role.plaintiff -> Spacer(Modifier.width(40.dp))
            null -> Spacer(Modifier.width(12.dp))
            else -> Unit
        }
    }
}
