// Port of ArgueWin/Features/Onboarding/About/AboutYouTwoContent.swift. "About you two" (CONTRACTS-v2 amendment bd):
// the five questions between Welcome and the Courtroom Oath. Copy, option ids and storage keys are binding and
// byte-identical with iOS. Answers stay on the device (`onboarding.<scope>.<key>`): never sent to the backend,
// analytics or the judge.
package app.plead.android.features.onboarding.about

import app.plead.android.features.onboarding.OnboardingModel
import app.plead.android.features.onboarding.OnboardingStep

/** One "About you two" question (screens 1–5 of the six; the oath is the sixth). */
enum class AboutQuestion(val rawValue: String) {
    frequency("frequency"), topics("topics"), outcome("outcome"), repeats("repeats"), easier("easier");

    /** How the options are laid out (amendment bd: radio rows, two-column icon tiles, icon rows). */
    enum class Layout { radioRows, iconTiles, iconRows }

    /** `id` is what is stored (amendment bd ids). */
    data class Option(val id: String, val title: String, val icon: AboutOptionIcon? = null)

    val step: OnboardingStep
        get() = when (this) {
            frequency -> OnboardingStep.aboutFrequency
            topics -> OnboardingStep.aboutTopics
            outcome -> OnboardingStep.aboutOutcome
            repeats -> OnboardingStep.aboutRepeat
            easier -> OnboardingStep.aboutEasier
        }

    /** `onboarding.<scope>.aboutFrequency` … (amendment bd). */
    val storageKey: OnboardingModel.Key
        get() = when (this) {
            frequency -> OnboardingModel.Key.aboutFrequency
            topics -> OnboardingModel.Key.aboutTopics
            outcome -> OnboardingModel.Key.aboutOutcome
            repeats -> OnboardingModel.Key.aboutRepeat
            easier -> OnboardingModel.Key.aboutEasier
        }

    val isMultiSelect: Boolean get() = this == topics || this == easier

    val layout: Layout
        get() = when (this) {
            topics -> Layout.iconTiles
            easier -> Layout.iconRows
            else -> Layout.radioRows
        }

    val title: String
        get() = when (this) {
            frequency -> "How often do little things turn into arguments?"
            topics -> "What do you bicker about most?"
            outcome -> "When you disagree, what usually happens?"
            repeats -> "Does the same argument keep coming back?"
            easier -> "What would make those moments easier?"
        }

    val hint: String get() = if (isMultiSelect) "Choose all that fit" else "Pick one"

    val options: List<Option>
        get() = when (this) {
            frequency -> listOf(
                Option("most_days", "Most days"),
                Option("few_times_week", "A few times a week"),
                Option("occasionally", "Occasionally"),
                Option("rarely", "Rarely"),
            )
            topics -> listOf(
                Option("chores", "Chores", AboutOptionIcon.broom),
                Option("food", "Food", AboutOptionIcon.pizza),
                Option("being_late", "Being late", AboutOptionIcon.alarmClock),
                Option("phone_habits", "Phone habits", AboutOptionIcon.phone),
                Option("spending", "Spending", AboutOptionIcon.wallet),
                Option("random", "Random little things", AboutOptionIcon.thought),
            )
            outcome -> listOf(
                Option("both_insist", "We both insist we're right"),
                Option("gives_in", "Someone gives in"),
                Option("laugh_off", "We laugh it off"),
                Option("unresolved", "It stays unresolved"),
            )
            repeats -> listOf(
                Option("all_the_time", "All the time"),
                Option("sometimes", "Sometimes"),
                Option("rarely", "Rarely"),
                Option("something_new", "We always find something new"),
            )
            easier -> listOf(
                Option("feeling_heard", "Feeling heard", AboutOptionIcon.heart),
                Option("fair_compromise", "A fair compromise", AboutOptionIcon.scales),
                Option("clear_decision", "A clear decision", AboutOptionIcon.document),
                Option("laugh_about_it", "Being able to laugh about it", AboutOptionIcon.smile),
            )
        }

    val optionIds: List<String> get() = options.map { it.id }

    companion object {
        const val eyebrow = "ABOUT YOU TWO"

        /** Swift `init?(step:)`. */
        fun forStep(step: OnboardingStep): AboutQuestion? = entries.firstOrNull { it.step == step }
    }
}

/** The Courtroom Oath's copy (amendment bd, screen 6). */
object CourtroomOathCopy {
    const val eyebrow = "THE COURTROOM OATH"
    const val title = "Do you swear to hear them out?"
    const val oath = "I'll hear both sides, give the verdict a fair chance, and keep our little disagreements playful."
    const val button = "HOLD TO TAKE THE OATH"
    const val caption = "Hold for 2 seconds"

    /** The hold the button needs (amendment bd: the full 2 seconds). */
    const val holdDuration: Double = 2.0
}

/** Stored form of an answer: option ids, multi-select comma-joined in option order (amendment bd). Pure; unit-tested. */
object AboutAnswerCodec {
    /** `null` = nothing stored (unanswered). */
    fun encode(ids: List<String>, q: AboutQuestion): String? {
        val ordered = q.optionIds.filter { it in ids }
        if (ordered.isEmpty()) return null
        return if (q.isMultiSelect) ordered.joinToString(",") else ordered[0]
    }

    /** Unknown ids are dropped; a pick-one question keeps at most one. */
    fun decode(stored: String?, q: AboutQuestion): List<String> {
        if (stored.isNullOrEmpty()) return emptyList()
        val ids = stored.split(",").map { it.trim() }.toSet()
        val ordered = q.optionIds.filter { it in ids }
        return if (q.isMultiSelect) ordered else ordered.take(1)
    }

    /** The answer after tapping `id`: pick-one replaces; choose-all toggles (kept in option order). */
    fun toggled(id: String, current: List<String>, q: AboutQuestion): List<String> {
        if (id !in q.optionIds) return current
        if (!q.isMultiSelect) return listOf(id)
        val set = current.toMutableSet()
        if (!set.remove(id)) set.add(id)
        return q.optionIds.filter { it in set }
    }
}
