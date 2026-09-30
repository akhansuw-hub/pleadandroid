// Port of ArgueWin/Features/FileCase/FileCaseView.swift.
//
// File a case: 4-step modal. Charge → Evidence → Remedy → Review & serve.
// Serving uploads image exhibits to `{couple}/pending/…` then calls `file_case`
// with the exhibits inline (CaseStore.fileCase → StorageService).
package app.plead.android.features.filecase

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.plead.android.app.AppModel
import app.plead.android.app.DemoHarness
import app.plead.android.app.sampleDrafts
import app.plead.android.designsystem.InlineError
import app.plead.android.designsystem.PleadColor
import app.plead.android.designsystem.PleadSpacing
import app.plead.android.designsystem.PleadType
import app.plead.android.designsystem.awBottomBar
import app.plead.android.designsystem.awCourtFile
import app.plead.android.features.casedetail.AWTextField
import app.plead.android.features.casedetail.CaseDialog
import app.plead.android.features.casedetail.CaseNavBar
import app.plead.android.features.casedetail.CaseSheetHost
import app.plead.android.features.casedetail.CaseType
import app.plead.android.features.casedetail.DialogAction
import app.plead.android.features.casedetail.FormPrimaryButton
import app.plead.android.features.casedetail.InteractiveDismissDisabled
import app.plead.android.features.casedetail.NavTextButton
import app.plead.android.features.casedetail.RecordLabel
import app.plead.android.models.EdgeError
import app.plead.android.models.Role
import app.plead.android.services.CaseStore
import app.plead.android.services.DraftExhibit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

/** `AppSheet.fileCase`: the File a case modal in its sheet. */
@Composable
fun FileCaseSheet(model: AppModel, onDismiss: () -> Unit) {
    CaseSheetHost(onDismissRequest = onDismiss) { FileCaseView(model.store, onDismiss) }
}

/** Statics of the filing form (Swift `FileCaseView.Step`). */
object FileCaseView {
    enum class Step {
        charge, exhibits, remedy, review;

        val title: String
            get() = when (this) {
                charge -> "The charge"
                exhibits -> "Evidence"
                remedy -> "The remedy"
                review -> "Review"
            }
    }

    /** Whether the bottom button may advance from [step]. */
    fun canContinue(step: Step, title: String, charge: String, remedy: String, serving: Boolean): Boolean = when (step) {
        Step.charge -> title.trim().isNotEmpty() && charge.trim().isNotEmpty()
        Step.exhibits -> true
        Step.remedy -> remedy.trim().isNotEmpty()
        Step.review -> !serving
    }
}

@Composable
fun ColumnScope.FileCaseView(store: CaseStore, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val evidenceDemo = remember { DemoHarness.fileCaseEvidenceStep }
    var step by remember { mutableStateOf(if (evidenceDemo) FileCaseView.Step.exhibits else FileCaseView.Step.charge) }
    var title by remember { mutableStateOf("") }
    var charge by remember { mutableStateOf("") }
    var remedy by remember { mutableStateOf("") }
    var drafts by remember { mutableStateOf(if (evidenceDemo) DemoHarness.sampleDrafts else emptyList<DraftExhibit>()) }
    var serving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }

    val partnerName = store.partner?.displayName ?: "your partner"
    val isDirty = title.isNotEmpty() || charge.isNotEmpty() || remedy.isNotEmpty() || drafts.isNotEmpty()
    val canContinue = FileCaseView.canContinue(step, title, charge, remedy, serving)
    InteractiveDismissDisabled(isDirty || serving)

    fun serve() {
        val blocker = store.filingBlocker
        if (blocker != null) { error = blocker; return }
        serving = true
        scope.launch {
            try {
                store.fileCase(title.trim(), charge.trim(), remedy.trim(), drafts)
                // `.sensoryFeedback(.success, trigger: served)`.
                view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
                onDismiss()
            } catch (e: EdgeError) {
                // premium_required also raises the paywall gate (AppModel), which replaces the tabs.
                error = e.message
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Couldn't serve the summons. Check your connection and try again."
            }
            serving = false
        }
    }

    fun advance() {
        error = null
        val next = FileCaseView.Step.entries.getOrNull(step.ordinal + 1)
        if (next != null) { step = next; return }
        serve()
    }

    CaseNavBar(
        title = "Summon $partnerName",
        leading = {
            if (step == FileCaseView.Step.charge) {
                NavTextButton("Cancel", enabled = !serving) { if (isDirty) confirmDiscard = true else onDismiss() }
            } else {
                NavTextButton("Back", icon = "chevron.left", enabled = !serving) {
                    step = FileCaseView.Step.entries.getOrNull(step.ordinal - 1) ?: FileCaseView.Step.charge
                }
            }
        },
    )
    Column(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(PleadSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(PleadSpacing.xl),
    ) {
        StepHeader(step.ordinal, FileCaseView.Step.entries.size, step.title)
        when (step) {
            FileCaseView.Step.charge -> Column(verticalArrangement = Arrangement.spacedBy(PleadSpacing.l)) {
                FormField("Case title", "Give it a name the court will remember.") {
                    AWTextField(title, { title = it }, "The Thermostat Incident")
                }
                FormField("The charge", "What did $partnerName do? Stick to the facts. The judge will add the drama.") {
                    AWTextField(charge, { charge = it }, "The defendant set the thermostat to 17°C…", minLines = 4, maxLines = 10)
                }
            }
            FileCaseView.Step.exhibits -> ExhibitEditor(drafts, { drafts = it }, ownerRole = Role.plaintiff)
            FileCaseView.Step.remedy ->
                FormField("Requested remedy", "What should happen if you win? Keep it doable within a week, and never about money.") {
                    AWTextField(remedy, { remedy = it }, "Defendant does the washing up for a week", minLines = 2, maxLines = 6)
                }
            FileCaseView.Step.review -> Column(verticalArrangement = Arrangement.spacedBy(PleadSpacing.l)) {
                Column(Modifier.awCourtFile(PleadSpacing.xl), verticalArrangement = Arrangement.spacedBy(PleadSpacing.m)) {
                    RecordLabel("Court filing", color = PleadColor.walnut)
                    // Amendment u: the case title preview is the one display moment on this form.
                    Text(title.trim(), style = PleadType.caseTitle, color = PleadColor.cocoa)
                    Text("${store.me?.displayName ?: "Plaintiff"} v. $partnerName", style = PleadType.caseParties, color = PleadColor.subtleText)
                    ReviewRow("Charge", charge.trim()) { step = FileCaseView.Step.charge }
                    ReviewRow("Remedy", remedy.trim()) { step = FileCaseView.Step.remedy }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${drafts.size} ${if (drafts.size == 1) "exhibit" else "exhibits"}", style = PleadType.body, color = PleadColor.cocoa)
                        Spacer(Modifier.weight(1f))
                        EditLink { step = FileCaseView.Step.exhibits }
                    }
                }
                Text("$partnerName gets a summons and 72 hours to enter a plea.", style = PleadType.metadata, color = PleadColor.subtleText)
            }
        }
        InlineError(error)
    }
    Box(Modifier.imePadding().awBottomBar()) {
        FormPrimaryButton(
            if (step == FileCaseView.Step.review) "Serve summons" else "Continue",
            enabled = canContinue,
            systemImage = if (step == FileCaseView.Step.review) "paperplane.fill" else "arrow.right",
            isLoading = serving,
            action = ::advance,
        )
    }

    if (confirmDiscard) {
        CaseDialog(
            "Discard this case?",
            null,
            listOf(DialogAction("Discard", destructive = true) { onDismiss() }, DialogAction("Keep editing", cancel = true)),
            onDismiss = { confirmDiscard = false },
        )
    }
}

@Composable
private fun ReviewRow(label: String, value: String, edit: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(PleadSpacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RecordLabel(label, modifier = Modifier.weight(1f))
            EditLink(edit)
        }
        Text(value, style = PleadType.body, color = PleadColor.cocoa)
    }
}

/** The review's "Edit" (a plain accent-tinted button). */
@Composable
private fun EditLink(onClick: () -> Unit) {
    Text("Edit", style = PleadType.uiButtonSecondary, color = PleadColor.burgundy, modifier = Modifier.clickable(onClick = onClick).padding(PleadSpacing.xs))
}

@Composable
private fun FormField(label: String, hint: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
        RecordLabel(label)
        content()
        Text(hint, style = PleadType.metadata, color = PleadColor.subtleText)
    }
}

/** "Step 2 of 4" + title + progress bar. */
@Composable
fun StepHeader(index: Int, count: Int, title: String, modifier: Modifier = Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
        Text("Step ${index + 1} of $count", style = PleadType.metadataMedium, color = PleadColor.subtleText)
        Text(title, style = CaseType.stepTitle, color = PleadColor.cocoa, modifier = Modifier.semantics { heading() })
        Row(Modifier.clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(PleadSpacing.xs)) {
            repeat(count) { i ->
                Box(Modifier.weight(1f).height(4.dp).background(if (i <= index) PleadColor.burgundy else PleadColor.separator, CircleShape))
            }
        }
    }
}
