// Port of ArgueWin/Courtroom/CourtCaseCall.swift: the shared case call (CONTRACTS-v2 amendment ad, motion brief §18).
// INTERFACE IS FIXED for the mock trial and the live scene: every name below and the `judgeLine` output are shared;
// members may be added, never renamed or removed. Copy here is exact; do not paraphrase.
//
// After the shared entrance (amendment ac) the court is called before any testimony: the NOW HEARING card
// (`CourtCaseCallCard`), then Judge Wigsworth's introduction (`judgeLine`) in the judge's bubble, then the opening. The
// live court builds its `CourtCaseCall` with `CourtCaseCall.live(_:)` and plays the flow with `CourtCaseCallDirector`
// (CourtCaseCallLive.kt); the mock trial supplies `mock`.
package app.plead.android.courtroom

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.plead.android.designsystem.Color
import app.plead.android.designsystem.PleadColor
import app.plead.android.designsystem.PleadMotion
import app.plead.android.designsystem.PleadRadius
import app.plead.android.designsystem.PleadType
import app.plead.android.designsystem.accessibilityReduceMotion
import app.plead.android.designsystem.pleadShadow
import app.plead.android.designsystem.swiftSpring
import app.plead.android.models.Role

/** The case as the court calls it: card fields + the judge's introduction line. */
data class CourtCaseCall(
    val number: Int?,
    val plaintiff: String,
    val defendant: String,
    /** Filed title, shown on the card in caps ("THE LAST SLICE CASE"); null hides that part of the line. */
    val title: String? = null,
    /** Short neutral topic for the judge's line, lower-case noun phrase ("the last slice of pizza"); null → parties only. */
    val topic: String? = null,
) {
    val partiesLine: String get() = "$plaintiff vs $defendant"

    /** "CASE #14 / THE LAST SLICE CASE" (or just "CASE #14", or just the title). */
    val referenceLine: String
        get() = listOfNotNull(number?.let { "CASE #$it" }, title?.uppercase()).joinToString(" / ")

    /**
     * Amendment ad template. Mock: "The court is now in session. Case 14: Sam versus Alex. The matter before the court
     * is the last slice of pizza. Sam, you may begin."
     */
    val judgeLine: String
        get() {
            var s = "The court is now in session."
            s += if (number != null) " Case $number: $plaintiff versus $defendant." else " $plaintiff versus $defendant."
            if (!topic.isNullOrEmpty()) s += " The matter before the court is $topic."
            s += " $plaintiff, you may begin."
            return s
        }

    /** VoiceOver reads the card as one element: "Now hearing: Sam versus Alex. Case 14, The Last Slice Case." */
    val accessibilityLabel: String
        get() {
            var s = "Now hearing: $plaintiff versus $defendant."
            val t = title?.takeIf { it.isNotEmpty() }
            s += when {
                number != null && t != null -> " Case $number, $t."
                number != null -> " Case $number."
                t != null -> " $t."
                else -> ""
            }
            return s
        }

    companion object {
        val mock = CourtCaseCall(
            number = 14, plaintiff = "Sam", defendant = "Alex",
            title = "The Last Slice Case", topic = "the last slice of pizza",
        )

        // MARK: Live data

        /**
         * The call for a live case: case number, the parties' display names, the filed title and a neutral topic derived
         * from that title (`topic(fromTitle:)`; null → the judge names only the parties). Nothing else of the case is
         * used: the charge is one side's allegation, never something the court may announce as a finding.
         */
        fun live(s: CourtroomState): CourtCaseCall {
            fun name(r: Role): String {
                val n = s.profile(r).displayName.trim()
                return n.ifEmpty { CourtroomLogic.roleTitle(r) }
            }
            val plaintiff = name(Role.plaintiff)
            val defendant = name(Role.defendant)
            val title = s.kase.title.trim()
            return CourtCaseCall(
                number = if (s.kase.caseNumber > 0) s.kase.caseNumber else null,
                plaintiff = plaintiff, defendant = defendant,
                title = title.ifEmpty { null },
                topic = topic(title, names = listOf(plaintiff, defendant)),
            )
        }

        private const val punctuationChars = ".!,;:"

        /** Swift `CharacterSet.letters` (letters and marks). */
        private fun isLetterScalar(cp: Int): Boolean = Character.isLetter(cp) || when (Character.getType(cp).toByte()) {
            Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> true
            else -> false
        }

        /** Swift `CharacterSet.whitespaces` (space separators and tab). */
        private fun isWhitespaceScalar(cp: Int): Boolean = cp == '\t'.code || Character.getType(cp).toByte() == Character.SPACE_SEPARATOR

        /**
         * A short, neutral, lower-case noun phrase for "The matter before the court is …", from the filed title: "The
         * Thermostat Incident" → "the thermostat incident", "The Case of the Missing Remote" → "the missing remote",
         * "Dishes" → "the dishes". Returns null (the judge then names only the parties) when the title is empty, a
         * question, has emoji / symbols, is too long (> 8 words / 48 characters), or reads as a claim rather than a
         * subject: a personal pronoun ("my", "he", "you"…) or a finite verb / accusing adverb ("is", "never", "ate"…).
         */
        fun topic(fromTitle: String?, names: List<String> = emptyList()): String? {
            var t = fromTitle?.trim() ?: return null
            if (t.isEmpty() || t.contains("?")) return null
            // Curly apostrophes → straight; quotes dropped.
            t = t.replace("’", "'").replace("‘", "'")
                .replace("“", "").replace("”", "")
                .replace("\"", "")
            val allowed = t.codePoints().toArray().all { cp ->
                isLetterScalar(cp) || Character.isDigit(cp) || isWhitespaceScalar(cp) ||
                    (cp < 0x10000 && ("'-&" + punctuationChars).contains(cp.toChar()))
            }
            if (!allowed) return null
            // Trailing punctuation goes; punctuation inside ("Socks. Again.") means a sentence, not a subject.
            t = t.trim { it in punctuationChars || isWhitespaceScalar(it.code) }
            if (t.any { it in punctuationChars }) return null
            val words = t.split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
            if (words.isEmpty()) return null

            // "The Case of the X" / "Case of X" → "the X"; "The Last Slice Case" → "The Last Slice".
            fun lower(w: String) = w.lowercase()
            if (words.size > 2 && lower(words[0]) == "the" && lower(words[1]) == "case" && lower(words[2]) == "of") {
                repeat(3) { words.removeAt(0) }
            } else if (words.size > 2 && lower(words[0]) == "case" && lower(words[1]) == "of") {
                repeat(2) { words.removeAt(0) }
            }
            if (words.size > 1 && lower(words[words.size - 1]) == "case") words.removeAt(words.size - 1)
            if (words.isEmpty() || words.size > 8) return null

            val bare = words.map { w ->
                var b = lower(w)
                if (b.endsWith("'s")) b = b.dropLast(2)
                b.trim('\'')
            }
            if (bare.any { it in claimWords }) return null

            // Title Case ("The Thermostat Incident") lowers every word; sentence case ("Socks on the stairs") keeps what
            // was typed after the first word. Acronyms (TV) and the parties' names keep their capitals.
            val nameSet = names.map { lower(it) }.toSet()
            val capitalised = words.count { it.firstOrNull()?.isUpperCase() == true }
            val titleCase = words.size > 1 && capitalised * 2 > words.size
            for (i in words.indices) {
                val w = words[i]
                val isAcronym = w.length >= 2 && w.all { it.isUpperCase() || it.isDigit() } && w.any { it.isLetter() }
                if (isAcronym || bare[i] in nameSet) continue
                if (i == 0 || titleCase) words[i] = lower(w)
            }
            val first = lower(words[0])
            if (first !in determiners && !first.endsWith("ing") && bare[0] !in nameSet) {
                words.add(0, "the")
            }
            val phrase = words.joinToString(" ")
            return if (phrase.length <= 48) phrase else null
        }

        private val determiners: Set<String> = setOf("the", "a", "an", "this", "that", "these", "those", "some", "one")

        /** Words that make a title read as a claim about someone (pronouns, finite verbs, accusing adverbs). */
        private val claimWords: Set<String> = setOf(
            "i", "i'm", "me", "my", "mine", "you", "you're", "your", "yours", "he", "he's", "him", "his", "she", "she's", "her",
            "hers", "they", "they're", "them", "their", "we", "we're", "us", "our",
            "is", "isn't", "are", "aren't", "was", "wasn't", "were", "weren't", "do", "does", "doesn't", "did", "didn't", "don't",
            "has", "hasn't", "have", "haven't", "had", "won't", "can't", "cannot", "never", "always", "again", "keeps", "refuses",
            "refused", "forgot", "forgets", "ate", "eats", "stole", "steals", "lied", "lies", "broke", "breaks", "ignored", "ignores",
            "should", "must", "will",
        )
    }
}

// MARK: - Card

/** Statics of `CourtCaseCallCard`. */
object CourtCaseCallCard {
    /** Court gold as ink on paper (brand gold #C99558 is ≈ 2.6:1 on paper white; this is ≈ 5:1, WCAG AA). */
    val goldInk = Color(hex = 0x916226)
}

/**
 * NOW HEARING card: paper white over the stage, eyebrow in court gold, "{Plaintiff} vs {Defendant}" in Fraunces
 * display, "CASE #14 / THE LAST SLICE CASE" in tracked caps. Enters like a bubble (amendment x: fade + scale 0.96 → 1
 * + a 6 pt rise, restrained spring ≈ 220 ms); Reduce Motion fades only. TalkBack reads it as one element. The host owns
 * the tap (anywhere advances); `onTap` also backs the accessibility activate action.
 */
@Composable
fun CourtCaseCallCard(
    call: CourtCaseCall,
    modifier: Modifier = Modifier,
    /** Advance (host). Also the accessibility activate action. null = display only. */
    onTap: (() -> Unit)? = null,
    /** false = shown at rest from the first frame (stills). */
    animatesIn: Boolean = true,
) {
    val reduceMotion = accessibilityReduceMotion()
    val progress = remember { Animatable(if (animatesIn) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (animatesIn && progress.value < 1f) {
            progress.animateTo(
                1f,
                if (reduceMotion) tween(200, easing = PleadMotion.easeOut)
                else swiftSpring(CourtMotionTiming.bubbleEntrance.toFloat(), 0.12f),
            )
        }
    }
    val shape = RoundedCornerShape(PleadRadius.card)
    DynamicTypeCap(DynamicTypeSize.xxLarge) {
        Column(
            modifier = modifier
                .graphicsLayer {
                    val p = progress.value
                    alpha = p.coerceIn(0f, 1f)
                    val s = if (reduceMotion) 1f else CourtMotionTiming.bubbleScale + (1f - CourtMotionTiming.bubbleScale) * p
                    scaleX = s
                    scaleY = s
                    transformOrigin = TransformOrigin(0.5f, 1f)
                    translationY = if (reduceMotion) 0f else CourtMotionTiming.bubbleRise * (1f - p) * density
                }
                .clearAndSetSemantics {
                    contentDescription = call.accessibilityLabel
                    if (onTap != null) {
                        role = SemanticsRole.Button
                        onClick { onTap(); true }
                    }
                }
                .testTag("court.caseCall")
                .widthIn(max = 340.dp)
                .pleadShadow(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.35f), 14.dp, y = 6.dp, shape = shape)
                .background(PleadColor.paperWhite, shape)
                .drawWithContent {
                    drawContent()
                    strokeBorder(PleadRadius.card.toPx(), inset = 4.dp.toPx(), width = 1.dp.toPx(), color = PleadColor.gold.copy(alpha = 0.55f))
                    strokeBorder(PleadRadius.card.toPx(), inset = 0f, width = 1.5.dp.toPx(), color = PleadColor.gold.copy(alpha = 0.9f))
                }
                .padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CaseCallRule()
                androidx.compose.material3.Text(
                    "NOW HEARING",
                    style = PleadType.labelCapsTracked,
                    color = CourtCaseCallCard.goldInk,
                    maxLines = 1,
                    softWrap = false,
                )
                CaseCallRule()
            }
            ScaledText(
                call.partiesLine,
                style = PleadType.displayL,
                color = PleadColor.cocoa,
                minimumScaleFactor = 0.7f,
                maxLines = 2,
                textAlign = TextAlign.Center,
            )
            if (call.referenceLine.isNotEmpty()) {
                ScaledText(
                    call.referenceLine,
                    style = PleadType.labelCaps.copy(letterSpacing = PleadType.capsTracking.sp),
                    color = PleadColor.walnut,
                    minimumScaleFactor = 0.85f,
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun CaseCallRule() {
    Box(Modifier.size(width = 22.dp, height = 1.dp).background(PleadColor.gold.copy(alpha = 0.6f)).accessibilityHidden())
}
