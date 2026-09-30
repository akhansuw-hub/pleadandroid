// Port of ArgueWin/Features/Judgement/JudgementPresentation.swift.
//
// Presentation helpers for the winner-selected court judgement (CONTRACTS-v2 amendment j).
// Pure logic lives here so it can be unit tested without a view.
package app.plead.android.features.judgement

import android.text.format.DateFormat
import androidx.compose.ui.graphics.Color
import app.plead.android.designsystem.PleadColor
import app.plead.android.models.Judgement
import app.plead.android.models.JudgementOption
import app.plead.android.models.JudgementOptionType
import app.plead.android.models.JudgementStatus
import app.plead.android.models.Verdict
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

/**
 * Since amendment j the ruling no longer invents a sentence; the backend writes this fixed line and
 * the app shows the judgement instead. (`DemoTrialSimulator.judgementPendingSentence` reads this one.)
 */
val Verdict.Companion.judgementPendingSentence: String get() = "The prevailing party will choose the court's judgement."

/** A pre-amendment ruling whose `sentence` is a real order (shown as-is when no judgement exists). */
val Verdict.hasLegacySentence: Boolean
    get() = sentence.isNotEmpty() && sentence != Verdict.judgementPendingSentence

/** Short chip text on option cards. */
val JudgementOptionType.title: String
    get() = when (this) {
        JudgementOptionType.directRemedy -> "Remedy"
        JudgementOptionType.effort -> "Effort"
        JudgementOptionType.privilege -> "Privilege"
        JudgementOptionType.favour -> "Favour"
        JudgementOptionType.compromise -> "Compromise"
    }

/** "due in 3 days" / "due in 1 day". */
val JudgementOption.dueLine: String get() = "due in $dueDays ${if (dueDays == 1) "day" else "days"}"

/** Status chip text (brief screen D). */
val JudgementStatus.chipTitle: String
    get() = when (this) {
        JudgementStatus.pendingSelection -> "Pending"
        JudgementStatus.delivered -> "Delivered"
        JudgementStatus.accepted -> "Accepted"
        JudgementStatus.served -> "Served"
        JudgementStatus.declined -> "Declined"
    }

/** Burgundy while it needs someone, walnut once accepted, gold when served, muted when declined. */
val JudgementStatus.tint: Color
    get() = when (this) {
        JudgementStatus.pendingSelection -> PleadColor.mahogany
        JudgementStatus.delivered -> PleadColor.burgundy
        JudgementStatus.accepted -> PleadColor.walnut
        JudgementStatus.served -> PleadColor.gold
        JudgementStatus.declined -> PleadColor.subtleText
    }

/** "Sunday" within the coming week, "Today" / "Tomorrow", else "12 Oct". Swift's calendar = [zone]. */
fun Judgement.Companion.dueText(
    due: Instant,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    val days = ChronoUnit.DAYS.between(now.atZone(zone).toLocalDate(), due.atZone(zone).toLocalDate())
    return when (days) {
        0L -> "Today"
        1L -> "Tomorrow"
        in 2L..6L -> due.atZone(zone).dayOfWeek.getDisplayName(TextStyle.FULL, locale)
        else -> JudgementDates.dayMonthAbbreviated(due, locale, zone)
    }
}

/** "Due: Sunday" / "Was due Sunday" (past). */
fun Judgement.dueLine(now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String? {
    val due = dueAt ?: return null
    if (status != JudgementStatus.delivered && status != JudgementStatus.accepted) return null
    val text = Judgement.dueText(due, now, zone)
    val sameDay = due.atZone(zone).toLocalDate() == now.atZone(zone).toLocalDate()
    return if (due.isBefore(now) && !sameDay) "Was due $text" else "Due: $text"
}

/** Locale-ordered date formats for the judgement screens (SwiftUI `Date.FormatStyle` templates). */
object JudgementDates {
    private fun pattern(skeleton: String, fallback: String, locale: Locale): String =
        runCatching { DateFormat.getBestDateTimePattern(locale, skeleton) }.getOrNull() ?: fallback

    /** `.dateTime.day().month(.abbreviated)`: "12 Oct". */
    fun dayMonthAbbreviated(date: Instant, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern(pattern("dMMM", "d MMM", locale), locale).format(date.atZone(zone))

    /** `.dateTime.weekday(.wide).day().month(.abbreviated)`: "Sunday 12 Oct". */
    fun weekdayDayMonthAbbreviated(date: Instant, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern(pattern("EEEEdMMM", "EEEE d MMM", locale), locale).format(date.atZone(zone))
}

/** What the status card offers, by role and state (brief screen D). */
enum class JudgementCardAction {
    choose, accept, decline, markServed;

    companion object {
        /**
         * The chooser picks while pending; the other party accepts (or quietly declines) once delivered;
         * either party marks it served once delivered or accepted. Served / declined are final.
         * A court-chosen resolution (tie, amendment l) skips the accept step: either partner marks it
         * served, or quietly declines it.
         */
        fun actions(judgement: Judgement, me: UUID): List<JudgementCardAction> {
            if (judgement.isCourtChosen) {
                return when (judgement.status) {
                    JudgementStatus.delivered, JudgementStatus.accepted -> listOf(markServed, decline)
                    JudgementStatus.pendingSelection, JudgementStatus.served, JudgementStatus.declined -> emptyList()
                }
            }
            val chooser = judgement.chooserId == me
            return when (judgement.status) {
                JudgementStatus.pendingSelection -> if (chooser) listOf(choose) else emptyList()
                JudgementStatus.delivered -> if (chooser) listOf(markServed) else listOf(accept, markServed, decline)
                JudgementStatus.accepted -> listOf(markServed)
                JudgementStatus.served, JudgementStatus.declined -> emptyList()
            }
        }
    }
}
