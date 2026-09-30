// Port of ArgueWin/Courtroom/CourtMiddleBand.swift: the vertical budget for the courtroom's middle band: everything
// between the bench nameplate and the dock. Top to bottom it holds (a) the party bubble, (b) the easel exhibit and
// (c) the podiums (avatars + name tags). Nothing in it may overlap:
//
//   • The podium avatars and name tags never move and are never covered.
//   • Above the avatars' heads the band is full width; below that only the centre column between the two avatars is
//     free, and below the name tags' top only the gap between the tags.
//   • A LIVE exhibit (just shown, objection pending, ruling just landed) gets the FULL card in the centre column,
//     bottom edge above the name tags; the party bubble above it drops to one row. If even that leaves the card under
//     `fullCardMin`, the card collapses to the MINI frame.
//   • Otherwise the easel keeps a MINI frame (96×72) that may sit low, between the name tags, and the party bubble
//     hangs in the centre column with up to 3 lines (plus an inline exhibit row when the turn presents an exhibit),
//     giving up lines, then the exhibit row, then dropping to one row.
//
// Shrink order: exhibit card (ideal → min → mini) → bubble (3 lines → 1 → single row). Pure geometry, so the scene
// stays a function of state + measured sizes.
@file:Suppress("ClassName", "EnumEntryName")

package app.plead.android.courtroom

import androidx.compose.ui.geometry.Rect
import app.plead.android.models.Role
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class MiddleBand(
    zones: CourtroomZones,
    nameplate: Rect,
    dockTop: Float,
    tagHeight: Float,
    metrics: Metrics,
    party: Party?,
    hasExhibit: Boolean,
    exhibitLive: Boolean,
    measuredPartyHeight: Float,
) {
    /** Text-size dependent heights (default sizes × the capped font scale). */
    class Metrics(
        val line: Float,
        val scale: Float,
        /**
         * Accessibility text sizes (AX1…AX5): the band degrades to a one-line bubble and the mini easel (the full card's
         * type would crowd the podiums; a tap opens the exhibit in full).
         */
        val degraded: Boolean = false,
    ) {
        /** Party bubble without body lines: paddings + name / role header. */
        val partyBase: Float get() = 48 * scale

        /** Inline exhibit row in a party bubble (44 pt thumbnail + padding + spacing). */
        val exhibitRow: Float get() = 60 + 30 * (scale - 1)

        /** Objection card without body lines. */
        val objectionBase: Float get() = 72 * scale

        /** One-row party / objection bubble (no tail). */
        val compactRow: Float get() = 40 * scale

        /** "Sam does not object." line. */
        val passLine: Float get() = 34 * scale

        /** Below this the full card collapses to the mini frame. */
        val fullCardMin: Float = 80f
        val fullCardIdeal: Float = 150f

        companion object {
            /** SF Pro's line height as a multiple of the point size (UIFont.lineHeight / pointSize ≈ 1.1934). */
            const val lineHeightFactor = 1.1934f

            /** Swift `Metrics(dynamicType:)`: callout (16) line height at the capped size (party bubbles cap at xLarge). */
            fun of(dynamicType: DynamicTypeSize): Metrics {
                val capped = if (dynamicType > DynamicTypeSize.xLarge) DynamicTypeSize.xLarge else dynamicType
                val current = 16f * capped.scale * lineHeightFactor
                val base = 16f * lineHeightFactor
                return Metrics(line = ceil(current), scale = max(1f, current / base), degraded = dynamicType.isAccessibilitySize)
            }
        }
    }

    sealed class Tail {
        data object speakerCorner : Tail()
        data class side(val y: Float) : Tail()
        data object none : Tail()
    }

    data class BubbleSlot(
        /** The container the bubble is laid out in (top-aligned; the bubble may be shorter). */
        val rect: Rect,
        val lines: Int,
        val compact: Boolean,
        val exhibitRow: Boolean,
        /** Centre-column bubble (vs. full-width row hung from the speaker's side). */
        val centred: Boolean,
        val tail: Tail,
    )

    data class Party(val kind: CourtBubbleKind, val role: Role, val refersToExhibit: Boolean)

    var bubble: BubbleSlot? = null
        private set
    var easelRect: Rect? = null
        private set
    var fullCard: Boolean = false
        private set

    /** Band limits (exposed for debugging and tests). */
    val top: Float = nameplate.bottom + 4
    val tagTop: Float = CourtPodiumParty.tagCenter(zones, Role.plaintiff).y - tagHeight / 2
    val column: ClosedFloatingPointRange<Float>

    init {
        val z = zones
        val m = metrics
        val W = z.size.width
        // Full card and centre-column bubble end above the name tags (they overlap the tags' x-range).
        val tagFloor = min(tagTop, dockTop) - gap
        // The mini frame is narrow enough to sit between the tags, down to just above the dock.
        val miniFloor = dockTop - 8
        val pa = z.avatarFrame(Role.plaintiff)
        val da = z.avatarFrame(Role.defendant)
        // Visible sprite bodies sit ~10% inside their frames.
        val colL = pa.right - pa.width * 0.1f + gap
        val colR = da.left + da.width * 0.1f - gap
        column = colL..max(colL + 1, colR)
        val colW = colR - colL
        val board = z.rect(CourtroomZones.easel)
        val headY = min(pa.top, da.top) + pa.height * 0.3f
        layout(W, m, party, hasExhibit, exhibitLive, measuredPartyHeight, tagFloor, miniFloor, colL, colW, board, headY)
    }

    private fun layout(
        W: Float, m: Metrics, party: Party?, hasExhibit: Boolean, exhibitLive: Boolean, measuredPartyHeight: Float,
        tagFloor: Float, miniFloor: Float, colL: Float, colW: Float, board: Rect, headY: Float,
    ) {
        // (1) Live exhibit: full card, one-row bubble above it.
        if (hasExhibit && exhibitLive && !m.degraded) {
            var rowH = 0f
            var tailed = false
            if (party != null) {
                when (party.kind) {
                    is CourtBubbleKind.pass -> rowH = m.passLine
                    is CourtBubbleKind.party -> {
                        rowH = m.compactRow; tailed = true
                    }
                    else -> rowH = m.compactRow
                }
            }
            val cardTop = if (party == null) top + 2 else top + rowH + gap
            val avail = tagFloor - cardTop
            if (avail >= m.fullCardMin) {
                val h = min(avail, m.fullCardIdeal)
                val w = min(W * 0.6f, colW)
                val y = if (party == null) cardTop + (avail - h) / 2 else cardTop
                easelRect = Rect(board.center.x - w / 2, y, board.center.x + w / 2, y + h)
                fullCard = true
                if (party != null) {
                    // Full width above the avatars; the tail sits at the outer corner, beside the card.
                    val bw = min(W * 0.74f, 320f)
                    val x = if (party.role == Role.plaintiff) 12f else W - 12 - bw
                    val th = if (tailed) CourtBubbleShape.tailSize else 0f
                    bubble = BubbleSlot(
                        rect = Rect(x, top, x + bw, top + rowH + th), lines = 1, compact = true,
                        exhibitRow = false, centred = false, tail = if (tailed) Tail.speakerCorner else Tail.none,
                    )
                }
                return
            }
        }

        // (2) Mini easel (or none) with a centre-column bubble.
        val mini = CourtEaselMini.outer
        val bubbleFloor = if (hasExhibit) min(tagFloor, miniFloor - mini.height - gap) else tagFloor
        val avail = max(bubbleFloor - top, m.compactRow)
        if (party != null) {
            var lines = 1
            var compact = false
            var row = false
            when (party.kind) {
                is CourtBubbleKind.party -> {
                    val free = avail - m.partyBase
                    if (m.degraded) {
                        // One line of text under the name (or a single row when even that won't fit).
                        if (free < m.line) compact = true
                    } else if (party.refersToExhibit && ((free - m.exhibitRow) / m.line).toInt() >= 1) {
                        row = true
                        lines = min(3, ((free - m.exhibitRow) / m.line).toInt())
                    } else if ((free / m.line).toInt() >= 1) {
                        lines = min(3, (free / m.line).toInt())
                    } else {
                        compact = true
                    }
                }
                is CourtBubbleKind.objection -> {
                    val n = ((avail - m.objectionBase) / m.line).toInt()
                    // Large text: the single-row card (OBJECTION · side · reason); the rest is a tap away.
                    if (n >= 1 && !m.degraded) lines = min(3, n) else compact = true
                }
                else -> Unit
            }
            val isParty = party.kind is CourtBubbleKind.party
            bubble = BubbleSlot(
                rect = Rect(colL, top, colL + colW, top + avail), lines = lines, compact = compact,
                exhibitRow = row, centred = true, tail = if (isParty) Tail.side(headY - top) else Tail.none,
            )
        }
        if (hasExhibit) {
            val bubbleBottom = if (party == null) top - gap else top + min(measuredPartyHeight, avail)
            val home = board.top + board.height * 0.3f
            var y = max(home, bubbleBottom + gap)
            y = min(y, miniFloor - mini.height)
            easelRect = Rect(board.center.x - mini.width / 2, y, board.center.x + mini.width / 2, y + mini.height)
        }
    }

    companion object {
        const val gap: Float = 6f
    }
}

// MARK: - Dock height budget

/**
 * The dock (turn controls above the tab bar) must never reach the podium name tags: its top stays at least
 * `clearance` below the tags' bottom edge in every mode, on every phone, at every text size. The scene works out the
 * height on offer; the dock measures itself and steps down a density (regular → compact → tight) whenever it is taller
 * than that. Density only steps back up when the budget itself changes (another screen size / text size), so the dock
 * never flickers between layouts while someone is reading it.
 */
object CourtDockBudget {
    const val clearance: Float = 12f

    enum class Density {
        /** Full layout (tip line, two-line subtitles, roomy exhibit card). */
        regular,

        /** Tighter paddings, a slimmer exhibit row, "N left · Rest" shortened into the Show row. */
        compact,

        /**
         * Last resort (large text on a small phone): no tip line, one-line subtitle and exhibit row, no YOUR TURN
         * eyebrow (TalkBack still hears it).
         */
        tight;

        val next: Density get() = entries[min(ordinal + 1, tight.ordinal)]
    }

    /** Bottom edge of the podium name tags, in scene coordinates. */
    fun tagBottom(z: CourtroomZones, tagHeight: Float): Float = CourtPodiumParty.tagCenter(z, Role.plaintiff).y + tagHeight / 2

    /**
     * Height the dock may take above the tab bar / navigation bar (`bottomInset`) so its top stays `clearance` below
     * the name tags.
     */
    fun available(sceneHeight: Float, bottomInset: Float, tagBottom: Float, clearance: Float = CourtDockBudget.clearance): Float =
        max(0f, sceneHeight - bottomInset - tagBottom - clearance)

    /**
     * The density to use after measuring the dock at `current`: one step down when it overflows the budget (half a
     * point of tolerance), otherwise unchanged.
     */
    fun density(after: Density, measured: Float, budget: Float?): Density {
        if (budget == null || measured <= budget + 0.5f) return after
        return after.next
    }

    /** Clearance between the dock's top and the tags' bottom for a measured dock height. */
    fun clearance(sceneHeight: Float, bottomInset: Float, tagBottom: Float, dockHeight: Float): Float =
        sceneHeight - bottomInset - dockHeight - tagBottom
}
