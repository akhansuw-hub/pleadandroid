// Plead — shared models. Port of ArgueWin/Models/Models.swift; mirrors docs/CONTRACTS.md + docs/CONTRACTS-v2.md
// and the Supabase schema.
// Rule for agents: ADD files/extensions, do not edit these definitions (PORT.md §5, additive-only).
//
// Conventions of the port:
// - Swift enums with raw values are `enum class X(override val rawValue: String) : RawRepresentable`; the JSON
//   value is the raw value (`@SerialName`), `X.fromRaw(raw)` is Swift's failable `X(rawValue:)`, and
//   `X.entries` is `allCases`. Entry names keep the Swift case names.
// - Swift `Date` is `java.time.Instant`, `UUID` is `java.util.UUID`, `Double` stays `Double`.
// - Non-optional fields that have a construction default are `@Required`, so decoding fails when the key is
//   missing, exactly as the synthesized Swift `Decodable` does. Optional fields default to null.
@file:UseSerializers(SupabaseDateSerializer::class, UUIDSerializer::class)
@file:OptIn(ExperimentalSerializationApi::class)
@file:Suppress("EnumEntryName", "unused")

package app.plead.android.models

import app.plead.android.services.SupabaseDateSerializer
import app.plead.android.services.UUIDSerializer
import java.time.Instant
import java.util.UUID
import kotlin.math.max
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** Swift `RawRepresentable` where `RawValue == String`. */
interface RawRepresentable {
    val rawValue: String
}

/** Swift `X(rawValue:)` for any raw-value enum. */
inline fun <reified T> rawEnum(raw: String?): T? where T : Enum<T>, T : RawRepresentable =
    if (raw == null) null else enumValues<T>().firstOrNull { it.rawValue == raw }

// MARK: - Enums (raw values == Postgres enum labels)

@Serializable
enum class CaseStatus(override val rawValue: String) : RawRepresentable {
    @SerialName("drafting") drafting("drafting"),
    @SerialName("summoned") summoned("summoned"),
    @SerialName("defence") defence("defence"),
    @SerialName("scheduling") scheduling("scheduling"),
    @SerialName("trial") trial("trial"),
    @SerialName("deliberating") deliberating("deliberating"),
    @SerialName("awaiting_verdict") awaitingVerdict("awaiting_verdict"),
    @SerialName("verdict") verdict("verdict"),
    @SerialName("appeal") appeal("appeal"),
    @SerialName("closed") closed("closed"),
    @SerialName("closed_guilty") closedGuilty("closed_guilty"),
    @SerialName("closed_default") closedDefault("closed_default"),
    @SerialName("closed_settled") closedSettled("closed_settled"),
    @SerialName("mistrial") mistrial("mistrial");

    val isOpen: Boolean
        get() = when (this) {
            closed, closedGuilty, closedDefault, closedSettled, mistrial -> false
            else -> true
        }
    val isClosed: Boolean get() = !isOpen
    val isInCourtroom: Boolean get() = this == trial || this == deliberating || this == awaitingVerdict || this == verdict
    val isDeliberating: Boolean get() = this == deliberating || this == awaitingVerdict

    companion object {
        fun fromRaw(raw: String?): CaseStatus? = rawEnum(raw)
    }
}

@Serializable
enum class Plea(override val rawValue: String) : RawRepresentable {
    @SerialName("guilty") guilty("guilty"),
    @SerialName("not_guilty") notGuilty("not_guilty");

    companion object {
        fun fromRaw(raw: String?): Plea? = rawEnum(raw)
    }
}

/** Pixel avatar configuration, stored as `profiles.avatar_json`. Keys are exactly these names. */
@Serializable(with = AvatarSerializer::class)
data class Avatar(
    val skin: Int = 2,        // 0 until Avatar.skinTones.size
    val hair: Int = 1,        // 0 until Avatar.hairColours.size
    val hairstyle: Hairstyle = Hairstyle.short,
    val top: Int = 0,         // 0 until Avatar.topColours.size
    val outfit: Outfit = Outfit.tee,
    val version: Int = 1,
) {
    @Serializable
    enum class Hairstyle(override val rawValue: String) : RawRepresentable {
        @SerialName("buzz") buzz("buzz"),
        @SerialName("short") short("short"),
        @SerialName("long") long("long"),
        @SerialName("curly") curly("curly"),
        @SerialName("bun") bun("bun"),
        @SerialName("ponytail") ponytail("ponytail");

        companion object {
            fun fromRaw(raw: String?): Hairstyle? = rawEnum(raw)
        }
    }

    @Serializable
    enum class Outfit(override val rawValue: String) : RawRepresentable {
        @SerialName("tee") tee("tee"),
        @SerialName("hoodie") hoodie("hoodie"),
        @SerialName("shirt") shirt("shirt"),
        @SerialName("dress") dress("dress"),
        @SerialName("suit") suit("suit");

        companion object {
            fun fromRaw(raw: String?): Outfit? = rawEnum(raw)
        }
    }

    companion object {
        val default = Avatar()

        fun random(): Avatar = Avatar(
            skin = skinTones.indices.random(),
            hair = hairColours.indices.random(),
            hairstyle = Hairstyle.entries.random(),
            top = topColours.indices.random(),
            outfit = Outfit.entries.random(),
        )

        /** Palettes as hex, so Models stays UI-framework free. Index bounds are clamped by the renderer. */
        val skinTones: List<Int> = listOf(0xFFE0C8, 0xF5C9A6, 0xD9A377, 0xB8784E, 0x8D5533, 0x5C3A21)
        val hairColours: List<Int> = listOf(0x1E1410, 0x4A2C1B, 0x8A4B2A, 0xC98A4B, 0xE9D5A1, 0x8A2C2A)
        val topColours: List<Int> = listOf(0x8A2C2A, 0x764534, 0xCA7356, 0xEB9996, 0xC99558, 0x2F5D62, 0x3B4A7A, 0x2A1310)
    }
}

/** Wire shape of `Avatar` (all six keys are always written). */
@Serializable
private data class AvatarWire(
    val skin: Int,
    val hair: Int,
    val hairstyle: Avatar.Hairstyle,
    val top: Int,
    val outfit: Avatar.Outfit,
    val version: Int,
)

/** Tolerant decoding: missing/invalid fields fall back to defaults (an empty `{}` decodes fine). */
object AvatarSerializer : KSerializer<Avatar> {
    override val descriptor: SerialDescriptor = AvatarWire.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Avatar) {
        encoder.encodeSerializableValue(
            AvatarWire.serializer(),
            AvatarWire(value.skin, value.hair, value.hairstyle, value.top, value.outfit, value.version),
        )
    }

    override fun deserialize(decoder: Decoder): Avatar {
        val input = decoder as? JsonDecoder
            ?: return decoder.decodeSerializableValue(AvatarWire.serializer()).let {
                Avatar(it.skin, it.hair, it.hairstyle, it.top, it.outfit, it.version)
            }
        val obj = input.decodeJsonElement() as? JsonObject
            ?: throw SerializationException("avatar_json is not an object")
        fun int(key: String): Int? {
            val p = obj[key] as? JsonPrimitive ?: return null
            if (p.isString || p is JsonNull) return null
            val d = p.doubleOrNull ?: return null
            return if (d == Math.floor(d) && d >= Int.MIN_VALUE && d <= Int.MAX_VALUE) d.toInt() else null
        }
        fun string(key: String): String? = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return Avatar(
            skin = int("skin") ?: 2,
            hair = int("hair") ?: 1,
            hairstyle = Avatar.Hairstyle.fromRaw(string("hairstyle")) ?: Avatar.Hairstyle.short,
            top = int("top") ?: 0,
            outfit = Avatar.Outfit.fromRaw(string("outfit")) ?: Avatar.Outfit.tee,
            version = int("version") ?: 1,
        )
    }
}

@Serializable
enum class Role(override val rawValue: String) : RawRepresentable {
    @SerialName("plaintiff") plaintiff("plaintiff"),
    @SerialName("defendant") defendant("defendant");

    val other: Role get() = if (this == plaintiff) defendant else plaintiff

    companion object {
        fun fromRaw(raw: String?): Role? = rawEnum(raw)
    }
}

@Serializable
enum class Speaker(override val rawValue: String) : RawRepresentable {
    @SerialName("plaintiff") plaintiff("plaintiff"),
    @SerialName("defendant") defendant("defendant"),
    @SerialName("judge") judge("judge");

    companion object {
        fun fromRaw(raw: String?): Speaker? = rawEnum(raw)
    }
}

@Serializable
enum class ExhibitType(override val rawValue: String) : RawRepresentable {
    @SerialName("photo") photo("photo"),
    @SerialName("screenshot") screenshot("screenshot"),
    @SerialName("voice") voice("voice"),
    @SerialName("text") text("text"),
    @SerialName("receipt") receipt("receipt");

    companion object {
        fun fromRaw(raw: String?): ExhibitType? = rawEnum(raw)
    }
}

/** Exhibit letter: A…Z, then AA, AB, … (no cap on exhibits per side). */
@Serializable(with = ExhibitLabelSerializer::class)
class ExhibitLabel(rawValue: String) : Comparable<ExhibitLabel> {
    /** Swift `init(rawValue:)` upper-cases. */
    val rawValue: String = rawValue.uppercase()

    /** Inverse of `at`. */
    val index: Int get() = rawValue.fold(0) { acc, c -> acc * 26 + c.code - 64 } - 1

    override fun toString(): String = rawValue
    val description: String get() = rawValue
    override fun compareTo(other: ExhibitLabel): Int = index.compareTo(other.index)
    override fun equals(other: Any?): Boolean = other is ExhibitLabel && other.rawValue == rawValue
    override fun hashCode(): Int = rawValue.hashCode()

    companion object {
        /** Swift's failable `ExhibitLabel(_ raw:)`: one or two ASCII letters, else null. */
        fun from(raw: String): ExhibitLabel? {
            val u = raw.uppercase()
            if (u.isEmpty() || u.length > 2 || !u.all { it in 'A'..'Z' }) return null
            return ExhibitLabel(u)
        }

        /** 0 → A, 25 → Z, 26 → AA, 27 → AB … */
        fun at(index: Int): ExhibitLabel {
            var i = max(0, index)
            var out = ""
            do {
                out = (65 + i % 26).toChar() + out
                i = i / 26 - 1
            } while (i >= 0)
            return ExhibitLabel(out)
        }

        val A = ExhibitLabel("A")
        val B = ExhibitLabel("B")
        val C = ExhibitLabel("C")
        val D = ExhibitLabel("D")
        val E = ExhibitLabel("E")
        val F = ExhibitLabel("F")
    }
}

object ExhibitLabelSerializer : KSerializer<ExhibitLabel> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("app.plead.ExhibitLabel", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ExhibitLabel) = encoder.encodeString(value.rawValue)
    override fun deserialize(decoder: Decoder): ExhibitLabel = ExhibitLabel(decoder.decodeString())
}

@Serializable
enum class ObjectionReason(override val rawValue: String) : RawRepresentable {
    @SerialName("irrelevant") irrelevant("irrelevant"),
    @SerialName("hearsay") hearsay("hearsay"),
    @SerialName("out_of_context") outOfContext("out_of_context"),
    @SerialName("speculation") speculation("speculation"),
    @SerialName("not_what_happened") notWhatHappened("not_what_happened");

    val title: String
        get() = when (this) {
            irrelevant -> "Irrelevant"
            hearsay -> "Hearsay"
            outOfContext -> "Out of context"
            speculation -> "Speculation"
            notWhatHappened -> "Not what happened"
        }

    companion object {
        fun fromRaw(raw: String?): ObjectionReason? = rawEnum(raw)
    }
}

@Serializable
enum class ObjectionRuling(override val rawValue: String) : RawRepresentable {
    @SerialName("sustained") sustained("sustained"),
    @SerialName("overruled") overruled("overruled");

    companion object {
        fun fromRaw(raw: String?): ObjectionRuling? = rawEnum(raw)
    }
}

@Serializable
enum class VerdictKind(override val rawValue: String) : RawRepresentable {
    @SerialName("ruling") ruling("ruling"),
    @SerialName("appeal") appeal("appeal"),
    @SerialName("guilty") guilty("guilty"),
    @SerialName("default") default("default");

    companion object {
        fun fromRaw(raw: String?): VerdictKind? = rawEnum(raw)
    }
}

@Serializable
enum class JudgePersona(override val rawValue: String) : RawRepresentable {
    @SerialName("wigsworth") wigsworth("wigsworth"),
    @SerialName("blunt") blunt("blunt"),
    @SerialName("sunny") sunny("sunny"),
    @SerialName("chaos") chaos("chaos");

    val displayName: String
        get() = when (this) {
            wigsworth -> "Judge Wigsworth"
            blunt -> "Judge Blunt"
            sunny -> "Judge Sunny"
            chaos -> "Judge Chaos"
        }
    val isFree: Boolean get() = this == wigsworth

    companion object {
        fun fromRaw(raw: String?): JudgePersona? = rawEnum(raw)
    }
}

@Serializable
enum class AICall(override val rawValue: String) : RawRepresentable {
    @SerialName("phase_line") phaseLine("phase_line"),
    @SerialName("objection_ruling") objectionRuling("objection_ruling"),
    @SerialName("cross_examine") crossExamine("cross_examine"),
    @SerialName("juror_evaluate") jurorEvaluate("juror_evaluate"),
    @SerialName("verdict") verdict("verdict"),
    @SerialName("appeal_juror") appealJuror("appeal_juror"),
    @SerialName("appeal_ruling") appealRuling("appeal_ruling"),
    @SerialName("safety") safety("safety"),
    @SerialName("judgement_options") judgementOptions("judgement_options"),
    @SerialName("judgement_delivery") judgementDelivery("judgement_delivery"),
    @SerialName("settlement_options") settlementOptions("settlement_options");

    companion object {
        fun fromRaw(raw: String?): AICall? = rawEnum(raw)
    }
}

@Serializable
enum class JurorRole(override val rawValue: String) : RawRepresentable {
    @SerialName("evidence") evidence("evidence"),
    @SerialName("consistency") consistency("consistency"),
    @SerialName("fairness") fairness("fairness");

    val number: String
        get() = when (this) {
            evidence -> "01"
            consistency -> "02"
            fairness -> "03"
        }
    val mandate: String
        get() = when (this) {
            evidence -> "Evidence"
            consistency -> "Consistency"
            fairness -> "Fairness"
        }

    companion object {
        fun fromRaw(raw: String?): JurorRole? = rawEnum(raw)
    }
}

/** MVP trial order. `next` encodes the sequence; the backend is authoritative. */
@Serializable
enum class TrialPhase(override val rawValue: String) : RawRepresentable {
    @SerialName("plaintiff_opening") plaintiffOpening("plaintiff_opening"),
    @SerialName("defendant_opening") defendantOpening("defendant_opening"),
    @SerialName("plaintiff_exhibits") plaintiffExhibits("plaintiff_exhibits"),
    @SerialName("defendant_exhibits") defendantExhibits("defendant_exhibits"),
    @SerialName("cross_examination") crossExamination("cross_examination"),
    @SerialName("plaintiff_closing") plaintiffClosing("plaintiff_closing"),
    @SerialName("defendant_closing") defendantClosing("defendant_closing");

    val next: TrialPhase?
        get() = entries.getOrNull(ordinal + 1)

    /** Short chip text shown on bubbles. */
    val chipTitle: String
        get() = when (this) {
            plaintiffOpening, defendantOpening -> "Opening"
            plaintiffExhibits, defendantExhibits -> "Exhibits"
            crossExamination -> "Cross-examination"
            plaintiffClosing, defendantClosing -> "Closing"
        }

    /** The side that primarily speaks in this phase (null = judge-led, both answer). */
    val speakingSide: Role?
        get() = when (this) {
            plaintiffOpening, plaintiffExhibits, plaintiffClosing -> Role.plaintiff
            defendantOpening, defendantExhibits, defendantClosing -> Role.defendant
            crossExamination -> null
        }

    companion object {
        fun fromRaw(raw: String?): TrialPhase? = rawEnum(raw)
    }
}

// MARK: - Rows

@Serializable
data class Profile(
    val id: UUID,
    val displayName: String,
    @Required @SerialName("avatar_json") val avatar: Avatar = Avatar.default,
    val coupleId: UUID? = null,
    val pushToken: String? = null,
    val timezone: String? = null,
    /** Onboarding: what the user calls their partner until the partner actually links. */
    val partnerNameTemp: String? = null,
    val onboardingCompletedAt: Instant? = null,
    @Required val createdAt: Instant = Instant.now(),
)

@Serializable
data class Couple(
    val id: UUID,
    val inviteCode: String,
    val inviteExpiresAt: Instant,
    val premiumUntil: Instant? = null,
    val payerUserId: UUID? = null,
    @Required val judgePersona: JudgePersona = JudgePersona.wigsworth,
    val linkedAt: Instant? = null,
    /** Optional relationship start date from onboarding ("Together since"). */
    val togetherSince: Instant? = null,
    @Required val createdAt: Instant = Instant.now(),
) {
    val isPremium: Boolean get() = (premiumUntil ?: Instant.MIN).isAfter(Instant.now())
    val isLinked: Boolean get() = linkedAt != null
}

@Serializable
data class Case(
    val id: UUID,
    val coupleId: UUID,
    val caseNumber: Int,
    val title: String,
    val plaintiffId: UUID,
    val defendantId: UUID,
    val status: CaseStatus,
    val phase: TrialPhase? = null,
    val phaseTurnOwner: Role? = null,
    val charge: String,
    val remedyRequested: String,
    val counterClaim: String? = null,
    /** The defendant's written defence (cases.defence_statement), set by file_defence. */
    val defenceStatement: String? = null,
    val plea: Plea? = null,
    val proposedTrialAt: Instant? = null,
    @Required val proposalCount: Int = 0,
    val trialAt: Instant? = null,
    val verdictAt: Instant? = null,
    val deadlineAt: Instant? = null,
    /** Pending settlement (Settle Outside Court); null when none. Timers are paused while set. */
    val settlementId: UUID? = null,
    /** 0 = panel not started, 1–3 = jurors finished, 4 = presiding judge finished. Safe to show; leaks nothing. */
    @Required val panelProgress: Int = 0,
    val deliberatingAt: Instant? = null,
    @Required val createdAt: Instant = Instant.now(),
    @Required val updatedAt: Instant = Instant.now(),
    val closedAt: Instant? = null,
) {
    fun role(of: UUID): Role? = when (of) {
        plaintiffId -> Role.plaintiff
        defendantId -> Role.defendant
        else -> null
    }

    /** Swift `userId(for:)`. */
    fun userId(role: Role): UUID = if (role == Role.plaintiff) plaintiffId else defendantId
    val formattedNumber: String get() = "Case #$caseNumber"
}

@Serializable
data class Exhibit(
    val id: UUID,
    val caseId: UUID,
    val ownerId: UUID,
    val label: ExhibitLabel,
    val type: ExhibitType,
    val storagePath: String? = null,
    val caption: String,
    val body: String? = null,
    val objectionReason: ObjectionReason? = null,
    val objectionRuling: ObjectionRuling? = null,
    val objectionNote: String? = null,
    val weight: Int? = null,
    val presentedAt: Instant? = null,
    /** Optional date/time the evidence refers to (receipts, screenshots). */
    val occurredAt: Instant? = null,
    @Required val sort: Int = 0,
    @Required val createdAt: Instant = Instant.now(),
) {
    val displayName: String get() = "Exhibit ${label.rawValue}"
}

@Serializable
data class Turn(
    val id: UUID,
    val caseId: UUID,
    val phase: TrialPhase? = null,
    val speaker: Speaker,
    val body: String,
    val exhibitId: UUID? = null,
    val aiCall: AICall? = null,
    @Required val meta: JSONValue = JSONValue.Obj(emptyMap()),
    @Required val createdAt: Instant = Instant.now(),
) {
    /** Set by the backend on objection turns: meta.objection = {reason, ruling} */
    val isObjection: Boolean get() = meta["objection"] != null
    val objectionReason: ObjectionReason? get() = ObjectionReason.fromRaw(meta["objection"]?.get("reason")?.stringValue)
    val objectionRuling: ObjectionRuling? get() = ObjectionRuling.fromRaw(meta["objection"]?.get("ruling")?.stringValue)

    /** Set on cross_examine judge turns: meta.questions = [String], meta.side = role */
    val questions: List<String> get() = meta["questions"]?.arrayValue?.mapNotNull { it.stringValue } ?: emptyList()
    val crossSide: Role? get() = Role.fromRaw(meta["side"]?.stringValue)

    /** Set on the safety-valve turn: meta.safety = true */
    val isSafetyNotice: Boolean get() = meta["safety"]?.boolValue == true
}

@Serializable
data class VerdictFinding(
    val exhibitId: UUID?,
    val label: String,       // "Exhibit B"
    val side: Role,
    val finding: String,
    val weight: Int,         // 0...3
)

@Serializable
data class Verdict(
    val id: UUID,
    val caseId: UUID,
    val kind: VerdictKind,
    val winnerId: UUID?,
    val isTie: Boolean,
    val recap: String,
    val findings: List<VerdictFinding>,
    val sentence: String,
    val closingLine: String,
    val shareImagePath: String? = null,
    @Required val panelRound: Int = 1,
    val panelSplit: String? = null,          // "2-1", "3-0", "1-1-1"
    val panelVotes: JSONValue? = null,       // {plaintiff:n, defendant:n, tie:n}
    val confidenceLabel: String? = null,     // high | medium | low
    val majorityConflict: String? = null,    // judge's explanation when ruling against the panel majority
    val modelRef: String? = null,
    val promptVersion: String? = null,
    /** True when the panel or judge fell back during an AI outage; revealed only after a 6 h grace period. */
    val isFallback: Boolean? = null,
    @Required val createdAt: Instant = Instant.now(),
) {
    val panelSplitHeadline: String
        get() {
            val split = panelSplit ?: return if (isTie) "TIE" else "DECISION"
            return if (isTie) "TIE · $split PANEL" else "$split PANEL DECISION"
        }
}

/** One juror's structured review. Hidden from clients until the verdict is revealed. */
@Serializable
data class JurorReview(
    val id: UUID,
    val caseId: UUID,
    @Required val panelRound: Int = 1,
    val jurorRole: JurorRole,
    val findings: JSONValue,
    val preferredWinnerId: UUID?,
    val isTie: Boolean,
    val confidence: Double,
    val modelRef: String? = null,
    val promptVersion: String? = null,
    @Required val createdAt: Instant = Instant.now(),
) {
    val summary: String? get() = findings["summary"]?.stringValue
}

@Serializable
data class Appeal(
    val id: UUID,
    val caseId: UUID,
    val appellantId: UUID,
    val argument: String,
    val exhibitId: UUID? = null,
    val reply: String? = null,
    val outcome: String? = null,
    val createdAt: Instant,
)

// MARK: - Winner-selected court judgement (CONTRACTS-v2 amendment j)

@Serializable
enum class JudgementStatus(override val rawValue: String) : RawRepresentable {
    @SerialName("pending_selection") pendingSelection("pending_selection"),
    @SerialName("delivered") delivered("delivered"),
    @SerialName("accepted") accepted("accepted"),
    @SerialName("served") served("served"),
    @SerialName("declined") declined("declined");

    val title: String
        get() = when (this) {
            pendingSelection -> "Awaiting the winner's choice"
            delivered -> "Delivered"
            accepted -> "Accepted"
            served -> "Served"
            declined -> "Declined"
        }

    companion object {
        fun fromRaw(raw: String?): JudgementStatus? = rawEnum(raw)
    }
}

@Serializable
enum class JudgementOptionType(override val rawValue: String) : RawRepresentable {
    @SerialName("direct_remedy") directRemedy("direct_remedy"),
    @SerialName("effort") effort("effort"),
    @SerialName("privilege") privilege("privilege"),
    @SerialName("favour") favour("favour"),
    @SerialName("compromise") compromise("compromise");

    companion object {
        fun fromRaw(raw: String?): JudgementOptionType? = rawEnum(raw)
    }
}

@Serializable
data class JudgementOption(
    val id: String,
    val title: String,
    val detail: String,
    val type: JudgementOptionType,
    val dueDays: Int,
    val generic: Boolean? = null,
)

/** One generated set of options; readable only by the chooser. */
@Serializable
data class JudgementOptionSet(
    val id: UUID,
    val caseId: UUID,
    val round: Int,
    val options: List<JudgementOption>,
    val generatedFor: UUID,
    val createdAt: Instant,
)

@Serializable
data class Judgement(
    val caseId: UUID,
    val verdictId: UUID? = null,
    val theme: String? = null,
    @Required val status: JudgementStatus = JudgementStatus.pendingSelection,
    /** null = the court chose (tie). */
    val chooserId: UUID? = null,
    val selected: JudgementOption? = null,
    val selectedBy: UUID? = null,
    val selectedAt: Instant? = null,
    val deliveredTurnId: UUID? = null,
    val acceptedAt: Instant? = null,
    val declinedAt: Instant? = null,
    val servedAt: Instant? = null,
    val servedBy: UUID? = null,
    val dueAt: Instant? = null,
    @Required val rerolls: Int = 0,
    @Required val createdAt: Instant = Instant.now(),
    @Required val updatedAt: Instant = Instant.now(),
) {
    val id: UUID get() = caseId
    val rerollsLeft: Int get() = max(0, maxRerolls - rerolls)
    val isSettled: Boolean get() = status == JudgementStatus.served
    val isCourtChosen: Boolean get() = chooserId == null
    val isOutstanding: Boolean get() = status == JudgementStatus.delivered || status == JudgementStatus.accepted

    companion object {
        const val maxRerolls = 2
    }
}

// MARK: - Settle Outside Court (CONTRACTS-v2 amendment n)

@Serializable
enum class SettlementStatus(override val rawValue: String) : RawRepresentable {
    @SerialName("proposed") proposed("proposed"),
    @SerialName("countered") countered("countered"),
    @SerialName("accepted") accepted("accepted"),
    @SerialName("rejected") rejected("rejected"),
    @SerialName("withdrawn") withdrawn("withdrawn"),
    @SerialName("expired") expired("expired"),
    @SerialName("fulfilled") fulfilled("fulfilled");

    val isPending: Boolean get() = this == proposed || this == countered
    val isAgreed: Boolean get() = this == accepted || this == fulfilled

    companion object {
        fun fromRaw(raw: String?): SettlementStatus? = rawEnum(raw)
    }
}

@Serializable
enum class SettlementSource(override val rawValue: String) : RawRepresentable {
    @SerialName("ai") ai("ai"),
    @SerialName("custom") custom("custom");

    companion object {
        fun fromRaw(raw: String?): SettlementSource? = rawEnum(raw)
    }
}

@Serializable
enum class SettlementSuggestionKind(override val rawValue: String) : RawRepresentable {
    @SerialName("quick") quick("quick"),
    @SerialName("fair") fair("fair"),
    @SerialName("peace") peace("peace");

    val title: String
        get() = when (this) {
            quick -> "Quick compromise"
            fair -> "Fair compromise"
            peace -> "Peace offering"
        }

    companion object {
        fun fromRaw(raw: String?): SettlementSuggestionKind? = rawEnum(raw)
    }
}

@Serializable
data class SettlementSuggestion(
    val id: String,
    val kind: SettlementSuggestionKind,
    val body: String,
    val category: String? = null,
    @Required val dueDays: Int = 7,
    val generic: Boolean? = null,
)

@Serializable
data class SettlementOffer(
    val id: UUID,
    val settlementId: UUID,
    val roundNumber: Int,
    val proposedBy: UUID,
    val body: String,
    val source: SettlementSource,
    val category: String? = null,
    @Required val dueDays: Int = 7,
    @Required val createdAt: Instant = Instant.now(),
    val expiresAt: Instant? = null,
)

@Serializable
data class Settlement(
    val id: UUID,
    val caseId: UUID,
    val status: SettlementStatus,
    val initiatedBy: UUID,
    @Required val currentRound: Int = 1,
    val entryPoint: String? = null,
    val pausedFromStatus: CaseStatus? = null,
    val pausedPhase: TrialPhase? = null,
    val pausedTurnOwner: Role? = null,
    val pausedRemainingSeconds: Int? = null,
    val expiresAt: Instant? = null,
    val acceptedAt: Instant? = null,
    val rejectedAt: Instant? = null,
    val withdrawnAt: Instant? = null,
    val expiredAt: Instant? = null,
    val fulfilledAt: Instant? = null,
    val fulfilledBy: UUID? = null,
    val dueAt: Instant? = null,
    val acceptedOfferId: UUID? = null,
    @Required val createdAt: Instant = Instant.now(),
    @Required val updatedAt: Instant = Instant.now(),
) {
    val isPending: Boolean get() = status.isPending
    val roundsLeft: Int get() = max(0, maxRounds - currentRound)
    val isFinalRound: Boolean get() = currentRound >= maxRounds

    companion object {
        const val maxRounds = 3
    }
}

// MARK: - Edge function envelope

@Serializable
data class EdgeError(
    val code: String,
    override val message: String,
) : Exception(message) {
    /** Swift `LocalizedError.errorDescription`. */
    val errorDescription: String get() = message
}

// MARK: - Free / premium limits (mirror of CONTRACTS.md)

class Limits(
    val openCases: Int?,         // null = unlimited (amendment ag)
    val casesPerWeek: Int?,      // null = unlimited
    val appeals: Boolean,
    val historyCases: Int?,      // null = full
) {
    companion object {
        /** Server-side anti-abuse guard on exhibits per side. Not a product limit. */
        const val exhibitsSanityGuard = 100

        /**
         * Plead is a paid app (decision 2026-09-24): there is no free tier. Every subscribed couple gets
         * the same product. Non-subscribed couples are gated by the paywall and cannot file.
         */
        val subscribed = Limits(openCases = null, casesPerWeek = null, appeals = true, historyCases = null)

        @Deprecated("No free tier; use Limits.subscribed", ReplaceWith("Limits.subscribed"))
        val free = subscribed

        @Deprecated("Use Limits.subscribed", ReplaceWith("Limits.subscribed"))
        val premium = subscribed

        @Suppress("UNUSED_PARAMETER")
        fun `for`(premium: Boolean): Limits = subscribed
    }
}

// MARK: - JSON helper for jsonb columns

/**
 * Swift `enum JSONValue { case string, number, bool, null, array, object }` → [Str], [Num], [Bool], [Null],
 * [Arr], [Obj]. Decoding order matches Swift: null, bool, number, string, array, object.
 */
@Serializable(with = JSONValueSerializer::class)
sealed class JSONValue {
    data class Str(val value: String) : JSONValue()
    data class Num(val value: Double) : JSONValue()
    data class Bool(val value: Boolean) : JSONValue()
    data object Null : JSONValue()
    data class Arr(val value: List<JSONValue>) : JSONValue()
    data class Obj(val value: Map<String, JSONValue>) : JSONValue()

    operator fun get(key: String): JSONValue? = (this as? Obj)?.value?.get(key)
    val stringValue: String? get() = (this as? Str)?.value
    val boolValue: Boolean? get() = (this as? Bool)?.value
    val arrayValue: List<JSONValue>? get() = (this as? Arr)?.value
    val numberValue: Double? get() = (this as? Num)?.value
    val objectValue: Map<String, JSONValue>? get() = (this as? Obj)?.value

    fun toJsonElement(): JsonElement = when (this) {
        is Str -> JsonPrimitive(value)
        // Swift's JSONEncoder writes an integral Double without a fraction (2, not 2.0).
        is Num -> if (value == Math.floor(value) && !value.isInfinite() && kotlin.math.abs(value) < 1e15) JsonPrimitive(value.toLong()) else JsonPrimitive(value)
        is Bool -> JsonPrimitive(value)
        Null -> JsonNull
        is Arr -> JsonArray(value.map { it.toJsonElement() })
        is Obj -> JsonObject(value.mapValues { it.value.toJsonElement() })
    }

    companion object {
        fun from(element: JsonElement): JSONValue = when (element) {
            is JsonNull -> Null
            is JsonPrimitive -> when {
                element.isString -> Str(element.content)
                element.booleanOrNull != null -> Bool(element.booleanOrNull!!)
                element.doubleOrNull != null -> Num(element.doubleOrNull!!)
                else -> throw SerializationException("Unsupported JSON")
            }
            is JsonArray -> Arr(element.map { from(it) })
            is JsonObject -> Obj(element.mapValues { from(it.value) })
        }
    }
}

object JSONValueSerializer : KSerializer<JSONValue> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun serialize(encoder: Encoder, value: JSONValue) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("JSONValue needs a JSON encoder")
        json.encodeJsonElement(value.toJsonElement())
    }

    override fun deserialize(decoder: Decoder): JSONValue {
        val json = decoder as? JsonDecoder ?: throw SerializationException("JSONValue needs a JSON decoder")
        return JSONValue.from(json.decodeJsonElement())
    }
}
