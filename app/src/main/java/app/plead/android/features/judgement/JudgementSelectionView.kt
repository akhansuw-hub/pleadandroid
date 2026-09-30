// Port of ArgueWin/Features/Judgement/JudgementSelectionView.swift.
package app.plead.android.features.judgement

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.plead.android.app.AppModel
import app.plead.android.app.AppTab
import app.plead.android.app.DemoHarness
import app.plead.android.BuildConfig
import app.plead.android.designsystem.AWButtonKind
import app.plead.android.designsystem.Chip
import app.plead.android.designsystem.InlineError
import app.plead.android.designsystem.PleadColor
import app.plead.android.designsystem.PleadMotion
import app.plead.android.designsystem.PleadRadius
import app.plead.android.designsystem.PleadSpacing
import app.plead.android.designsystem.PleadType
import app.plead.android.designsystem.ScalesMark
import app.plead.android.designsystem.awBottomBar
import app.plead.android.designsystem.awCourtFile
import app.plead.android.features.settlement.ActionButton
import app.plead.android.features.settlement.IconLabel
import app.plead.android.features.settlement.SelectionFeedback
import app.plead.android.features.settlement.SheetScaffold
import app.plead.android.features.settlement.SuccessFeedback
import app.plead.android.features.settlement.pressScaleClickable
import app.plead.android.models.Case
import app.plead.android.models.JudgementOption
import app.plead.android.services.CaseStore
import app.plead.android.services.docketTitle
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * Screen B: CHOOSE THE COURT'S JUDGEMENT. Presented as a sheet (Home, Case detail, the verdict
 * sequence's CHOOSE JUDGEMENT). Four radio cards, SUGGEST ANOTHER (2 rerolls), DELIVER JUDGEMENT.
 * [onDismiss] closes the sheet (`router.sheet = null`).
 */
@Composable
fun JudgementSelectionView(caseId: UUID, model: AppModel, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val store = model.store
    val router = model.router
    val selection = remember(caseId, store) {
        JudgementSelectionModel(caseId, store).also { made ->
            // Demo harness: `AWJudgementSelect <option id>` pre-selects a card for screenshots.
            if (BuildConfig.DEBUG) DemoHarness.judgementSelect?.let(made::select)
        }
    }
    val kase = store.caseById(caseId)
    val scope = rememberCoroutineScope()

    /** The judge reads the judgement in the courtroom: go there while the case is still in court. */
    fun delivered() {
        onDismiss()
        val k = store.caseById(caseId)
        if (k != null && k.status.isInCourtroom) {
            router.courtCaseId = k.id
            router.tab = AppTab.court
        }
    }

    val showsBar = selection.isChooser && (!selection.isClosedForSelection || selection.didDeliver)
    SheetScaffold(
        cancelTitle = "Not now",
        onCancel = onDismiss,
        cancelEnabled = !selection.isDelivering,
        dismissDisabled = selection.isDelivering,
        modifier = modifier,
        bottomBar = if (showsBar) {
            {
                Column(Modifier.awBottomBar(), verticalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
                    JudgementRerollButton(selection.rerollTitle, isLoading = selection.isRerolling, isEnabled = selection.canReroll) {
                        scope.launch { selection.reroll() }
                    }
                    ActionButton(
                        selection.deliverTitle,
                        icon = Icons.Filled.Verified,   // checkmark.seal.fill
                        isLoading = selection.isDelivering,
                        enabled = selection.canDeliver || selection.isDelivering,
                        modifier = Modifier.semantics { if (selection.selectedOption == null) stateDescription = "Choose an option first" },
                    ) {
                        scope.launch { if (selection.deliver()) delivered() }
                    }
                }
            }
        } else {
            null
        },
    ) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PleadSpacing.l)
                .padding(top = PleadSpacing.s, bottom = PleadSpacing.xl)
                .animateContentSize(tween(250, easing = PleadMotion.easeInOut)),
            verticalArrangement = Arrangement.spacedBy(PleadSpacing.xl),
        ) {
            SelectionHeader(selection, kase)
            val judgement = selection.judgement
            when {
                judgement?.isCourtChosen == true ->
                    // Tie (amendment l): there is nothing to choose; the court picks the resolution itself.
                    Notice("The court could not separate you, so it chooses the resolution itself. You'll both be notified once it is delivered.")
                !selection.isChooser && judgement != null ->
                    Notice("The prevailing party chooses the court's judgement. You'll be notified once it is delivered.")
                selection.isClosedForSelection && !selection.didDeliver ->
                    Notice("The court has already delivered this judgement.")
                else -> when (selection.phase) {
                    JudgementSelectionModel.Phase.ready -> OptionList(selection, kase, store)
                    JudgementSelectionModel.Phase.preparing -> Preparing()
                    JudgementSelectionModel.Phase.unavailable -> Unavailable { scope.launch { selection.start() } }
                }
            }
            InlineError(selection.error)
        }
    }
    LaunchedEffect(selection) { selection.start() }
    SelectionFeedback(selection.selectedId)
    SuccessFeedback(selection.didDeliver, fire = selection.didDeliver)
}

/** `CourtroomLogic.sentenceCase` (Courtroom, wave 3a): "CHOOSE A RESOLUTION" → "Choose a resolution". */
private fun sentenceCase(s: String): String {
    val lower = s.lowercase()
    return lower.take(1).uppercase() + lower.drop(1)
}

@Composable
private fun SelectionHeader(selection: JudgementSelectionModel, kase: Case?) {
    Column(verticalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
        Row(horizontalArrangement = Arrangement.spacedBy(PleadSpacing.s), verticalAlignment = Alignment.CenterVertically) {
            ScalesMark(size = 22.dp)
            if (kase != null) {
                Text(kase.docketTitle, style = PleadType.metadataMedium, color = PleadColor.walnut, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // Sentence case in Fraunces: long phrases are never set in caps (typography brief §7).
        Text(sentenceCase(selection.title), style = PleadType.displayL, color = PleadColor.cocoa, modifier = Modifier.semantics { heading() })
        Text(selection.subtitle, style = PleadType.body, color = PleadColor.subtleText)
    }
}

@Composable
private fun OptionList(selection: JudgementSelectionModel, kase: Case?, store: CaseStore) {
    Column(Modifier.alpha(if (selection.isRerolling) 0.5f else 1f), verticalArrangement = Arrangement.spacedBy(PleadSpacing.m)) {
        selection.options.forEach { option ->
            JudgementOptionCard(
                option,
                isSelected = selection.selectedId == option.id,
                enabled = !(selection.isDelivering || selection.isRerolling),
            ) { selection.select(option.id) }
        }
        Text(footnote(selection, kase, store), style = PleadType.metadata, color = PleadColor.subtleText, modifier = Modifier.padding(top = PleadSpacing.xs))
    }
}

private fun footnote(selection: JudgementSelectionModel, kase: Case?, store: CaseStore): String {
    val other = kase?.let(store::judgementRecipientId)
    if (kase == null || other == null) return "Judgements are honour-based. Once delivered, the judgement can't be changed."
    val name = store.name(other, fallback = "Your partner")
    return if (selection.isTie) {
        "Resolutions are honour-based. $name can accept or decline, and it can't be changed once delivered."
    } else {
        "Judgements are honour-based. $name can accept or decline, and it can't be changed once delivered."
    }
}

@Composable
private fun Preparing() {
    Column(verticalArrangement = Arrangement.spacedBy(PleadSpacing.m)) {
        Row(
            Modifier.semantics(mergeDescendants = true) { },
            horizontalArrangement = Arrangement.spacedBy(PleadSpacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(color = PleadColor.burgundy, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            Text("The court is preparing outcomes…", style = PleadType.titleM, color = PleadColor.cocoa)
        }
        repeat(4) { RedactedOptionCard() }
    }
}

/** `.redacted(reason: .placeholder)` on an option card. */
@Composable
private fun RedactedOptionCard() {
    val shape = RoundedCornerShape(PleadRadius.tile)
    val bar = PleadColor.cocoa.copy(alpha = 0.12f)
    Row(
        Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { }
            .background(PleadColor.parchment, shape)
            .border(1.dp, PleadColor.walnut.copy(alpha = 0.22f), shape)
            .padding(PleadSpacing.l),
        horizontalArrangement = Arrangement.spacedBy(PleadSpacing.m),
    ) {
        Box(Modifier.padding(top = 1.dp).size(24.dp).background(bar, CircleShape))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PleadSpacing.xs)) {
            Box(Modifier.fillMaxWidth(0.7f).height(17.dp).background(bar, RoundedCornerShape(4.dp)))
            Box(Modifier.fillMaxWidth().height(14.dp).background(bar, RoundedCornerShape(4.dp)))
            Box(Modifier.fillMaxWidth(0.55f).height(14.dp).background(bar, RoundedCornerShape(4.dp)))
            Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
                Box(Modifier.width(60.dp).height(20.dp).background(bar, RoundedCornerShape(10.dp)))
                Box(Modifier.width(84.dp).height(13.dp).background(bar, RoundedCornerShape(3.dp)).align(Alignment.CenterVertically))
            }
        }
    }
}

@Composable
private fun Unavailable(onRetry: () -> Unit) {
    Column(Modifier.awCourtFile(), verticalArrangement = Arrangement.spacedBy(PleadSpacing.m)) {
        Text("The court is taking longer than usual to prepare the outcomes.", style = PleadType.titleM, color = PleadColor.cocoa)
        Text("Nothing is lost. Try again in a moment, or come back from the case record.", style = PleadType.body, color = PleadColor.subtleText)
        ActionButton("Try again", icon = Icons.Filled.Refresh, kind = AWButtonKind.secondary, onClick = onRetry)
    }
}

@Composable
private fun Notice(text: String) {
    Text(text, style = PleadType.body, color = PleadColor.cocoa, modifier = Modifier.awCourtFile())
}

/** One single-selection radio row: title, plain-English detail, type chip and "due in N days". */
@Composable
fun JudgementOptionCard(
    option: JudgementOption,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    action: () -> Unit,
) {
    val shape = RoundedCornerShape(PleadRadius.tile)
    val borderWidth by animateFloatAsState(if (isSelected) 2f else 1f, tween(150, easing = PleadMotion.easeOut), label = "optionBorder")
    val label = listOfNotNull(
        option.title, option.detail, option.type.title, option.dueLine,
        if (option.generic == true) "general" else null,
    ).joinToString(", ")
    Row(
        modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = label + if (option.generic == true) ". A general suggestion" else ""
                selected = isSelected
                onClick { action(); true }
            }
            .pressScaleClickable(enabled = enabled, pressedAlpha = 0.92f, onClick = action)
            .background(if (isSelected) PleadColor.paperWhite else PleadColor.parchment, shape)
            .border(borderWidth.dp, if (isSelected) PleadColor.burgundy else PleadColor.walnut.copy(alpha = 0.22f), shape)
            .padding(PleadSpacing.l),
        horizontalArrangement = Arrangement.spacedBy(PleadSpacing.m),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 1.dp).size(24.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxSize().border(2.dp, if (isSelected) PleadColor.burgundy else PleadColor.walnut.copy(alpha = 0.45f), CircleShape))
            if (isSelected) Box(Modifier.fillMaxSize().padding(6.dp).background(PleadColor.burgundy, CircleShape))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PleadSpacing.xs)) {
            Text(option.title, style = PleadType.titleM, color = PleadColor.cocoa)
            Text(option.detail, style = PleadType.body, color = PleadColor.subtleText)
            Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(PleadSpacing.s), verticalAlignment = Alignment.CenterVertically) {
                Chip(option.type.title, foreground = PleadColor.walnut)
                IconLabel(option.dueLine, Icons.Outlined.CalendarMonth)
                if (option.generic == true) {
                    Text("· general", style = PleadType.caption, color = PleadColor.subtleText.copy(alpha = 0.8f))
                }
            }
        }
    }
}

/** SUGGEST ANOTHER (N left): a fresh set from the same case context, disabled at 0. */
@Composable
fun JudgementRerollButton(title: String, isLoading: Boolean, isEnabled: Boolean, modifier: Modifier = Modifier, action: () -> Unit) {
    // `.buttonStyle(.aw(.secondary))` with a spinner + "Asking the court…" while loading.
    app.plead.android.designsystem.AWButton(
        onClick = action,
        modifier = modifier.clearAndSetSemantics {
            contentDescription = if (isLoading) "Asking the court for more options" else title
            if (isEnabled && !isLoading) onClick { action(); true }
        },
        style = app.plead.android.designsystem.AWButtonStyle.aw(AWButtonKind.secondary),
        enabled = isEnabled && !isLoading,
    ) {
        if (isLoading) {
            CircularProgressIndicator(color = PleadColor.burgundy, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            Text("Asking the court…")
        } else {
            Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(18.dp))   // arrow.triangle.2.circlepath
            Text(title)
        }
    }
}
