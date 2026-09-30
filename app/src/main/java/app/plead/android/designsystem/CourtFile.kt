// Port of ArgueWin/DesignSystem/CourtFile.swift: shared case-file primitives for Home / Cases / Us (CONTRACTS-v2
// amendment af). INTERFACE IS FIXED: add members, never rename/remove.
//
// Owed after the wave-2a merge (they need `CaseFlow` / `CaseStore` from services/): `CaseFileStatus.make(...)` (both
// overloads, the status matrix below, driven by `Case.nextAction`) and the `CaseStore` extension
// (`caseFileStatus(kase, now)`, `caseFileParties(kase)`, `caseFileAction(kase)`). In iOS the Cases agent owns
// `make`; port it verbatim into this file as `fun CaseFileStatus.Companion.make(...)` next to `remaining`.
//
// Status matrix (`make`), from `Case.nextAction` (settlement → judgement → court flow):
//
// | State                                    | kind         | message                  | nextStep                                  |
// |------------------------------------------|--------------|--------------------------|-------------------------------------------|
// | summoned, I'm the defendant              | needsYou     | Needs you · {countdown}  | Enter your plea                           |
// | summoned, plea deadline passed (plaint.) | needsYou     | Needs you                | Request default judgment                  |
// | summoned, waiting for the plea           | waiting      | Waiting for {partner}    | {Partner} to enter a plea                 |
// | defence, I'm the defendant               | needsYou     | Needs you · {countdown}  | File your defence                         |
// | defence, waiting                         | waiting      | Waiting for {partner}    | {Partner} to file their defence           |
// | scheduling, the proposal is mine to take | needsYou     | Needs you · {countdown}  | Agree the trial time                      |
// | scheduling, I proposed last              | scheduling   | Scheduling               | Waiting for {partner} to agree            |
// | scheduling, not a party                  | scheduling   | Scheduling               | Agree the trial time                      |
// | trial, my turn (opening/exhibits/close)  | needsYou     | Needs you · {countdown}  | Your turn in court                        |
// | trial, my turn in their exhibits phase   | needsYou     | Needs you · {countdown}  | Object or let it stand                    |
// | trial, my turn in cross-examination      | needsYou     | Needs you · {countdown}  | Answer the judge                          |
// | trial, their turn                        | waiting      | Waiting for {partner}    | {Partner} is giving their opening / …     |
// | trial, the judge has the floor           | waiting      | In court                 | The judge has the floor                   |
// | deliberating / awaiting verdict          | deliberating | Judge is deliberating    | Awaiting verdict (· due in {countdown})   |
// | verdict / appeal, to be read             | needsYou     | Needs you                | Hear the verdict                          |
// | verdict, I choose the judgement          | needsYou     | Needs you                | Choose the judgement                      |
// | verdict, judgement delivered to me       | needsYou     | Needs you                | Accept the judgement                      |
// | verdict, the other party is choosing     | waiting      | Waiting for {partner}    | {Partner} is choosing the judgement       |
// | verdict, tie, the court is choosing      | waiting      | Judgement pending        | The court is choosing the judgement       |
// | verdict, judgement outstanding           | waiting      | Judgement outstanding    | Mark it served once it's done             |
// | settlement offer awaits me               | needsYou     | Needs you                | Respond to the settlement offer           |
// | my settlement offer is out               | waiting      | Waiting for {partner}    | {Partner} to answer your settlement offer |
// | closed / guilty plea / default           | closed       | Verdict delivered        | View the ruling                           |
// | closed_settled                           | settled      | Settled outside court    | View the settlement                       |
// | mistrial (trial stopped)                 | stopped      | Trial stopped            | View the record                           |
//
// `{countdown}` is compact ("1d 5h left", "42m left") and omitted when the case has no deadline or it has passed.
package app.plead.android.designsystem

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.plead.android.models.Avatar
import app.plead.android.models.Case
import app.plead.android.models.Role
import java.time.Duration
import java.time.Instant

/** The case row as the case-file primitives see it (`Case` in Models.kt). */
typealias Kase = Case

/** Compact status + next step for a case as one user sees it. Never colour-only; `message` carries the meaning. */
data class CaseFileStatus(
    val kind: Kind,
    /** "Needs you · 1d 5h left", "Scheduling", "Judge is deliberating", "Verdict delivered". */
    val message: String,
    /** "File your defence", "Agree the trial time", "Awaiting verdict", "View the ruling". */
    val nextStep: String,
    /** Only when the case really has one (turn deadline, trial time, verdict time). */
    val deadline: Instant?,
) {
    enum class Kind { needsYou, waiting, scheduling, deliberating, closed, settled, stopped }

    val needsMe: Boolean get() = kind == Kind.needsYou

    /** The message read aloud: "needs you, 1 day 5 hours left" (the "·" and compact units expanded). */
    fun spokenMessage(now: Instant = Instant.now()): String {
        if (kind == Kind.needsYou) {
            val left = deadline?.let { spokenRemaining(it, now) }
            if (left != null) return "needs you, $left left"
            return "needs you"
        }
        return message.replace(" · ", ", ")
    }

    /** Sort key inside the docket's Open list: action first, then waiting, scheduling, deliberating. */
    val docketRank: Int
        get() = when (kind) {
            Kind.needsYou -> 0
            Kind.waiting -> 1
            Kind.scheduling -> 2
            Kind.deliberating -> 3
            Kind.closed, Kind.settled, Kind.stopped -> 4
        }

    companion object {
        private fun secondsUntil(date: Instant, now: Instant): Long = Math.floorDiv(Duration.between(now, date).toMillis(), 1000L)

        /** Compact time left: "2d", "1d 5h", "3h 12m", "42m", "1m". null once the moment has passed. */
        fun remaining(until: Instant, now: Instant): String? {
            val s = secondsUntil(until, now)
            if (s <= 0) return null
            val d = s / 86_400
            val h = (s % 86_400) / 3600
            val m = (s % 3600) / 60
            if (d > 0) return if (h > 0) "${d}d ${h}h" else "${d}d"
            if (h > 0) return if (m > 0) "${h}h ${m}m" else "${h}h"
            return "${maxOf(m, 1)}m"
        }

        /** Spoken form for TalkBack: "1 day 5 hours", "42 minutes". */
        fun spokenRemaining(until: Instant, now: Instant): String? {
            val s = secondsUntil(until, now)
            if (s <= 0) return null
            val d = s / 86_400
            val h = (s % 86_400) / 3600
            val m = (s % 3600) / 60
            fun unit(n: Long, word: String) = "$n $word${if (n == 1L) "" else "s"}"
            if (d > 0) return if (h > 0) "${unit(d, "day")} ${unit(h, "hour")}" else unit(d, "day")
            if (h > 0) return if (m > 0) "${unit(h, "hour")} ${unit(m, "minute")}" else unit(h, "hour")
            return unit(maxOf(m, 1), "minute")
        }
    }
}

// MARK: - Docket rules (sorting, counts)

/** Swift's `(kase: Case, status: CaseFileStatus)` tuple. */
data class CaseFileRow(val kase: Case, val status: CaseFileStatus)

object CaseFileDocket {
    /** Swift's `(open: Int, closed: Int)` tuple. */
    data class Counts(val open: Int, val closed: Int)

    /**
     * Open list order: action-required first by nearest deadline (no deadline last), then waiting /
     * scheduling / deliberating (each by nearest deadline, then most recently updated).
     */
    fun sortOpen(items: List<CaseFileRow>): List<CaseFileRow> = items.sortedWith { a, b ->
        if (a.status.docketRank != b.status.docketRank) return@sortedWith a.status.docketRank.compareTo(b.status.docketRank)
        val da = a.status.deadline ?: Instant.MAX
        val db = b.status.deadline ?: Instant.MAX
        if (da != db) return@sortedWith da.compareTo(db)
        if (a.kase.updatedAt != b.kase.updatedAt) return@sortedWith b.kase.updatedAt.compareTo(a.kase.updatedAt)
        b.kase.caseNumber.compareTo(a.kase.caseNumber)
    }

    /** Closed list order: newest first (closed time, else last update). */
    fun sortClosed(cases: List<Case>): List<Case> = cases.sortedWith { a, b ->
        val da = a.closedAt ?: a.updatedAt
        val db = b.closedAt ?: b.updatedAt
        if (da != db) db.compareTo(da) else b.caseNumber.compareTo(a.caseNumber)
    }

    /** (open, closed) counts for the segmented control. */
    fun counts(cases: List<Case>): Counts = Counts(cases.count { it.status.isOpen }, cases.count { it.status.isClosed })
}

// MARK: - Palette (brief tokens, the Court tab's palette)

object CourtFilePalette {
    val background = PleadBrandColor.warmCream
    val paper = PleadBrandColor.paperWhite
    val burgundy = PleadBrandColor.courtBurgundy
    val wine = PleadBrandColor.deepWine
    val mahogany = PleadBrandColor.mahogany
    val gold = PleadBrandColor.gold
    val blush = PleadBrandColor.blush
    val border = PleadBrandColor.mahogany.copy(alpha = 0.2f)
}

// MARK: - Case file card

/** Swift's `(label: String, perform: () -> Void)` action tuple. */
data class CaseFileAction(val label: String, val perform: () -> Unit)

/** Helpers of `CaseFileCard` (Swift statics). */
object CaseFileCard {
    /** "CASE #16 · SAM v. ALEX": names upper-cased, the "v." kept lower-case. */
    fun caption(number: Int, parties: String): String {
        val names = parties.split(" v. ").map { it.uppercase() }
        return "CASE #$number · ${names.joinToString(" v. ")}"
    }

    /** SF Symbol that doubles the status text (never the only signal); drawn through [SFSymbol]. */
    fun symbol(kind: CaseFileStatus.Kind): String = when (kind) {
        CaseFileStatus.Kind.needsYou -> "exclamationmark.circle.fill"
        CaseFileStatus.Kind.waiting -> "hourglass"
        CaseFileStatus.Kind.scheduling -> "calendar"
        CaseFileStatus.Kind.deliberating -> "scalemass"
        CaseFileStatus.Kind.closed -> "checkmark.seal"
        CaseFileStatus.Kind.settled -> "hands.and.sparkles"
        CaseFileStatus.Kind.stopped -> "stop.circle"
    }

    /** "Case 16, The Spoiler, Sam versus Alex, needs you, 1 day 5 hours left, file your defence". */
    fun accessibilitySummary(
        number: Int,
        title: String,
        parties: String,
        status: CaseFileStatus,
        detail: String? = null,
        now: Instant = Instant.now(),
    ): String {
        val parts = mutableListOf("Case $number", title, parties.replace(" v. ", " versus "), status.spokenMessage(now))
        if (!detail.isNullOrEmpty()) parts.add(detail)
        if (status.nextStep.isNotEmpty()) parts.add(status.nextStep.take(1).lowercase() + status.nextStep.drop(1))
        return parts.joinToString(", ")
    }

    /** iOS `dynamicTypeSize.isAccessibilitySize` (AX1 and up ≈ 1.6× body). */
    const val accessibilityFontScale: Float = 1.6f
}

/** One case file: number + parties, serif title, status, next step, optional action row. Tap = open the case. */
@Composable
fun CaseFileCard(
    number: Int,
    title: String,
    /** "Sam v. Alex" */
    parties: String,
    status: CaseFileStatus,
    modifier: Modifier = Modifier,
    me: Avatar? = null,
    partner: Avatar? = null,
    myRole: Role? = null,
    /** Direct action ("File your defence"); null = the card only opens the case. */
    action: CaseFileAction? = null,
    /** Optional extra line under the status (e.g. a closed case's outcome "You won" / "Tied"). */
    detail: String? = null,
    onOpen: () -> Unit,
) {
    val reduceMotion = accessibilityReduceMotion()
    val accessibilitySize = LocalDensity.current.fontScale >= CaseFileCard.accessibilityFontScale
    val shape = RoundedCornerShape(PleadRadius.card)
    val urgent = status.needsMe
    val summaryText = CaseFileCard.accessibilitySummary(number, title, parties, status, detail)
    val offsetPx = with(LocalDensity.current) { 6.dp.roundToPx() }

    Box(
        modifier
            .fillMaxWidth()
            .pleadShadow(CourtFilePalette.wine.copy(alpha = 0.05f), radius = 8.dp, y = 2.dp, shape = shape)
            .clip(shape)
            .background(CourtFilePalette.paper, shape)
            .border(1.dp, if (urgent) CourtFilePalette.burgundy.copy(alpha = 0.35f) else CourtFilePalette.border, shape),
    ) {
      Column(Modifier.fillMaxWidth()) {
        run {
            // Summary: the whole card body opens the case.
            Column(
                Modifier
                    .fillMaxWidth()
                    .caseFilePressStyle(onClick = onOpen, onClickLabel = "Opens the case")
                    .clearAndSetSemantics {
                        contentDescription = summaryText
                        role = androidx.compose.ui.semantics.Role.Button
                        onClick(label = "Opens the case") { onOpen(); true }
                    }
                    .testTag("casefile.$number")
                    .padding(
                        start = PleadSpacing.l + if (urgent) 4.dp else 0.dp,
                        end = PleadSpacing.l,
                        top = PleadSpacing.l,
                        bottom = if (action == null) PleadSpacing.l else PleadSpacing.m,
                    ),
                verticalArrangement = Arrangement.spacedBy(PleadSpacing.s),
            ) {
                if (accessibilitySize) {
                    Column(verticalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
                        CaseFileHeading(number, title, parties)
                        CaseFilePortraits(me, partner, myRole, alignEnd = false)
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(PleadSpacing.m), verticalAlignment = Alignment.Top) {
                        Box(Modifier.weight(1f)) { CaseFileHeading(number, title, parties) }
                        CaseFilePortraits(me, partner, myRole, alignEnd = true)
                    }
                }
                AnimatedContent(
                    targetState = status,
                    contentKey = { it.kind },
                    transitionSpec = {
                        if (reduceMotion) {
                            fadeIn(tween(200, easing = PleadMotion.easeInOut)) togetherWith fadeOut(tween(200, easing = PleadMotion.easeInOut))
                        } else {
                            (fadeIn(tween(220, easing = PleadMotion.easeOut)) + slideInVertically(tween(220, easing = PleadMotion.easeOut)) { offsetPx }) togetherWith
                                fadeOut(tween(220, easing = PleadMotion.easeOut))
                        }
                    },
                    label = "caseFileStatus",
                ) { s ->
                    CaseFileStatusBlock(s, urgent = s.needsMe, detail = detail, actionLabel = action?.label)
                }
            }
        }

        if (action != null) {
            Row(
                Modifier
                    .padding(start = PleadSpacing.l + if (urgent) 4.dp else 0.dp, end = PleadSpacing.l, bottom = PleadSpacing.l)
                    .fillMaxWidth()
                    .caseFilePressStyle(onClick = action.perform)
                    .semantics(mergeDescendants = true) { contentDescription = action.label }
                    .testTag("casefile.$number.action")
                    .defaultMinSize(minHeight = 48.dp)
                    .background(CourtFilePalette.burgundy, RoundedCornerShape(PleadRadius.button))
                    .padding(horizontal = PleadSpacing.l, vertical = PleadSpacing.m),
                horizontalArrangement = Arrangement.spacedBy(PleadSpacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(action.label, style = PleadType.uiButton, color = PleadBrandColor.warmCream, modifier = Modifier.weight(1f))
                Icon(SFSymbol.icon("arrow.right"), contentDescription = null, tint = PleadBrandColor.warmCream, modifier = Modifier.size(20.dp))
            }
        }
      }
        if (urgent) {
            Box(Modifier.matchParentSize()) {
                Box(Modifier.width(4.dp).fillMaxHeight().background(CourtFilePalette.burgundy))
            }
        }
    }
}

@Composable
private fun CaseFileHeading(number: Int, title: String, parties: String) {
    Column(verticalArrangement = Arrangement.spacedBy(PleadSpacing.xs)) {
        Text(
            CaseFileCard.caption(number, parties),
            style = PleadType.labelCaps.copy(letterSpacing = PleadType.capsTracking.sp),
            color = CourtFilePalette.mahogany,
        )
        Text(title, style = PleadType.displayM, color = CourtFilePalette.wine)
    }
}

@Composable
private fun CaseFilePortraits(me: Avatar?, partner: Avatar?, myRole: Role?, alignEnd: Boolean) {
    if (me == null && partner == null) return
    Column(
        Modifier.clearAndSetSemantics { },
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(PleadSpacing.xs),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(PleadSpacing.xs)) {
            if (me != null) CaseFilePortrait(me)
            if (partner != null) CaseFilePortrait(partner)
        }
        if (myRole != null) {
            Text(
                "You · ${if (myRole == Role.plaintiff) "Plaintiff" else "Defendant"}",
                style = PleadType.caption.copy(fontWeight = FontWeight.SemiBold),
                color = CourtFilePalette.mahogany,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .background(CourtFilePalette.background, CircleShape)
                    .border(1.dp, CourtFilePalette.border, CircleShape)
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun CaseFileStatusBlock(status: CaseFileStatus, urgent: Boolean, detail: String?, actionLabel: String?) {
    Column(Modifier.padding(top = PleadSpacing.xs), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val tint = if (urgent) CourtFilePalette.burgundy else CourtFilePalette.mahogany
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(SFSymbol.icon(CaseFileCard.symbol(status.kind)), contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
            Text(status.message, style = PleadType.metadataMedium.monospacedDigit(), color = tint)
        }
        if (!detail.isNullOrEmpty()) {
            Text(detail, style = PleadType.metadata, color = CourtFilePalette.mahogany)
        }
        // The action row already names the step; don't say it twice.
        if (status.nextStep.isNotEmpty() && status.nextStep != actionLabel) {
            Text(status.nextStep, style = PleadType.body.monospacedDigit(), color = PleadColor.cocoa)
        }
    }
}

/** A pixel portrait at integer scale (16 × 2 = 32 dp) on a small paper tile. */
@Composable
fun CaseFilePortrait(avatar: Avatar, modifier: Modifier = Modifier, scale: Int = 2) {
    val side: Dp = (PixelAvatar.side * scale).dp
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .background(PleadBrandColor.warmCream, shape)
            .border(1.dp, CourtFilePalette.border, shape)
            .padding(2.dp),
    ) {
        PixelAvatarView(avatar, size = side)
    }
}

/**
 * Swift `CaseFilePressStyle`: press feedback without a highlight flash (scale 0.985, not under Reduce Motion;
 * opacity 0.9), easeOut 0.12 s. Makes the element clickable.
 */
@Composable
fun Modifier.caseFilePressStyle(onClick: () -> Unit, onClickLabel: String? = null): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduceMotion = accessibilityReduceMotion()
    val scale by animateFloatAsState(if (pressed && !reduceMotion) 0.985f else 1f, tween(120, easing = PleadMotion.easeOut), label = "caseFilePressScale")
    val alpha by animateFloatAsState(if (pressed) 0.9f else 1f, tween(120, easing = PleadMotion.easeOut), label = "caseFilePressAlpha")
    return this
        .scale(scale)
        .alpha(alpha)
        .clickable(interactionSource = interaction, indication = null, onClickLabel = onClickLabel, onClick = onClick)
}

// MARK: - Tab header

/** The shared tab header: page title in Fraunces (or the wordmark on Home), optional trailing control. */
@Composable
fun CourtTabHeader(
    title: String?,
    modifier: Modifier = Modifier,
    showsWordmark: Boolean = false,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(horizontal = CourtTabHeader.gutter)
            .padding(top = PleadSpacing.s, bottom = PleadSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(PleadSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showsWordmark) {
            PleadLogo(variant = PleadLogo.Variant.wordmarkOnly, width = 96.dp)
        }
        if (title != null) {
            Text(title, style = PleadType.displayL, color = CourtFilePalette.wine, modifier = Modifier.semantics { heading() })
        }
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

object CourtTabHeader {
    /** Shared horizontal gutter for the three tabs. */
    val gutter: Dp = 20.dp
}

@Preview(name = "Case files", widthDp = 402, heightDp = 700)
@Composable
private fun CaseFilesPreview() {
    CaseFilesPreviewContent()
}

/** The Swift `#Preview("Case files")` body (also in the component gallery). */
@Composable
fun CaseFilesPreviewContent(modifier: Modifier = Modifier) {
    val meAvatar = Avatar.default
    val partnerAvatar = Avatar(skin = 4, hair = 0, hairstyle = Avatar.Hairstyle.curly, top = 5, outfit = Avatar.Outfit.hoodie)
    Column(
        modifier.background(CourtFilePalette.background).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CourtTabHeader(title = "The docket")
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CaseFileCard(
                number = 16, title = "The Spoiler", parties = "Alex v. Sam",
                status = CaseFileStatus(
                    CaseFileStatus.Kind.needsYou, "Needs you · 1d 5h left", "File your defence",
                    remember { Instant.now().plusSeconds(29 * 3600) },
                ),
                me = meAvatar, partner = partnerAvatar, myRole = Role.defendant,
                action = CaseFileAction("File your defence") {}, onOpen = {},
            )
            CaseFileCard(
                number = 21, title = "The Instagram Like Incident", parties = "Sam v. Alex",
                status = CaseFileStatus(CaseFileStatus.Kind.deliberating, "Judge is deliberating", "Awaiting verdict", null),
                me = meAvatar, partner = partnerAvatar, myRole = Role.plaintiff, onOpen = {},
            )
        }
        Spacer(Modifier.height(12.dp))
    }
}
