// Port of ArgueWin/Features/Settlement/SettlementRoomView.swift.
package app.plead.android.features.settlement

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.plead.android.app.AppModel
import app.plead.android.app.AppSheet
import app.plead.android.designsystem.AWButtonKind
import app.plead.android.designsystem.AWInputStyle
import app.plead.android.designsystem.Chip
import app.plead.android.designsystem.InlineError
import app.plead.android.designsystem.LegalLabel
import app.plead.android.designsystem.PleadColor
import app.plead.android.designsystem.PleadMotion
import app.plead.android.designsystem.PleadRadius
import app.plead.android.designsystem.PleadSpacing
import app.plead.android.designsystem.PleadType
import app.plead.android.designsystem.PrimaryButton
import app.plead.android.designsystem.SectionLabel
import app.plead.android.designsystem.awBottomBar
import app.plead.android.designsystem.awCourtFile
import app.plead.android.designsystem.awInput
import app.plead.android.designsystem.monospacedDigit
import app.plead.android.models.SettlementOffer
import app.plead.android.models.SettlementSuggestionKind
import app.plead.android.services.CaseStore
import app.plead.android.services.EdgeErrors
import app.plead.android.services.SettlementRules
import app.plead.android.services.docketNumber
import app.plead.android.services.statusTitle
import java.util.UUID
import kotlinx.coroutines.launch
import app.plead.android.app.AppRouter

/**
 * SETTLEMENT ROOM (brief §4), a sheet. Header + case title, a neutral context line, three suggestions
 * (Quick / Fair / Peace offering), "Write our own", PROPOSE SETTLEMENT. When an offer awaits my
 * response the same view is the COUNTER OFFER room, with the incoming offer on top.
 *
 * [onDismiss] closes the sheet (`router.sheet = null`). [onSent] is called after a successful send; by default
 * (router sheet) the room swaps to the "Offer sent" response sheet.
 */
@Composable
fun SettlementRoomView(
    caseId: UUID,
    model: AppModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onSent: (() -> Unit)? = null,
) = SettlementRoomView(caseId, model.store, model.router, onDismiss, modifier, onSent)

/** The same room for callers holding the store and router (the summons' own sheet). */
@Composable
fun SettlementRoomView(
    caseId: UUID,
    store: CaseStore,
    router: AppRouter,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onSent: (() -> Unit)? = null,
) {
    val room = remember(caseId, store) {
        SettlementRoomModel(caseId, store).also { made ->
            if (made.mode == SettlementRoomModel.Mode.propose) store.trackSettlementEntry(caseId)
        }
    }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    fun sent() {
        val handler = onSent
        if (handler != null) handler() else router.sheet = AppSheet.settlementResponse(caseId)
    }

    SheetScaffold(
        cancelTitle = "Cancel",
        onCancel = onDismiss,
        cancelEnabled = !room.isSending,
        dismissDisabled = room.isSending,
        modifier = modifier.imePadding(),
        bottomBar = if (room.isAvailable && !room.didSend) {
            {
                Column(Modifier.awBottomBar(), verticalArrangement = Arrangement.spacedBy(PleadSpacing.s), horizontalAlignment = Alignment.CenterHorizontally) {
                    ActionButton(
                        room.ctaTitle,
                        isLoading = room.isSending,
                        enabled = room.canSend || room.isSending,
                        modifier = Modifier.semantics { if (room.choice == null) stateDescription = "Choose terms first" },
                    ) {
                        focusManager.clearFocus()
                        scope.launch { if (room.send()) sent() }
                    }
                    Text(SettlementCopy.helper, style = PleadType.metadata, color = PleadColor.subtleText, textAlign = TextAlign.Center)
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
                .padding(top = PleadSpacing.xs, bottom = PleadSpacing.xl)
                .animateContentSize(tween(200, easing = PleadMotion.easeOut)),
            verticalArrangement = Arrangement.spacedBy(PleadSpacing.xl),
        ) {
            SettlementRoomHeader(room)
            if (room.didSend) {
                // Nothing: the sheet swaps to the response sheet.
            } else if (!room.isAvailable) {
                Column(Modifier.awCourtFile(), verticalArrangement = Arrangement.spacedBy(PleadSpacing.m)) {
                    Text(unavailableText(room, store), style = PleadType.body, color = PleadColor.cocoa)
                    PrimaryButton("Close", kind = AWButtonKind.secondary) { onDismiss() }
                }
            } else {
                room.incomingOffer?.let { offer ->
                    SettlementOfferCard(offer, heading = "${proposerName(offer, store)}'s offer · round ${offer.roundNumber}", emphasised = false)
                }
                ContextCard(room)
                Column(verticalArrangement = Arrangement.spacedBy(PleadSpacing.m)) {
                    SectionLabel(if (room.mode == SettlementRoomModel.Mode.counter) "Or propose new terms" else "Suggested settlements")
                    SuggestionList(room, onSelect = { focusManager.clearFocus() }, onRetry = { scope.launch { room.start() } })
                    WriteOurOwn(room, focus)
                }
                InlineError(room.error)
            }
        }
    }
    LaunchedEffect(room) { room.start() }
    SelectionFeedback(room.choice)
    SuccessFeedback(room.didSend, fire = room.didSend)
}

@Composable
private fun SettlementRoomHeader(room: SettlementRoomModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(PleadSpacing.m), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
            Row(horizontalArrangement = Arrangement.spacedBy(PleadSpacing.s), verticalAlignment = Alignment.CenterVertically) {
                LegalLabel(room.title, color = PleadColor.walnut, size = 12f)
                if (room.mode == SettlementRoomModel.Mode.counter) Chip(SettlementRules.roundLine(room.nextRound), foreground = PleadColor.burgundy)
            }
            Text(room.kase?.title ?: "", style = PleadType.caseTitle, color = PleadColor.cocoa, modifier = Modifier.semantics { heading() })
            Text(
                if (room.mode == SettlementRoomModel.Mode.counter) "Change the terms. Both of you must agree." else "Resolve this without a verdict.",
                style = PleadType.body,
                color = PleadColor.subtleText,
            )
        }
        SettlementSeal(size = 58.dp, modifier = Modifier.padding(top = PleadSpacing.xs))
    }
}

@Composable
private fun ContextCard(room: SettlementRoomModel) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(PleadColor.parchment, RoundedCornerShape(PleadRadius.card))
            .padding(PleadSpacing.l)
            .semantics(mergeDescendants = true) { },
        verticalArrangement = Arrangement.spacedBy(PleadSpacing.s),
    ) {
        LegalLabel("Case context", color = PleadColor.burgundy, size = 11f)
        Text(room.contextLine, style = PleadType.judgeSpeech, color = PleadColor.cocoa)
        room.kase?.let { kase ->
            Text("${kase.docketNumber} · ${kase.statusTitle}", style = PleadType.metadata, color = PleadColor.subtleText)
        }
    }
}

@Composable
private fun SuggestionList(room: SettlementRoomModel, onSelect: () -> Unit, onRetry: () -> Unit) {
    when (room.phase) {
        SettlementRoomModel.Phase.loading -> Column(
            Modifier.clearAndSetSemantics { contentDescription = "The court is drafting suggestions" },
            verticalArrangement = Arrangement.spacedBy(PleadSpacing.m),
        ) {
            SettlementSuggestionKind.entries.forEach { _ -> RedactedSuggestionCard() }
        }
        SettlementRoomModel.Phase.ready -> Column(verticalArrangement = Arrangement.spacedBy(PleadSpacing.m)) {
            room.suggestions.forEach { s ->
                SettlementSuggestionCard(
                    suggestion = s,
                    isSelected = room.choice == SettlementRoomModel.Choice.suggestion(s.id),
                    enabled = !room.isSending,
                ) {
                    onSelect()
                    room.select(SettlementRoomModel.Choice.suggestion(s.id))
                }
            }
            if (room.hasGenericSuggestions) {
                Text("Some of these are general suggestions. Pick one, or write your own.", style = PleadType.metadata, color = PleadColor.subtleText)
            }
        }
        SettlementRoomModel.Phase.failed -> Row(horizontalArrangement = Arrangement.spacedBy(PleadSpacing.s), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "The court couldn't draft suggestions just now. Write your own terms below.",
                style = PleadType.body,
                color = PleadColor.subtleText,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 44.dp)) {
                Text("Retry", style = PleadType.uiButtonSecondary, color = PleadColor.burgundy)
            }
        }
    }
}

/** `.redacted(reason: .placeholder)` on a suggestion card: the same card with its text drawn as bars. */
@Composable
private fun RedactedSuggestionCard() {
    val shape = RoundedCornerShape(PleadRadius.tile)
    val bar = PleadColor.cocoa.copy(alpha = 0.12f)
    Row(
        Modifier
            .fillMaxWidth()
            .background(PleadColor.paperWhite.copy(alpha = 0.7f), shape)
            .border(1.dp, PleadColor.walnut.copy(alpha = 0.18f), shape)
            .padding(PleadSpacing.l),
        horizontalArrangement = Arrangement.spacedBy(PleadSpacing.m),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
            Box(Modifier.width(120.dp).height(11.dp).background(bar, RoundedCornerShape(3.dp)))
            Box(Modifier.fillMaxWidth(0.9f).height(16.dp).background(bar, RoundedCornerShape(4.dp)))
            Row(horizontalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
                Box(Modifier.width(56.dp).height(20.dp).background(bar, RoundedCornerShape(10.dp)))
                Box(Modifier.width(90.dp).height(13.dp).background(bar, RoundedCornerShape(3.dp)).align(Alignment.CenterVertically))
            }
        }
        Box(Modifier.size(26.dp).background(bar, RoundedCornerShape(13.dp)))
    }
}

@Composable
private fun WriteOurOwn(room: SettlementRoomModel, focus: FocusRequester) {
    val selected = room.choice == SettlementRoomModel.Choice.custom
    val shape = RoundedCornerShape(PleadRadius.tile)
    Column(
        Modifier
            .fillMaxWidth()
            .background(PleadColor.parchment, shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) PleadColor.burgundy else PleadColor.blush.copy(alpha = 0.8f), shape)
            .padding(PleadSpacing.l),
        verticalArrangement = Arrangement.spacedBy(PleadSpacing.m),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .semantics { this.selected = selected }
                .pressScaleClickable {
                    room.select(SettlementRoomModel.Choice.custom)
                    runCatching { focus.requestFocus() }
                },
            horizontalArrangement = Arrangement.spacedBy(PleadSpacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                LegalLabel("Write our own", color = PleadColor.burgundy, size = 13f)
                if (!selected) Text("Your own terms, in your own words.", style = PleadType.metadata, color = PleadColor.subtleText)
            }
            SettlementRadio(isSelected = selected)
        }
        AnimatedVisibility(selected, enter = fadeIn(), exit = fadeOut()) {
            SettlementOfferComposer(
                text = room.customText,
                onTextChange = { room.customText = it },
                dueDays = room.customDays,
                onDueDaysChange = { room.customDays = it },
                safetyIssue = room.composerError ?: room.safetyIssue,
                focusRequester = focus,
            )
        }
    }
    LaunchedEffect(selected) { if (selected) runCatching { focus.requestFocus() } }
}

private fun unavailableText(room: SettlementRoomModel, store: CaseStore): String {
    if (room.mode == SettlementRoomModel.Mode.counter) return EdgeErrors.settlementRoundLimitMessage
    if (store.hasPendingSettlement(room.caseId)) return "A settlement offer is already on the table. One offer at a time."
    return "Settlement is only available before closing statements begin."
}

private fun proposerName(offer: SettlementOffer, store: CaseStore): String = store.name(offer.proposedBy, fallback = "Your partner")

/** "Write our own": a 200-character text field, a 1–7 day window and an inline safety hint. */
@Composable
fun SettlementOfferComposer(
    text: String,
    onTextChange: (String) -> Unit,
    dueDays: Int,
    onDueDaysChange: (Int) -> Unit,
    safetyIssue: String?,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester = remember { FocusRequester() },
) {
    val focusManager = LocalFocusManager.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PleadSpacing.m)) {
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(PleadSpacing.xs)) {
            Box(Modifier.fillMaxWidth().awInput()) {
                if (text.isEmpty()) {
                    Text("e.g. Cook dinner on Friday and do the dishes", style = AWInputStyle.textStyle.copy(color = PleadColor.subtleText.copy(alpha = 0.7f)))
                }
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    textStyle = AWInputStyle.textStyle,
                    cursorBrush = SolidColor(PleadColor.burgundy),
                    minLines = 2,
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .semantics { contentDescription = "Settlement terms" },
                )
            }
            Text(
                "${text.length}/${SettlementRules.bodyLimit}",
                style = PleadType.caption.monospacedDigit(),
                color = if (text.length >= SettlementRules.bodyLimit) PleadColor.burgundy else PleadColor.subtleText,
                modifier = Modifier.clearAndSetSemantics { contentDescription = "${text.length} of ${SettlementRules.bodyLimit} characters" },
            )
        }
        DueDaysStepper(dueDays, onDueDaysChange)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
            Icon(
                if (safetyIssue == null) Icons.Outlined.PanTool else Icons.Filled.Error,   // hand.raised / exclamationmark.circle.fill
                contentDescription = null,
                tint = if (safetyIssue == null) PleadColor.subtleText else PleadColor.burgundy,
                modifier = Modifier.size(14.dp).padding(top = 1.dp),
            )
            Text(safetyIssue ?: SettlementCopy.safetyHint, style = PleadType.metadata, color = if (safetyIssue == null) PleadColor.subtleText else PleadColor.burgundy)
        }
    }
}

/** SwiftUI `Stepper(value:in:)` with the calendar label: the value on the left, the − / + pill on the right. */
@Composable
private fun DueDaysStepper(value: Int, onChange: (Int) -> Unit) {
    val range = SettlementRules.dueDaysRange
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { stateDescription = SettlementRules.dueLine(value) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(PleadSpacing.s), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CalendarMonth, contentDescription = null, tint = PleadColor.walnut, modifier = Modifier.size(18.dp))
            Text(SettlementRules.dueLine(value).capitalizedFirst, style = PleadType.body.monospacedDigit(), color = PleadColor.cocoa)
        }
        val pill = RoundedCornerShape(8.dp)
        Row(Modifier.height(32.dp).background(PleadColor.cocoa.copy(alpha = 0.06f), pill), verticalAlignment = Alignment.CenterVertically) {
            StepperHalf(Icons.Filled.Remove, "Decrement", enabled = value > range.first) { onChange(value - 1) }
            Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 8.dp).background(PleadColor.separator))
            StepperHalf(Icons.Filled.Add, "Increment", enabled = value < range.last) { onChange(value + 1) }
        }
    }
}

@Composable
private fun StepperHalf(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .width(47.dp)
            .fillMaxHeight()
            .alpha(if (enabled) 1f else 0.3f)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = PleadColor.cocoa, modifier = Modifier.size(18.dp))
    }
}
