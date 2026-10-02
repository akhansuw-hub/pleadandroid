// Port of ArgueWin/Features/Settlement/SettlementResponseSheet.swift (SettlementResponseModel + SettlementResponseSheet).
package app.plead.android.features.settlement

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.plead.android.app.AppModel
import app.plead.android.app.AppSheet
import app.plead.android.designsystem.AWButtonKind
import app.plead.android.designsystem.InlineError
import app.plead.android.designsystem.LegalLabel
import app.plead.android.designsystem.PleadColor
import app.plead.android.designsystem.PleadMotion
import app.plead.android.designsystem.PleadSpacing
import app.plead.android.designsystem.PleadType
import app.plead.android.designsystem.PrimaryButton
import app.plead.android.designsystem.TextStyleKind
import app.plead.android.designsystem.awBottomBar
import app.plead.android.models.Case
import app.plead.android.models.Settlement
import app.plead.android.models.SettlementOffer
import app.plead.android.models.SettlementStatus
import app.plead.android.services.CaseStore
import app.plead.android.services.EdgeErrors
import app.plead.android.services.SettlementRules
import app.plead.android.services.docketTitle
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * The response sheet's state. Receiver: ACCEPT / COUNTER (below round 3) / REJECT. Proposer:
 * "Offer sent · waiting" with Withdraw. Anything else: how the attempt ended.
 */
@Stable
class SettlementResponseModel(val caseId: UUID, private val store: CaseStore) {
    enum class Role { receiver, proposer, resolved }
    enum class Working { accept, reject, withdraw }

    var working: Working? by mutableStateOf(null)
        private set
    var error: String? by mutableStateOf(null)

    val kase: Case? get() = store.caseById(caseId)
    val settlement: Settlement? get() = store.settlement(caseId)
    val offer: SettlementOffer? get() = store.latestOffer(caseId)

    val role: Role
        get() {
            val s = settlement
            if (s == null || !s.isPending || kase?.status?.isOpen != true) return Role.resolved
            return if (store.pendingSettlementForMe(caseId)) Role.receiver else Role.proposer
        }

    /** The round on the table (1…3). */
    val round: Int get() = offer?.roundNumber ?: settlement?.currentRound ?: 1
    val roundLine: String get() = SettlementRules.roundLine(round)

    /** COUNTER OFFER is hidden on round 3: after the final round only Accept or Reject. */
    val canCounter: Boolean get() = role == Role.receiver && (settlement?.let(SettlementRules::canCounter) ?: false)
    val isFinalRound: Boolean get() = round >= Settlement.maxRounds

    val proposerName: String
        get() {
            val by = offer?.proposedBy ?: return "Your partner"
            return if (by == store.me?.id) "You" else store.name(by, fallback = "Your partner")
        }
    val otherName: String
        get() {
            val kase = kase ?: return "your partner"
            return store.opponentId(kase)?.let { store.name(it, fallback = "your partner") } ?: "your partner"
        }

    val headline: String
        get() = when (role) {
            Role.receiver -> if (isFinalRound) "Final settlement offer" else if (round > 1) "Counter-offer received" else "A settlement offer"
            Role.proposer -> "Offer sent · waiting for $otherName"
            Role.resolved -> resolvedHeadline
        }

    val subline: String
        get() = when (role) {
            Role.receiver -> if (isFinalRound) SettlementCopy.finalRound else "If you both accept, the case closes as settled. No winner, no loser."
            Role.proposer -> SettlementCopy.courtWaits
            Role.resolved -> resolvedSubline
        }

    private val resolvedHeadline: String
        get() = when (settlement?.status) {
            SettlementStatus.accepted, SettlementStatus.fulfilled -> "Settled out of court"
            SettlementStatus.rejected -> "Settlement failed"
            SettlementStatus.withdrawn -> "Offer withdrawn"
            SettlementStatus.expired -> "The offer expired"
            else -> "No offer on the table"
        }

    private val resolvedSubline: String
        get() = when (settlement?.status) {
            SettlementStatus.accepted, SettlementStatus.fulfilled -> SettlementCopy.noWinner
            SettlementStatus.rejected -> "Court is back in session. The case picks up exactly where it paused."
            SettlementStatus.withdrawn, SettlementStatus.expired -> "No harm done. The case picks up exactly where it paused."
            else -> "Nothing is waiting for you here."
        }

    suspend fun accept(): Boolean = run(Working.accept) { store.respondToSettlement(caseId, accept = true) }
    suspend fun reject(): Boolean = run(Working.reject) { store.respondToSettlement(caseId, accept = false) }
    suspend fun withdraw(): Boolean = run(Working.withdraw) { store.withdrawSettlement(caseId) }

    private suspend fun run(kind: Working, work: suspend () -> Unit): Boolean {
        if (working != null) return false
        working = kind; error = null
        try {
            work()
            return true
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = EdgeErrors.settlementMessage(e)
            return false
        } finally {
            working = null
        }
    }
}

/** The receiver's response sheet (brief §6), and the proposer's "Offer sent · waiting" view. */
@Composable
fun SettlementResponseSheet(caseId: UUID, model: AppModel, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val store = model.store
    val router = model.router
    val response = remember(caseId, store) { SettlementResponseModel(caseId, store) }
    var confirmReject by remember { mutableStateOf(false) }
    var confirmWithdraw by remember { mutableStateOf(false) }
    var outcome by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    SheetScaffold(
        cancelTitle = if (response.role == SettlementResponseModel.Role.receiver) "Not now" else "Done",
        onCancel = onDismiss,
        cancelEnabled = response.working == null,
        dismissDisabled = response.working != null,
        modifier = modifier,
        bottomBar = {
            Column(Modifier.awBottomBar(), verticalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
                ResponseActions(
                    response, store,
                    onAccept = { scope.launch { if (response.accept()) outcome += 1 } },
                    onCounter = { router.sheet = AppSheet.settlementRoom(caseId) },
                    onReject = { confirmReject = true },
                    onWithdraw = { confirmWithdraw = true },
                    onDone = onDismiss,
                    onSeeAgreement = { router.sheet = AppSheet.settlementAccepted(caseId) },
                )
            }
        },
    ) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PleadSpacing.l)
                .padding(top = PleadSpacing.xs, bottom = PleadSpacing.xl)
                .animateContentSize(tween(200, easing = PleadMotion.easeOut)),
            verticalArrangement = Arrangement.spacedBy(PleadSpacing.xl),
        ) {
            ResponseHeader(response)
            val offer = response.offer
            val resolved = response.role == SettlementResponseModel.Role.resolved
            if (offer != null && (!resolved || response.settlement?.status?.isAgreed == true)) {
                SettlementOfferCard(
                    offer,
                    heading = if (response.role == SettlementResponseModel.Role.proposer) "Your offer" else "${response.proposerName}'s offer",
                    round = if (resolved) null else offer.roundNumber,
                    expiresAt = if (resolved) null else (offer.expiresAt ?: response.settlement?.expiresAt),
                )
            }
            if (!resolved) {
                val proposer = response.role == SettlementResponseModel.Role.proposer
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                    Icon(
                        if (proposer) Icons.Outlined.People else Icons.Outlined.PauseCircle,   // person.2 / pause.circle
                        contentDescription = null,
                        tint = PleadColor.subtleText,
                        modifier = Modifier.size(14.dp).padding(top = 1.dp),
                    )
                    Text(if (proposer) SettlementCopy.helper else SettlementCopy.courtWaits, style = PleadType.metadata, color = PleadColor.subtleText)
                }
            }
            InlineError(response.error)
        }
    }
    SuccessFeedback(outcome)
    ConfirmationDialog(
        visible = confirmReject,
        title = "See you in court?",
        message = SettlementCopy.noHardFeelings,
        actions = listOf(
            DialogAction("Reject and return to court") { scope.launch { if (response.reject()) { outcome += 1; onDismiss() } } },
            DialogAction("Keep talking", cancel = true),
        ),
        onDismiss = { confirmReject = false },
    )
    ConfirmationDialog(
        visible = confirmWithdraw,
        title = "Withdraw your offer?",
        message = "The case picks up exactly where it paused. You can propose again later.",
        actions = listOf(
            DialogAction("Withdraw offer", destructive = true) { scope.launch { if (response.withdraw()) onDismiss() } },
            DialogAction("Keep it on the table", cancel = true),
        ),
        onDismiss = { confirmWithdraw = false },
    )
}

@Composable
private fun ResponseHeader(response: SettlementResponseModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(PleadSpacing.m), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
            LegalLabel(if (response.role == SettlementResponseModel.Role.resolved) "Settlement" else "Settlement offer", color = PleadColor.walnut, size = 12f)
            response.kase?.let { kase ->
                Text(kase.docketTitle, style = PleadType.metadataMedium, color = PleadColor.walnut, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(response.headline, style = PleadType.displayL, color = PleadColor.cocoa, modifier = Modifier.semantics { heading() })
            Text(response.subline, style = PleadType.body, color = PleadColor.subtleText)
        }
        SettlementSeal(size = 58.dp, modifier = Modifier.padding(top = PleadSpacing.xs))
    }
}

@Composable
private fun ResponseActions(
    response: SettlementResponseModel,
    store: CaseStore,
    onAccept: () -> Unit,
    onCounter: () -> Unit,
    onReject: () -> Unit,
    onWithdraw: () -> Unit,
    onDone: () -> Unit,
    onSeeAgreement: () -> Unit,
) {
    val working = response.working
    when (response.role) {
        SettlementResponseModel.Role.receiver -> {
            ActionButton(
                "ACCEPT SETTLEMENT",
                icon = Icons.Filled.Verified,   // checkmark.seal.fill
                isLoading = working == SettlementResponseModel.Working.accept,
                enabled = working == null || working == SettlementResponseModel.Working.accept,
                modifier = Modifier.testTag("settlement.accept"),
                onClick = onAccept,
            )
            if (response.canCounter) {
                ActionButton("COUNTER OFFER", kind = AWButtonKind.secondary, enabled = working == null, onClick = onCounter)
            }
            QuietTextButton(
                "REJECT — SEE YOU IN COURT",
                style = PleadType.ui(14f, FontWeight.SemiBold, TextStyleKind.subheadline).copy(letterSpacing = 0.4.sp),
                enabled = working == null,
                isLoading = working == SettlementResponseModel.Working.reject,
                modifier = Modifier.semantics { stateDescription = SettlementCopy.noHardFeelings },
                onClick = onReject,
            )
        }
        SettlementResponseModel.Role.proposer -> {
            ActionButton(
                "Withdraw offer",
                kind = AWButtonKind.secondary,
                isLoading = working == SettlementResponseModel.Working.withdraw,
                enabled = working == null || working == SettlementResponseModel.Working.withdraw,
                onClick = onWithdraw,
            )
            ActionButton("Done", kind = AWButtonKind.plain, onClick = onDone)
        }
        SettlementResponseModel.Role.resolved -> {
            val s = response.settlement
            if (s != null && s.status == SettlementStatus.accepted && !store.celebratedSettlementIds.contains(s.id)) {
                PrimaryButton("See the agreement") { onSeeAgreement() }
            } else {
                PrimaryButton("Close") { onDone() }
            }
        }
    }
}
