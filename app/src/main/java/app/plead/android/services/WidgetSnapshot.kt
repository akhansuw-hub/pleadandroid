// Port of Shared/WidgetSnapshot.swift (compiled into the app and the widget extension on iOS; on Android the Glance
// widgets of wave 3f live in the same process and read it through `WidgetSnapshot.load()`).
//
// CONTRACTS-v2 amendment o: the minimum shared state the widgets need. Written by the app
// (`WidgetSnapshotStore`), read by the widgets. Never put evidence, transcript, allegation or judgement text in here.
@file:UseSerializers(SupabaseDateSerializer::class, UUIDSerializer::class)

package app.plead.android.services

import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Mirrors the "Show case details on Lock Screen" setting (default off → generic). */
@Serializable
enum class WidgetPrivacyMode { generic, detailed }

/** The one state a widget shows (brief §3). Names are the JSON values. */
@Serializable
enum class WidgetState { summoned, yourTurn, settlement, deliberating, verdictReady, judgementDue, agreementDue, none }

/** The primary tap for the state. */
@Serializable
enum class WidgetAction {
    enterPlea, takeTurn, reviewOffer, viewCase, readVerdict, viewJudgement, openApp;

    /** Pill title (brief image 2). */
    val title: String
        get() = when (this) {
            enterPlea -> "Enter plea"
            takeTurn -> "Respond"
            reviewOffer -> "Review offer"
            viewCase -> "View case"
            readVerdict -> "Read verdict"
            viewJudgement -> "View judgement"
            openApp -> "Open Plead"
        }
}

@Serializable
data class WidgetCase(
    val caseId: UUID,
    val caseNumber: Int,
    val state: WidgetState,
    val caseTitle: String,
    val partnerDisplayName: String,
    val nextAction: WidgetAction = WidgetSnapshot.action(state),
    val deadlineAt: Instant? = null,
    /** `plead://case/{id}/…` (Swift `URL`). */
    val link: String = WidgetSnapshot.deepLink(caseId, state),
) {
    /** "Case #021". */
    val docketNumber: String get() = "Case #%03d".format(caseNumber)
}

@Serializable
data class WidgetSnapshot(
    val updatedAt: Instant = Instant.now(),
    val privacyMode: WidgetPrivacyMode = WidgetPrivacyMode.generic,
    val activeCaseCount: Int = 0,
    val primary: WidgetCase? = null,
) {
    /** The state shown (`none` when there is no primary case). */
    val state: WidgetState get() = primary?.state ?: WidgetState.none

    /** Where a tap lands. */
    val link: String get() = primary?.link ?: homeLink

    /** Same content, ignoring the timestamp (the app only rewrites / reloads on a real change). */
    fun sameContent(other: WidgetSnapshot?): Boolean {
        if (other == null) return false
        return privacyMode == other.privacyMode && activeCaseCount == other.activeCaseCount && primary == other.primary
    }

    /** JSON with sorted keys and ISO-8601 dates (Swift `WidgetSnapshot.encoder`). */
    fun encoded(): String = json.encodeToString(JsonElement.serializer(), sorted(json.encodeToJsonElement(serializer(), this)))

    /** Writes the snapshot where the widgets read it (the App Group suite). */
    fun save(to: UserDefaults = UserDefaults.appGroup) {
        to.set(encoded(), fileName)
    }

    companion object {
        // MARK: Storage (App Group)

        const val appGroup = "group.app.plead.shared"
        const val fileName = "widget-snapshot.json"

        /** App Group defaults key written by Settings ("Show case details on Lock Screen"). */
        const val lockscreenDetailsKey = "lockscreenDetails"

        /** Swift's default `JSONEncoder` / `JSONDecoder` (camelCase keys) with `.iso8601` dates. */
        val json: Json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }

        fun decode(data: String): WidgetSnapshot = json.decodeFromString(serializer(), data)

        /** The last snapshot the app wrote, or null (never written / unreadable). */
        fun load(from: UserDefaults = UserDefaults.appGroup): WidgetSnapshot? =
            from.string(fileName)?.let { runCatching { decode(it) }.getOrNull() }

        private fun sorted(element: JsonElement): JsonElement = when (element) {
            is JsonObject -> JsonObject(element.entries.sortedBy { it.key }.associate { it.key to sorted(it.value) })
            is JsonArray -> JsonArray(element.map(::sorted))
            else -> element
        }

        // MARK: Copy (brief §3) and deep links (amendment o)

        const val homeLink = "plead://home"

        /** `plead://case/{id}/plea | turn | settlement | verdict | judgement | deliberation`, `plead://home`. */
        fun deepLink(caseId: UUID?, state: WidgetState): String {
            val screen = linkScreen(state)
            if (caseId == null || screen == null) return homeLink
            return "plead://case/${caseId.toString().lowercase(Locale.ROOT)}/$screen"
        }

        fun linkScreen(state: WidgetState): String? = when (state) {
            WidgetState.summoned -> "plea"
            WidgetState.yourTurn -> "turn"
            WidgetState.settlement, WidgetState.agreementDue -> "settlement"
            WidgetState.deliberating -> "deliberation"
            WidgetState.verdictReady -> "verdict"
            WidgetState.judgementDue -> "judgement"
            WidgetState.none -> null
        }

        fun action(state: WidgetState): WidgetAction = when (state) {
            WidgetState.summoned -> WidgetAction.enterPlea
            WidgetState.yourTurn -> WidgetAction.takeTurn
            WidgetState.settlement -> WidgetAction.reviewOffer
            WidgetState.deliberating, WidgetState.agreementDue -> WidgetAction.viewCase
            WidgetState.verdictReady -> WidgetAction.readVerdict
            WidgetState.judgementDue -> WidgetAction.viewJudgement
            WidgetState.none -> WidgetAction.openApp
        }

        /**
         * Brief §3 widget copy. The headline is lock-screen-safe in both modes: it never carries the case
         * title or partner name, so `privacy` only matters for `detailLine`.
         */
        @Suppress("UNUSED_PARAMETER")
        fun headline(state: WidgetState, privacy: WidgetPrivacyMode = WidgetPrivacyMode.generic): String = when (state) {
            WidgetState.summoned -> "You've been summoned"
            WidgetState.yourTurn -> "Your response is due"
            WidgetState.settlement -> "Settlement offer waiting"
            WidgetState.deliberating -> "The court is deliberating"
            WidgetState.verdictReady -> "The judge has ruled"
            WidgetState.judgementDue -> "Judgement outstanding"
            WidgetState.agreementDue -> "Agreement outstanding"
            WidgetState.none -> "Court adjourned"
        }

        fun headline(primary: WidgetCase?, privacy: WidgetPrivacyMode = WidgetPrivacyMode.generic): String =
            headline(primary?.state ?: WidgetState.none, privacy)

        /** Second line. Detailed: the case title. Generic: a neutral line with no title, partner or case text. */
        fun detailLine(primary: WidgetCase?, privacy: WidgetPrivacyMode, activeCaseCount: Int = 0): String {
            if (privacy == WidgetPrivacyMode.detailed && primary != null && primary.caseTitle.isNotEmpty()) return primary.caseTitle
            return when (primary?.state ?: WidgetState.none) {
                WidgetState.summoned -> "Open Plead to enter your plea"
                WidgetState.yourTurn -> "The court calls you"
                WidgetState.settlement -> "Review it before court continues"
                WidgetState.deliberating -> "The ruling is on its way"
                WidgetState.verdictReady -> "Your verdict is ready"
                WidgetState.judgementDue -> "The court's judgement is due"
                WidgetState.agreementDue -> "Your settlement is due"
                WidgetState.none -> if (activeCaseCount > 0) "Nothing needs you right now" else "No open cases"
            }
        }

        /** Short status for the chip (Home Screen widgets). */
        fun statusChip(state: WidgetState): String = when (state) {
            WidgetState.summoned -> "Awaiting your plea"
            WidgetState.yourTurn -> "Your turn"
            WidgetState.settlement -> "Offer waiting"
            WidgetState.deliberating -> "Deliberating"
            WidgetState.verdictReady -> "Verdict ready"
            WidgetState.judgementDue -> "Judgement due"
            WidgetState.agreementDue -> "Agreement due"
            WidgetState.none -> "No active case"
        }

        /** Word before the countdown ("Plea due in 3 hr"). */
        fun deadlineLabel(state: WidgetState): String? = when (state) {
            WidgetState.summoned -> "Plea due in"
            WidgetState.yourTurn -> "Turn ends in"
            WidgetState.settlement -> "Offer ends in"
            WidgetState.deliberating -> "Ruling in"
            WidgetState.judgementDue, WidgetState.agreementDue -> "Due in"
            WidgetState.verdictReady, WidgetState.none -> null
        }

        /** The countdown target while it is still ahead of `now`. */
        fun activeDeadline(primary: WidgetCase?, now: Instant): Instant? {
            val d = primary?.deadlineAt ?: return null
            if (!d.isAfter(now) || deadlineLabel(primary.state) == null) return null
            return d
        }

        /** "Active cases: 2" line for the small widget ("1 active case"). */
        fun activeCountLine(n: Int): String = if (n == 1) "1 active case" else "$n active cases"

        /** One TalkBack sentence for a widget. */
        fun accessibilityLabel(snapshot: WidgetSnapshot, detailed: Boolean, now: Instant = Instant.now()): String {
            val parts = mutableListOf("Plead", headline(snapshot.primary, snapshot.privacyMode))
            val privacy = if (detailed) WidgetPrivacyMode.detailed else WidgetPrivacyMode.generic
            val p = snapshot.primary
            if (p != null) {
                if (detailed) {
                    parts.add(p.caseTitle)
                    parts.add("with ${p.partnerDisplayName}")
                } else {
                    parts.add(detailLine(p, privacy))
                }
                val d = activeDeadline(p, now)
                val label = deadlineLabel(p.state)
                if (d != null && label != null) parts.add("$label ${spokenInterval(now, d)}")
                parts.add(p.nextAction.title)
            } else {
                parts.add(detailLine(null, privacy, snapshot.activeCaseCount))
            }
            return parts.joinToString(". ")
        }

        /** `DateComponentsFormatter` (.full; day+hour at an hour or more, else minutes; two units at most). */
        private fun spokenInterval(from: Instant, to: Instant): String {
            val seconds = Duration.between(from, to).seconds
            fun unit(n: Long, one: String) = if (n == 1L) "1 $one" else "$n ${one}s"
            if (seconds >= 3600) {
                val days = seconds / 86_400
                val hours = (seconds % 86_400) / 3600
                val out = mutableListOf<String>()
                if (days > 0) out.add(unit(days, "day"))
                if (hours > 0 || days == 0L) out.add(unit(hours, "hour"))
                return out.joinToString(", ")
            }
            return unit(seconds / 60, "minute")
        }

        // MARK: Samples (placeholder, previews, the DEBUG harness)

        val sampleCaseId: UUID = UUID.fromString("0D1E2F30-4152-4637-8899-AABBCCDDEEFF")

        /** Widget gallery / placeholder content (brief image 1). */
        fun sample(state: WidgetState = WidgetState.summoned, privacy: WidgetPrivacyMode = WidgetPrivacyMode.detailed, now: Instant = Instant.now()): WidgetSnapshot {
            if (state == WidgetState.none) return WidgetSnapshot(updatedAt = now, privacyMode = privacy, activeCaseCount = 0, primary = null)
            val deadline: Instant? = when (state) {
                WidgetState.summoned -> now.plusSeconds(5 * 3600 + 12 * 60)
                WidgetState.yourTurn -> now.plusSeconds(2 * 3600)
                WidgetState.deliberating -> now.plusSeconds(52 * 60)
                WidgetState.judgementDue, WidgetState.agreementDue -> now.plusSeconds(2 * 86_400)
                else -> null
            }
            val primary = WidgetCase(
                caseId = sampleCaseId, caseNumber = 21, state = state, caseTitle = "The Dinner Incident",
                partnerDisplayName = "Sophie", deadlineAt = deadline,
            )
            return WidgetSnapshot(updatedAt = now, privacyMode = privacy, activeCaseCount = 1, primary = primary)
        }
    }
}

/** Swift `URL.lastPathComponent` for the widget links. */
val String.lastPathComponent: String get() = runCatching { URI(this).path.trimEnd('/').substringAfterLast('/') }.getOrDefault("")
