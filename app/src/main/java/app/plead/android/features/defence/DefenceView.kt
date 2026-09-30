// Port of ArgueWin/Features/Defence/DefenceView.swift.
//
// Defence filing: statement, exhibits, optional counter-claim, proposed trial time
// (now+1h … now+7d) → `file_defence`.
package app.plead.android.features.defence

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import app.plead.android.app.AppModel
import app.plead.android.designsystem.AWCard
import app.plead.android.designsystem.Countdown
import app.plead.android.designsystem.InlineError
import app.plead.android.designsystem.PleadColor
import app.plead.android.designsystem.PleadRadius
import app.plead.android.designsystem.PleadSpacing
import app.plead.android.designsystem.PleadType
import app.plead.android.designsystem.awBottomBar
import app.plead.android.designsystem.monospacedDigit
import app.plead.android.features.casedetail.AWTextField
import app.plead.android.features.casedetail.CaseDialog
import app.plead.android.features.casedetail.CaseNavBar
import app.plead.android.features.casedetail.CaseSheetHost
import app.plead.android.features.casedetail.CaseType
import app.plead.android.features.casedetail.ContentUnavailable
import app.plead.android.features.casedetail.DialogAction
import app.plead.android.features.casedetail.FormPrimaryButton
import app.plead.android.features.casedetail.InteractiveDismissDisabled
import app.plead.android.features.casedetail.NavTextButton
import app.plead.android.features.casedetail.RecordLabel
import app.plead.android.features.casedetail.TrialTimePicker
import app.plead.android.features.filecase.ExhibitEditor
import app.plead.android.features.filecase.ToggleRow
import app.plead.android.models.Case
import app.plead.android.models.EdgeError
import app.plead.android.models.Role
import app.plead.android.services.CaseStore
import app.plead.android.services.DraftExhibit
import app.plead.android.services.TrialWindow
import app.plead.android.services.docketNumber
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

/** `AppSheet.defence(caseId)`: the defence form in its sheet. */
@Composable
fun DefenceSheet(model: AppModel, caseId: UUID, onDismiss: () -> Unit) {
    CaseSheetHost(onDismissRequest = onDismiss) { DefenceView(caseId, model.store, onDismiss) }
}

/** Statics of the defence form. */
object DefenceView {
    /** A statement, and the counter-claim's text when one is being added. */
    fun canFile(statement: String, hasCounterClaim: Boolean, counterClaim: String): Boolean =
        statement.trim().isNotEmpty() && (!hasCounterClaim || counterClaim.trim().isNotEmpty())

    const val invalidTimeMessage = "Pick a time between 1 hour and 7 days from now."
}

@Composable
fun ColumnScope.DefenceView(caseId: UUID, store: CaseStore, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    var statement by remember { mutableStateOf("") }
    var drafts by remember { mutableStateOf(emptyList<DraftExhibit>()) }
    var hasCounterClaim by remember { mutableStateOf(false) }
    var counterClaim by remember { mutableStateOf("") }
    var trialAt by remember { mutableStateOf(TrialWindow.defaultProposal()) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }

    val isDirty = statement.isNotEmpty() || drafts.isNotEmpty() || counterClaim.isNotEmpty()
    val canFile = DefenceView.canFile(statement, hasCounterClaim, counterClaim)
    InteractiveDismissDisabled(isDirty || working)

    fun file(kase: Case) {
        if (!TrialWindow.isValid(trialAt)) { error = DefenceView.invalidTimeMessage; return }
        working = true; error = null
        val claim = if (hasCounterClaim) counterClaim.trim() else null
        scope.launch {
            try {
                store.fileDefence(kase, statement.trim(), claim, drafts, trialAt)
                view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
                onDismiss()
            } catch (e: EdgeError) {
                error = e.message
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Couldn't file your defence. Try again."
            }
            working = false
        }
    }

    CaseNavBar(
        title = "Your defence",
        leading = { NavTextButton("Cancel", enabled = !working) { if (isDirty) confirmDiscard = true else onDismiss() } },
    )
    val kase = store.caseById(caseId)
    if (kase == null) {
        ContentUnavailable("Case not found", "questionmark.folder", modifier = Modifier.weight(1f))
    } else {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(PleadSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(PleadSpacing.xl),
        ) {
            AWCard {
                Column(verticalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(kase.docketNumber, style = PleadType.metadataMedium, color = PleadColor.subtleText)
                        Spacer(Modifier.weight(1f))
                        kase.deadlineAt?.let { d ->
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Due in", style = PleadType.metadataMedium, color = PleadColor.subtleText)
                                Countdown(target = d, font = PleadType.metadataMedium.monospacedDigit(), color = PleadColor.subtleText)
                            }
                        }
                    }
                    // Amendment u: the case title is the one display moment; the form stays SF.
                    Text(kase.title, style = CaseType.rowTitle, color = PleadColor.cocoa)
                    Text(kase.charge, style = PleadType.body, color = PleadColor.subtleText)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
                RecordLabel("Your statement")
                AWTextField(statement, { statement = it }, "Your side of the story", minLines = 5, maxLines = 12)
            }

            Column(verticalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
                RecordLabel("Your evidence")
                ExhibitEditor(drafts, { drafts = it }, ownerRole = Role.defendant)
            }

            Column(verticalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
                ToggleRow("Add a counter-claim", "The judge rules on it in the same verdict.", hasCounterClaim) { hasCounterClaim = it }
                if (hasCounterClaim) {
                    AWTextField(counterClaim, { counterClaim = it }, "The plaintiff also…", minLines = 3, maxLines = 8)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
                RecordLabel("Propose a trial time")
                TrialTimePicker(
                    trialAt,
                    TrialWindow.range(),
                    onChange = { trialAt = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(PleadColor.paperWhite, RoundedCornerShape(PleadRadius.card))
                        .padding(PleadSpacing.s),
                )
                Text(
                    "Between 1 hour and 7 days from now. Court opens as soon as the time is agreed — this is the latest it can adjourn.",
                    style = PleadType.metadata,
                    color = PleadColor.subtleText,
                )
            }

            InlineError(error)
        }
        Box(Modifier.imePadding().awBottomBar()) {
            FormPrimaryButton("File defence", enabled = canFile, systemImage = "paperplane.fill", isLoading = working) { file(kase) }
        }
    }

    if (confirmDiscard) {
        CaseDialog(
            "Discard your defence?",
            null,
            listOf(DialogAction("Discard", destructive = true) { onDismiss() }, DialogAction("Keep editing", cancel = true)),
            onDismiss = { confirmDiscard = false },
        )
    }
}
