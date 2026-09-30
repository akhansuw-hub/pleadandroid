// Port of ArgueWin/Features/Scheduling/SchedulingView.swift.
//
// Agree the trial time. Whoever did NOT make the latest proposal may accept;
// the plaintiff may counter-propose exactly once.
package app.plead.android.features.scheduling

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.plead.android.app.AppModel
import app.plead.android.designsystem.AWButtonKind
import app.plead.android.designsystem.AWCard
import app.plead.android.designsystem.Countdown
import app.plead.android.designsystem.InlineError
import app.plead.android.designsystem.PleadColor
import app.plead.android.designsystem.PleadFont
import app.plead.android.designsystem.PleadRadius
import app.plead.android.designsystem.PleadSpacing
import app.plead.android.designsystem.PrimaryButton
import app.plead.android.designsystem.SectionLabel
import app.plead.android.designsystem.monospacedDigit
import app.plead.android.features.casedetail.CaseDates
import app.plead.android.features.casedetail.CaseNavBar
import app.plead.android.features.casedetail.CaseSheetHost
import app.plead.android.features.casedetail.ContentUnavailable
import app.plead.android.features.casedetail.NavTextButton
import app.plead.android.features.casedetail.TrialTimePicker
import app.plead.android.features.casedetail.symbolIcon
import app.plead.android.models.Case
import app.plead.android.models.CaseStatus
import app.plead.android.models.EdgeError
import app.plead.android.models.Role
import app.plead.android.services.CaseStore
import app.plead.android.services.TrialWindow
import app.plead.android.services.canAcceptTime
import app.plead.android.services.canCounterPropose
import app.plead.android.services.lastProposer
import java.util.UUID
import kotlinx.coroutines.launch

/** `AppSheet.scheduling(caseId)`: the trial-time sheet (`.presentationDetents([.medium, .large])`). */
@Composable
fun SchedulingSheet(model: AppModel, caseId: UUID, onDismiss: () -> Unit) {
    var countering by remember { mutableStateOf(false) }
    // `.presentationDetents(countering ? [.large] : [.medium, .large])`.
    CaseSheetHost(onDismissRequest = onDismiss, partial = true, expand = countering) {
        SchedulingView(caseId, model.store, countering, { countering = it }, onDismiss)
    }
}

/** Statics of the scheduling sheet. */
object SchedulingView {
    /** "You" when I proposed last, else the proposer's name (or "Your partner"). */
    fun proposerName(kase: Case, store: CaseStore): String {
        val proposer = kase.userId(kase.lastProposer)
        return if (proposer == store.me?.id) "You" else store.profile(proposer)?.displayName ?: "Your partner"
    }
}

@Composable
fun ColumnScope.SchedulingView(
    caseId: UUID,
    store: CaseStore,
    countering: Boolean,
    onCountering: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    var counterAt by remember { mutableStateOf(TrialWindow.defaultProposal()) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val partner = store.partner?.displayName ?: "your partner"

    fun accept(kase: Case) {
        working = true; error = null
        scope.launch {
            try {
                store.acceptTime(kase)
                view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
                onDismiss()
            } catch (e: Exception) {
                error = (e as? EdgeError)?.message ?: "Couldn't reach the court."
            }
            working = false
        }
    }

    fun counter(kase: Case) {
        if (!TrialWindow.isValid(counterAt)) { error = "Pick a time between 1 hour and 7 days from now."; return }
        working = true; error = null
        scope.launch {
            try {
                store.proposeTime(counterAt, kase)
                onCountering(false)
                onDismiss()
            } catch (e: Exception) {
                error = (e as? EdgeError)?.message ?: "Couldn't reach the court."
            }
            working = false
        }
    }

    CaseNavBar(title = "Trial time", leading = { NavTextButton("Close", enabled = !working, onClick = onDismiss) })
    val kase = store.caseById(caseId)
    if (kase == null) {
        ContentUnavailable("Case not found", "questionmark.folder")
        return
    }
    val role = store.myRole(kase)
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(PleadSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(PleadSpacing.xl),
    ) {
        AWCard(Modifier.semantics(mergeDescendants = true) {}, padding = PleadSpacing.xl) {
            Column(verticalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
                Text("${SchedulingView.proposerName(kase, store)} proposed", style = PleadFont.caption, color = PleadColor.subtleText)
                val at = kase.proposedTrialAt
                if (at != null) {
                    Text(CaseDates.weekdayDayMonth(at), style = PleadFont.title, color = PleadColor.cocoa)
                    Text(CaseDates.shortTime(at), style = PleadFont.hero.monospacedDigit(), color = PleadColor.cocoa)
                } else {
                    Text("No time proposed yet", style = PleadFont.title, color = PleadColor.cocoa)
                }
                Text(
                    "Court opens the moment a time is agreed. Arguments run turn by turn and must finish by this time — rest early and the verdict follows straight away.",
                    style = PleadFont.caption,
                    color = PleadColor.subtleText,
                )
            }
        }

        if (kase.status != CaseStatus.scheduling) {
            Text("The trial time is agreed. Court is in session.", style = PleadFont.headline, color = PleadColor.cocoa)
        } else if (kase.canAcceptTime(role)) {
            Column(verticalArrangement = Arrangement.spacedBy(PleadSpacing.m)) {
                PrimaryButton("Accept this time", systemImage = "checkmark", isLoading = working && !countering) { accept(kase) }
                if (role == Role.plaintiff && kase.canCounterPropose(role)) {
                    if (countering) {
                        Column(verticalArrangement = Arrangement.spacedBy(PleadSpacing.m)) {
                            SectionLabel("Your counter-proposal")
                            TrialTimePicker(
                                counterAt,
                                TrialWindow.range(),
                                onChange = { counterAt = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(PleadColor.paperWhite, RoundedCornerShape(PleadRadius.card))
                                    .padding(PleadSpacing.s),
                            )
                            Text("You can counter-propose once. After that, $partner decides.", style = PleadFont.caption, color = PleadColor.subtleText)
                            PrimaryButton("Send counter-proposal", systemImage = "paperplane.fill", isLoading = working && countering) { counter(kase) }
                        }
                    } else {
                        PrimaryButton("Counter-propose", kind = AWButtonKind.secondary) { onCountering(true) }
                    }
                }
                kase.deadlineAt?.let { d ->
                    Row(
                        Modifier.semantics(mergeDescendants = true) {},
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("If you don't respond in", style = PleadFont.caption, color = PleadColor.subtleText)
                        Countdown(target = d, font = PleadFont.caption, color = PleadColor.subtleText)
                        Text("this time stands.", style = PleadFont.caption, color = PleadColor.subtleText)
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
                Row(horizontalArrangement = Arrangement.spacedBy(PleadSpacing.s), verticalAlignment = Alignment.CenterVertically) {
                    Icon(symbolIcon("hourglass"), contentDescription = null, tint = PleadColor.cocoa, modifier = Modifier.size(18.dp))
                    Text("Waiting for $partner to respond", style = PleadFont.headline, color = PleadColor.cocoa)
                }
                if (role == Role.plaintiff && kase.proposalCount >= 1) {
                    Text("You've used your one counter-proposal. The court allows one per case.", style = PleadFont.body, color = PleadColor.subtleText)
                } else if (role == Role.defendant) {
                    Text("They can accept your time or counter-propose once.", style = PleadFont.body, color = PleadColor.subtleText)
                }
            }
        }
        InlineError(error)
    }
}
