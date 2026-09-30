// Port of ArgueWin/Features/Judgement/JudgementSelectionModel.swift.
package app.plead.android.features.judgement

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.plead.android.models.Judgement
import app.plead.android.models.JudgementOption
import app.plead.android.models.JudgementStatus
import app.plead.android.services.CaseStore
import app.plead.android.services.EdgeErrors
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * State machine behind screen B. Selection stays local until DELIVER JUDGEMENT; the option set and
 * the reroll count come from `CaseStore` (the server is authoritative).
 *
 * preparing (round 0 still generating: refetch every 3 s, up to 60 s) → ready ⇄ rerolling → delivering
 * → delivered. A timeout lands in `unavailable` with a retry.
 */
@Stable
class JudgementSelectionModel(
    val caseId: UUID,
    private val store: CaseStore,
    private val pollInterval: Duration = 3.seconds,
    private val pollTimeout: Duration = 60.seconds,
) {
    enum class Phase { preparing, ready, unavailable }

    var selectedId: String? by mutableStateOf(null)
    var phase: Phase by mutableStateOf(Phase.preparing)
        private set
    var isRerolling: Boolean by mutableStateOf(false)
        private set
    var isDelivering: Boolean by mutableStateOf(false)
        private set
    var didDeliver: Boolean by mutableStateOf(false)
        private set
    var error: String? by mutableStateOf(null)
        private set

    init {
        if (store.judgementOptions(caseId)?.options?.isNotEmpty() == true) phase = Phase.ready
    }

    // MARK: Derived

    val judgement: Judgement? get() = store.judgement(caseId)
    val options: List<JudgementOption> get() = store.judgementOptions(caseId)?.options ?: emptyList()
    val selectedOption: JudgementOption? get() = options.firstOrNull { it.id == selectedId }
    val rerollsLeft: Int get() = judgement?.rerollsLeft ?: 0
    val isTie: Boolean get() = store.verdict(caseId)?.isTie == true
    val isChooser: Boolean get() = store.isChooser(caseId)

    /** The judgement moved past selection (delivered here, or elsewhere). */
    val isClosedForSelection: Boolean get() = judgement?.let { it.status != JudgementStatus.pendingSelection } ?: false

    val canDeliver: Boolean
        get() = selectedOption != null && !isDelivering && !isRerolling && isChooser && judgement?.status == JudgementStatus.pendingSelection
    val canReroll: Boolean
        get() = phase == Phase.ready && rerollsLeft > 0 && !isRerolling && !isDelivering && judgement?.status == JudgementStatus.pendingSelection

    val title: String get() = if (isTie) "CHOOSE A RESOLUTION" else "CHOOSE THE COURT'S JUDGEMENT"
    val subtitle: String
        get() = if (isTie) {
            "No winner this time. The court has prepared compromise resolutions for you both."
        } else {
            "The court has prepared outcomes for this case."
        }
    val deliverTitle: String get() = if (isTie) "DELIVER RESOLUTION" else "DELIVER JUDGEMENT"
    val rerollTitle: String get() = "SUGGEST ANOTHER ($rerollsLeft left)"

    // MARK: Intents

    fun select(id: String) {
        if (isDelivering || options.none { it.id == id }) return
        selectedId = id
        error = null
    }

    /** Round 0 is generated in the background after the reveal: refetch until it lands or time runs out. */
    suspend fun start() {
        if (options.isNotEmpty()) { phase = Phase.ready; return }
        phase = Phase.preparing
        error = null
        val deadline = TimeSource.Monotonic.markNow() + pollTimeout
        while (coroutineContext.isActive) {
            val set = store.loadJudgementOptions(caseId)
            if (set != null && set.options.isNotEmpty()) { phase = Phase.ready; return }
            if (deadline.hasPassedNow()) { phase = Phase.unavailable; return }
            delay(pollInterval)
        }
    }

    /** SUGGEST ANOTHER: a fresh set replaces the list and clears the local selection. */
    suspend fun reroll() {
        if (!canReroll) return
        isRerolling = true
        error = null
        try {
            store.rerollJudgement(caseId)
            selectedId = null
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = EdgeErrors.judgementMessage(e)
        }
        isRerolling = false
    }

    /** DELIVER JUDGEMENT. Returns true once the court has it; the selection can no longer change. */
    suspend fun deliver(): Boolean {
        val id = selectedId
        if (!canDeliver || id == null) return false
        isDelivering = true
        error = null
        try {
            store.selectJudgement(caseId, id)
            didDeliver = true
            return true
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = EdgeErrors.judgementMessage(e)
            return false
        } finally {
            isDelivering = false
        }
    }
}
