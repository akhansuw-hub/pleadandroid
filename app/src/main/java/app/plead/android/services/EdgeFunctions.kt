// Port of ArgueWin/Services/EdgeFunctions.swift.
@file:UseSerializers(SupabaseDateSerializer::class, UUIDSerializer::class)

package app.plead.android.services

import app.plead.android.models.Case
import app.plead.android.models.Couple
import app.plead.android.models.EdgeError
import app.plead.android.models.ExhibitLabel
import app.plead.android.models.ExhibitType
import app.plead.android.models.Judgement
import app.plead.android.models.JudgementOptionSet
import app.plead.android.models.ObjectionReason
import app.plead.android.models.Plea
import app.plead.android.models.Settlement
import app.plead.android.models.SettlementOffer
import app.plead.android.models.SettlementSource
import app.plead.android.models.SettlementSuggestion
import app.plead.android.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.functions.functions
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

/**
 * Inline exhibit payload sent with `file_case` / `file_defence`.
 * Files are uploaded first to `{couple_id}/pending/{uuid}`; the server inserts the
 * `exhibits` rows and moves objects to `{couple_id}/{case_id}/{exhibit_id}`.
 */
@Serializable
data class ExhibitPayload(
    val label: ExhibitLabel,
    val type: ExhibitType,
    val caption: String,
    val body: String? = null,
    val storagePath: String? = null,
    /** Optional date/time on receipt/screenshot exhibits (`exhibits.occurred_at`). */
    val occurredAt: Instant? = null,
)

/**
 * Typed wrappers for every client-callable edge function in docs/CONTRACTS.md.
 * Responses use the `{ok:true, case, ...}` / `{ok:false, code, message}` envelope;
 * failures are always surfaced as [EdgeError].
 */
class EdgeFunctions(val client: SupabaseClient) {

    // MARK: Couples

    @Serializable
    data class CreateCoupleResult(val couple: Couple, val inviteCode: String? = null)

    suspend fun createCouple(): CreateCoupleResult = call("create_couple", JsonObject(emptyMap()), CreateCoupleResult.serializer())

    @Serializable
    data class JoinCoupleResult(val couple: Couple? = null)

    suspend fun joinCouple(code: String): JoinCoupleResult =
        call("join_couple", buildJsonObject { put("code", code.uppercase()) }, JoinCoupleResult.serializer())

    /**
     * `leave_couple` (20260924000900): pre-verdict cases become mistrials; `verdict` / `appeal` cases
     * are closed with the verdict kept. Decoded leniently (every field optional).
     */
    data class LeaveCoupleResult(
        val couple: Couple?,
        val leftCoupleId: UUID?,
        val mistrialCaseIds: List<UUID>,
        val closedCaseIds: List<UUID>,
    ) {
        companion object {
            fun from(o: JsonObject): LeaveCoupleResult = LeaveCoupleResult(
                couple = o.lenient("couple", Couple.serializer()),
                leftCoupleId = o.lenient("left_couple_id", UUIDSerializer),
                mistrialCaseIds = o.lenient("mistrial_case_ids", ListSerializer(UUIDSerializer)) ?: emptyList(),
                closedCaseIds = o.lenient("closed_case_ids", ListSerializer(UUIDSerializer)) ?: emptyList(),
            )
        }
    }

    suspend fun leaveCouple(): LeaveCoupleResult = LeaveCoupleResult.from(callObject("leave_couple", JsonObject(emptyMap())))

    // MARK: Case lifecycle

    @Serializable
    private data class FileCaseBody(
        val title: String,
        val charge: String,
        val remedyRequested: String,
        val exhibits: List<ExhibitPayload>,
        val exhibitIds: List<UUID>,
    )

    suspend fun fileCase(title: String, charge: String, remedyRequested: String, exhibits: List<ExhibitPayload>): Case =
        callCase("file_case", encode(FileCaseBody(title, charge, remedyRequested, exhibits, emptyList())))

    @Serializable
    private data class PleaBody(val caseId: UUID, val plea: Plea)

    suspend fun enterPlea(caseId: UUID, plea: Plea): Case = callCase("enter_plea", encode(PleaBody(caseId, plea)))

    @Serializable
    private data class FileDefenceBody(
        val caseId: UUID,
        val statement: String,
        val counterClaim: String?,
        val exhibits: List<ExhibitPayload>,
        val exhibitIds: List<UUID>,
        val proposedTrialAt: Instant,
    )

    suspend fun fileDefence(caseId: UUID, statement: String, counterClaim: String?, exhibits: List<ExhibitPayload>, proposedTrialAt: Instant): Case =
        callCase("file_defence", encode(FileDefenceBody(caseId, statement, counterClaim, exhibits, emptyList(), proposedTrialAt)))

    @Serializable
    private data class ProposeTimeBody(val caseId: UUID, val proposedTrialAt: Instant)

    suspend fun proposeTime(caseId: UUID, proposedTrialAt: Instant): Case =
        callCase("propose_time", encode(ProposeTimeBody(caseId, proposedTrialAt)))

    suspend fun acceptTime(caseId: UUID): Case = callCase("accept_time", encode(CaseOnly(caseId)))

    @Serializable
    private data class SubmitTurnBody(val caseId: UUID, val body: String, val exhibitId: UUID?)

    suspend fun submitTurn(caseId: UUID, body: String, exhibitId: UUID?): Case =
        callCase("submit_turn", encode(SubmitTurnBody(caseId, body, exhibitId)))

    @Serializable
    private data class ObjectBody(val caseId: UUID, val exhibitId: UUID, val reason: ObjectionReason)

    @Serializable
    private data class PassBody(val caseId: UUID, val exhibitId: UUID, val pass: Boolean)

    /** `reason == null` passes on the objection window. */
    suspend fun raiseObjection(caseId: UUID, exhibitId: UUID, reason: ObjectionReason?): Case {
        if (reason != null) return callCase("raise_objection", encode(ObjectBody(caseId, exhibitId, reason)))
        return callCase("raise_objection", encode(PassBody(caseId, exhibitId, true)))
    }

    suspend fun withdrawCase(caseId: UUID): Case = callCase("withdraw_case", encode(CaseOnly(caseId)))
    suspend fun requestDefault(caseId: UUID): Case = callCase("request_default", encode(CaseOnly(caseId)))

    /** Appeals are cut from the MVP; the backend returns 501 `not_implemented`. */
    suspend fun fileAppeal(caseId: UUID): Case = callCase("file_appeal", encode(CaseOnly(caseId)))

    // MARK: Judgement (CONTRACTS-v2 amendment j)

    /**
     * `reroll_judgement {case_id}` → `{ok, options}`. The chooser only; at most `Judgement.maxRerolls`
     * (`limit_rerolls`). The options are re-read from `judgement_options` afterwards, so a missing or
     * partial `options` payload decodes to null rather than failing the call.
     */
    data class RerollResult(val options: JudgementOptionSet?) {
        companion object {
            fun from(o: JsonObject) = RerollResult(o.lenient("options", JudgementOptionSet.serializer()))
        }
    }

    @Serializable
    data class JudgementResult(val judgement: Judgement)

    @Serializable
    data class SelectJudgementBody(val caseId: UUID, val optionId: String)

    @Serializable
    data class RespondJudgementBody(val caseId: UUID, val accept: Boolean)

    @Serializable
    data class JudgementCaseBody(val caseId: UUID)

    suspend fun rerollJudgement(caseId: UUID): RerollResult =
        RerollResult.from(callObject("reroll_judgement", encode(JudgementCaseBody(caseId))))

    suspend fun selectJudgement(caseId: UUID, optionId: String): Judgement =
        call("select_judgement", encode(SelectJudgementBody(caseId, optionId)), JudgementResult.serializer()).judgement

    suspend fun respondJudgement(caseId: UUID, accept: Boolean): Judgement =
        call("respond_judgement", encode(RespondJudgementBody(caseId, accept)), JudgementResult.serializer()).judgement

    suspend fun markServed(caseId: UUID): Judgement =
        call("mark_served", encode(JudgementCaseBody(caseId)), JudgementResult.serializer()).judgement

    // MARK: Settle Outside Court (CONTRACTS-v2 amendment n)

    /**
     * `generate_settlement_options {case_id}` → `{ok, options: [SettlementSuggestion], summary}`.
     * `summary` is the neutral one-line case context; `source` ("model" | "fallback") is optional.
     */
    data class SettlementOptionsResult(val options: List<SettlementSuggestion>, val summary: String?, val source: String?) {
        companion object {
            fun from(o: JsonObject) = SettlementOptionsResult(
                options = o.lenient("options", ListSerializer(SettlementSuggestion.serializer())) ?: emptyList(),
                summary = o.lenient("summary", serializer<String>())?.trim()?.takeIf { it.isNotEmpty() },
                source = o.lenient("source", serializer<String>()),
            )
        }
    }

    /** `{ok, settlement?, offer?, case?}`: every settlement mutation answers with some of these. */
    data class SettlementResult(val settlement: Settlement?, val offer: SettlementOffer?, val kase: Case?) {
        companion object {
            fun from(o: JsonObject) = SettlementResult(
                settlement = o.lenient("settlement", Settlement.serializer()),
                offer = o.lenient("offer", SettlementOffer.serializer()),
                kase = o.lenient("case", Case.serializer()),
            )
        }
    }

    @Serializable
    data class SettlementCaseBody(val caseId: UUID)

    @Serializable
    data class ProposeSettlementBody(
        val caseId: UUID,
        val body: String,
        val source: SettlementSource,
        val suggestionId: String?,
        val dueDays: Int?,
    )

    @Serializable
    data class CounterSettlementBody(
        val settlementId: UUID,
        val body: String,
        val source: SettlementSource,
        val dueDays: Int?,
    )

    @Serializable
    enum class SettlementResponse { accept, reject }

    @Serializable
    data class RespondSettlementBody(val settlementId: UUID, val action: SettlementResponse)

    @Serializable
    data class SettlementIdBody(val settlementId: UUID)

    suspend fun generateSettlementOptions(caseId: UUID): SettlementOptionsResult =
        SettlementOptionsResult.from(callObject("generate_settlement_options", encode(SettlementCaseBody(caseId))))

    suspend fun proposeSettlement(caseId: UUID, body: String, source: SettlementSource, suggestionId: String?, dueDays: Int?): SettlementResult =
        SettlementResult.from(callObject("propose_settlement", encode(ProposeSettlementBody(caseId, body, source, suggestionId, dueDays))))

    suspend fun counterSettlement(settlementId: UUID, body: String, source: SettlementSource, dueDays: Int?): SettlementResult =
        SettlementResult.from(callObject("counter_settlement", encode(CounterSettlementBody(settlementId, body, source, dueDays))))

    suspend fun respondToSettlement(settlementId: UUID, accept: Boolean): SettlementResult =
        SettlementResult.from(
            callObject(
                "respond_to_settlement",
                encode(RespondSettlementBody(settlementId, if (accept) SettlementResponse.accept else SettlementResponse.reject)),
            ),
        )

    suspend fun withdrawSettlement(settlementId: UUID): SettlementResult =
        SettlementResult.from(callObject("withdraw_settlement", encode(SettlementIdBody(settlementId))))

    suspend fun markSettlementFulfilled(settlementId: UUID): SettlementResult =
        SettlementResult.from(callObject("mark_settlement_fulfilled", encode(SettlementIdBody(settlementId))))

    // MARK: Account

    /**
     * `register_push {token?, timezone?, platform?}`: the only writer of the caller's push token and time zone
     * (`public.push_tokens`). See [PushRegistration] for the three shapes the app sends.
     */
    data class RegisterPushResult(val push: Push?) {
        @Serializable
        data class Push(val registered: Boolean? = null, val timezone: String? = null)

        companion object {
            fun from(o: JsonObject) = RegisterPushResult(o.lenient("push", Push.serializer()))
        }
    }

    suspend fun registerPush(registration: PushRegistration): RegisterPushResult =
        RegisterPushResult.from(callObject("register_push", registration.body()))

    /** `delete_account` (CONTRACTS-v2 amendment i). The server requires an explicit `{confirm: true}`. */
    @Serializable
    data class DeleteAccountBody(val confirm: Boolean = true)

    suspend fun deleteAccount() {
        callObject("delete_account", encode(DeleteAccountBody()))
    }

    // MARK: Plumbing

    @Serializable
    private data class CaseOnly(val caseId: UUID)

    private suspend fun callCase(name: String, body: JsonElement): Case {
        val o = callObject(name, body)
        val row = o["case"] ?: throw badResponse()
        return runCatching { JSONCoding.json.decodeFromJsonElement(Case.serializer(), row) }.getOrElse { throw badResponse() }
    }

    private suspend fun callObject(name: String, body: JsonElement): JsonObject = call(name, body, JsonObject.serializer())

    /** The request body, encoded with the app's JSON (snake_case keys, ISO dates). */
    private inline fun <reified B> encode(body: B): JsonElement = JSONCoding.json.encodeToJsonElement(serializer<B>(), body)

    suspend fun <R> call(name: String, body: JsonElement, deserializer: DeserializationStrategy<R>): R {
        val text: String = try {
            client.functions.invoke(function = name) {
                setBody(TextContent(JSONCoding.json.encodeToString(JsonElement.serializer(), body), ContentType.Application.Json))
            }.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: EdgeError) {
            throw e
        } catch (e: RestException) {
            val error = edgeError(from = e.error, status = e.statusCode)
            announce(error)
            throw error
        } catch (e: Exception) {
            throw EdgeError(code = "network", message = "Couldn't reach the court. Check your connection and try again.")
        }
        val status = runCatching { JSONCoding.json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        if (status != null && (status["ok"] as? JsonPrimitive)?.content == "false") {
            val code = (status["code"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val message = (status["message"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val error = EdgeError(code = code ?: "unknown", message = message ?: "Something went wrong.")
            announce(error)
            throw error
        }
        return try {
            JSONCoding.json.decodeFromString(deserializer, text)
        } catch (e: Exception) {
            throw badResponse()
        }
    }

    private fun badResponse() = EdgeError(code = "bad_response", message = "The court's reply was unreadable. Please try again.")

    companion object {
        /**
         * App-wide answers to gate errors: 402 `premium_required` → paywall; 403 `identity_required` →
         * SecureAccountView (amendment p).
         */
        fun announce(error: EdgeError) {
            if (error.isPremiumRequired) announcePremiumRequired()
            if (error.isIdentityRequired) AppNotifications.post(AppNotifications.awIdentityRequired)
        }

        fun announcePremiumRequired() {
            AppNotifications.post(AppNotifications.awPremiumRequired)
        }

        fun edgeError(from: String, status: Int): EdgeError {
            val decoded = runCatching { JSONCoding.json.decodeFromString(EdgeError.serializer(), from) }.getOrNull()
            if (status == 402 && decoded == null) {
                return EdgeError(code = "premium_required", message = "A Plead subscription is needed to file a case.")
            }
            if (decoded != null) return decoded
            if (status == 404) return EdgeError(code = "not_found", message = "This feature isn't available yet.")
            if (status == 501) return EdgeError(code = "not_implemented", message = "This feature isn't available yet.")
            return EdgeError(code = "http_$status", message = "Something went wrong ($status). Please try again.")
        }

        fun edgeError(from: ByteArray, status: Int): EdgeError = edgeError(from.decodeToString(), status)
    }
}

/** One key of an edge response decoded on its own: a missing or malformed value is null (Swift `try?`). */
internal fun <T> JsonObject.lenient(key: String, serializer: KSerializer<T>): T? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    return runCatching { JSONCoding.json.decodeFromJsonElement(serializer, element) }.getOrNull()
}

// MARK: - Paid-app gate

/**
 * Swift `NotificationCenter` names the app posts and `AppModel` observes (always on the main thread there; here any
 * collector decides its dispatcher).
 */
object AppNotifications {
    enum class Name { awPremiumRequired, awIdentityRequired, pleadSilentRefresh }

    /** Posted when any edge function answers 402 `premium_required`. AppModel presents the paywall gate. */
    val awPremiumRequired = Name.awPremiumRequired

    /** Posted when any edge function answers 403 `identity_required` (anonymous session; amendment p). */
    val awIdentityRequired = Name.awIdentityRequired

    /** Posted when a silent (data-only) push asks the app to refresh its state (widget, court notification). */
    val pleadSilentRefresh = Name.pleadSilentRefresh

    private val _events = MutableSharedFlow<Name>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<Name> = _events.asSharedFlow()

    fun post(name: Name) {
        _events.tryEmit(name)
    }
}

/** The couple has no active subscription (file_case → 402). The app answers with the gate. */
val EdgeError.isPremiumRequired: Boolean get() = code == "premium_required"

/** The server refused an anonymous session (amendment p): link an Apple ID / Google / email first. */
val EdgeError.isIdentityRequired: Boolean get() = code == "identity_required"

/** `join_couple` answered 429: 10 wrong invite codes in the last hour. */
val EdgeError.isTooManyAttempts: Boolean get() = code == "too_many_attempts" || code == "http_429"

/** `reroll_judgement` answered `limit_rerolls`: both extra suggestions have been used. */
val EdgeError.isRerollLimit: Boolean get() = code == "limit_rerolls"

/** `counter_settlement` answered `limit_rounds`: the third round is final. */
val EdgeError.isSettlementRoundLimit: Boolean get() = code == "limit_rounds"

/** `propose_settlement` / `counter_settlement` answered 422 `unsafe_terms` (settlement denylist). */
val EdgeError.isUnsafeTerms: Boolean get() = code == "unsafe_terms" || code == "http_422"

/** The safety valve stopped the case (it is now a mistrial). */
val EdgeError.isSafetyStop: Boolean get() = code == "safety_stop"

/**
 * The static members of Swift's `extension EdgeError` (Kotlin cannot add statics to the model class, which lives in
 * the additive-only Models.kt): `EdgeError.joinMessage(for:)` → `EdgeErrors.joinMessage(error)`.
 */
object EdgeErrors {
    const val rerollLimitMessage = "The court has no further suggestions for this case."

    /** Inline copy for a failed judgement call (selection screen, status card). */
    fun judgementMessage(error: Throwable): String {
        val e = error as? EdgeError ?: return "Couldn't reach the court. Try again."
        if (e.isRerollLimit) return rerollLimitMessage
        if (e.code == "wrong_state") return "This judgement has moved on. Pull to refresh."
        return e.message
    }

    const val settlementRoundLimitMessage = "No more counter-offers. Accept it, or see them in court."
    const val unsafeTermsMessage = "The court can't accept those terms. Keep it small, kind and doable."

    /** Inline copy for a failed settlement call (room, response sheet, fulfilment card). */
    fun settlementMessage(error: Throwable): String {
        val e = error as? EdgeError ?: return "Couldn't reach the court. Try again."
        return when (e.code) {
            "limit_rounds" -> settlementRoundLimitMessage
            "unsafe_terms", "http_422" -> unsafeTermsMessage
            "wrong_state" -> "This settlement has moved on. Pull to refresh."
            "wrong_role" -> "It's your partner's move on this offer."
            "safety_stop" -> "The court has paused this case. Please take a look at the support resources."
            "invalid_body", "invalid_input" -> if (e.message.isEmpty()) "Those terms can't be sent. Keep them short, kind and doable." else e.message
            else -> e.message
        }
    }

    const val tooManyJoinAttemptsMessage = "Too many attempts. Try again in an hour."

    /** Inline copy for a failed `join_couple` (onboarding link step and LinkCoupleView). */
    fun joinMessage(error: Throwable): String {
        val e = error as? EdgeError ?: return "Couldn't join. Try again."
        if (e.isTooManyAttempts) return tooManyJoinAttemptsMessage
        if (e.code == "not_found" || e.code == "invalid_code") return "That code doesn't match an open invite."
        if (e.code == "wrong_state") {
            // `couple_join`'s details (amendment as): plain copy instead of the raw server detail.
            val detail = e.message.lowercase()
            if (detail.contains("own invite")) return "That's your own invite code. Send it to your partner instead."
            if (detail.contains("already linked")) return "That invite has already been used. Ask your partner for a fresh code."
            if (detail.contains("leave your current couple")) return "You're already linked with a partner."
            return "That invite can't be used right now. Ask your partner for a fresh code."
        }
        return e.message
    }
}

/**
 * A `register_push` body. Encodes exactly the keys the server reads:
 * - [timezone]: `{timezone}` (launch / foreground, whatever the notification permission)
 * - [token]: `{token, timezone, platform: "fcm"}` (an FCM token arrived; amendment az)
 * - [clear]: `{token: null}` (sign-out / account deletion, before the session goes)
 */
sealed class PushRegistration {
    data class timezone(val zone: String) : PushRegistration()
    data class token(val token: String, val timezone: String) : PushRegistration()
    data object clear : PushRegistration()

    fun body(): JsonObject = when (this) {
        is timezone -> buildJsonObject { put("timezone", zone) }
        is token -> buildJsonObject {
            put("token", token)
            put("timezone", timezone)
            put("platform", platform)
        }
        clear -> buildJsonObject { put("token", JsonNull) }
    }

    companion object {
        /** Amendment az: Android tokens are FCM registration tokens. */
        const val platform = "fcm"
    }
}

// MARK: - Live Activities (CONTRACTS-v2 amendment o)

/**
 * A `register_live_activity` body: `{kind, token, activity_id?, case_id?, environment}`. Amendment az: the Android
 * client never calls `register_live_activity` (no Live Activities); the type is kept so the shape stays documented and
 * tested alongside the iOS one.
 */
data class LiveActivityRegistration(
    val kind: Kind,
    val token: String,
    val activityId: String? = null,
    val caseId: UUID? = null,
    /** APNs environment the token belongs to (debug builds use the sandbox). */
    val environment: String = currentEnvironment,
) {
    enum class Kind(val rawValue: String) { pushToStart("push_to_start"), update("update") }

    fun body(): JsonObject = buildJsonObject {
        put("kind", kind.rawValue)
        put("token", token)
        activityId?.let { put("activity_id", it) }
        caseId?.let { put("case_id", it.uuidString) }
        put("environment", environment)
    }

    companion object {
        val currentEnvironment: String get() = if (BuildConfig.DEBUG) "development" else "production"
    }
}
