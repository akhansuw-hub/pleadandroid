// Port of ArgueWin/Features/Settlement/SettlementRoomModel.swift.
package app.plead.android.features.settlement

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.plead.android.models.Case
import app.plead.android.models.EdgeError
import app.plead.android.models.Settlement
import app.plead.android.models.SettlementOffer
import app.plead.android.models.SettlementSource
import app.plead.android.models.SettlementSuggestion
import app.plead.android.services.CaseStore
import app.plead.android.services.EdgeErrors
import app.plead.android.services.SettlementRules
import app.plead.android.services.isUnsafeTerms
import java.util.UUID
import kotlin.math.min

/**
 * State for the Settlement Room (propose) and its counter-offer mode. The mode is derived from the
 * store: an offer awaiting my response means I'm countering it. Snapshot state (Swift `@Observable`), main thread.
 */
@Stable
class SettlementRoomModel(val caseId: UUID, private val store: CaseStore) {
    enum class Mode { propose, counter }
    enum class Phase { loading, ready, failed }

    sealed class Choice {
        data class suggestion(val id: String) : Choice()
        data object custom : Choice()
    }

    var phase: Phase by mutableStateOf(Phase.loading)
        private set
    var suggestions: List<SettlementSuggestion> by mutableStateOf(emptyList())
        private set
    var choice: Choice? by mutableStateOf(null)

    private var customTextState by mutableStateOf("")

    /** Capped at [SettlementRules.bodyLimit]; editing clears the composer error. */
    var customText: String
        get() = customTextState
        set(value) {
            val old = customTextState
            val capped = if (value.length > SettlementRules.bodyLimit) value.take(SettlementRules.bodyLimit) else value
            customTextState = capped
            if (capped != old) composerError = null
        }
    var customDays: Int by mutableIntStateOf(SettlementRules.customDefaultDueDays)

    /** Inline composer error (`unsafe_terms`): the text stays for editing. */
    var composerError: String? by mutableStateOf(null)
    var isSending: Boolean by mutableStateOf(false)
        private set
    var didSend: Boolean by mutableStateOf(false)
        private set
    var error: String? by mutableStateOf(null)

    val kase: Case? get() = store.caseById(caseId)
    val settlement: Settlement? get() = store.settlement(caseId)

    /** Counter mode while the current offer awaits my response (the room was opened from COUNTER OFFER). */
    val mode: Mode get() = if (store.pendingSettlementForMe(caseId)) Mode.counter else Mode.propose
    val incomingOffer: SettlementOffer? get() = if (mode == Mode.counter) store.latestOffer(caseId) else null

    /** The round this proposal would open: 1 for a fresh proposal, current + 1 for a counter. */
    val nextRound: Int get() = if (mode == Mode.counter) min((settlement?.currentRound ?: 1) + 1, Settlement.maxRounds) else 1
    val title: String get() = if (mode == Mode.counter) "Counter offer" else "Settlement room"
    val ctaTitle: String get() = if (mode == Mode.counter) "SEND COUNTER OFFER" else "PROPOSE SETTLEMENT"

    /** The server's neutral summary when it has one, else the local template. */
    val contextLine: String get() = store.settlementSummaries[caseId] ?: kase?.let(SettlementRules::contextLine) ?: ""
    val hasGenericSuggestions: Boolean get() = suggestions.any { it.generic == true }

    /** Available: a fresh proposal is allowed, or I'm countering below the round cap. */
    val isAvailable: Boolean
        get() = when (mode) {
            Mode.propose -> store.canProposeSettlement(caseId)
            Mode.counter -> settlement?.let(SettlementRules::canCounter) ?: false
        }

    val trimmedCustom: String get() = customText.trim()
    val safetyIssue: String? get() = if (trimmedCustom.isEmpty()) null else SettlementRules.localSafetyIssue(trimmedCustom)

    val selectedSuggestion: SettlementSuggestion?
        get() = (choice as? Choice.suggestion)?.let { c -> suggestions.firstOrNull { it.id == c.id } }

    val canSend: Boolean
        get() {
            if (!isAvailable || isSending) return false
            return when (choice) {
                is Choice.suggestion -> selectedSuggestion != null
                Choice.custom -> trimmedCustom.isNotEmpty() && trimmedCustom.length <= SettlementRules.bodyLimit
                null -> false
            }
        }

    fun select(choice: Choice) {
        this.choice = choice
        error = null
    }

    /** Fetches the three suggestions (skeleton meanwhile). A failure still leaves "Write our own". */
    suspend fun start() {
        if (phase == Phase.ready && suggestions.isNotEmpty()) return
        phase = Phase.loading
        phase = try {
            suggestions = store.generateSettlementOptions(caseId).take(3)
            if (suggestions.isEmpty()) Phase.failed else Phase.ready
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (_: Exception) {
            Phase.failed
        }
    }

    /** PROPOSE SETTLEMENT / SEND COUNTER OFFER. Returns true on success. */
    suspend fun send(): Boolean {
        if (!canSend) return false
        val s = selectedSuggestion
        val body: String
        val source: SettlementSource
        val days: Int
        val suggestionId: String?
        if (s != null) {
            body = s.body; source = SettlementSource.ai; days = s.dueDays; suggestionId = s.id
        } else {
            body = trimmedCustom; source = SettlementSource.custom; days = customDays; suggestionId = null
        }
        isSending = true; error = null; composerError = null
        try {
            when (mode) {
                Mode.propose -> store.proposeSettlement(caseId, body, source, suggestionId = suggestionId, dueDays = days)
                Mode.counter -> store.counterSettlement(caseId, body, source, dueDays = days)
            }
            didSend = true
            return true
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: EdgeError) {
            if (e.isUnsafeTerms) {
                // Keep the terms for editing; the message sits on the composer (AI picks fall back to the page).
                if (choice == Choice.custom) composerError = EdgeErrors.unsafeTermsMessage else error = EdgeErrors.unsafeTermsMessage
            } else {
                error = EdgeErrors.settlementMessage(e)
            }
            return false
        } catch (e: Exception) {
            error = EdgeErrors.settlementMessage(e)
            return false
        } finally {
            isSending = false
        }
    }
}
