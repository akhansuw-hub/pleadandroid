// Port of ArgueWin/Features/Summons/SummonsView.swift.
//
// Full-screen summons for the defendant: seal, title, charge, remedy, plea.
// "Decide later" closes it for this session; the Home card reopens it. (iOS `.fullScreenCover`: the system back
// gesture does what "Decide later" does, since a cover has no other way out.)
package app.plead.android.features.summons

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.plead.android.app.AppModel
import app.plead.android.app.AppRouter
import app.plead.android.app.AppSheet
import app.plead.android.designsystem.AWButton
import app.plead.android.designsystem.AWButtonKind
import app.plead.android.designsystem.AWButtonStyle
import app.plead.android.designsystem.Countdown
import app.plead.android.designsystem.InlineError
import app.plead.android.designsystem.PleadColor
import app.plead.android.designsystem.PleadMotion
import app.plead.android.designsystem.PleadRadius
import app.plead.android.designsystem.PleadSpacing
import app.plead.android.designsystem.PleadType
import app.plead.android.designsystem.SummonsSeal
import app.plead.android.designsystem.TextStyleKind
import app.plead.android.designsystem.accessibilityReduceMotion
import app.plead.android.features.casedetail.CaseDialog
import app.plead.android.features.casedetail.ContentUnavailable
import app.plead.android.features.casedetail.DialogAction
import app.plead.android.features.casedetail.SettlementEntryButtonSlot
import app.plead.android.features.casedetail.SettlementRoomSheetSlot
import app.plead.android.models.Case
import app.plead.android.models.CaseStatus
import app.plead.android.models.EdgeError
import app.plead.android.models.Plea
import app.plead.android.services.CaseStore
import app.plead.android.services.docketNumber
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * The summons cover (`router.summonsCaseId`). The integrator mounts it over the tabs where `MainTabEffects` draws the
 * wave placeholder, with `onDismiss = { router.summonsCaseId = null }`.
 */
@Composable
fun SummonsCover(model: AppModel, caseId: UUID, onDismiss: () -> Unit) {
    SummonsView(caseId, model.store, model.router, onDismiss)
}

/** Statics of the summons (Swift `SummonsView` members). */
object SummonsView {
    /** The cover's gradient foot (`Color(hex: 0x3A1713)`). */
    val foot: Color = app.plead.android.designsystem.Color(hex = 0x3A1713)

    fun confirmTitle(plea: Plea?): String = if (plea == Plea.guilty) "Plead guilty?" else "Plead not guilty?"

    fun confirmMessage(plea: Plea, plaintiffName: String?): String =
        if (plea == Plea.guilty) {
            "The case ends now and ${plaintiffName ?: "the plaintiff"}'s remedy is granted."
        } else {
            "You'll file a defence within 48 hours and propose a trial time."
        }
}

@Composable
fun SummonsView(caseId: UUID, store: CaseStore, router: AppRouter, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    var working by remember { mutableStateOf(false) }

    fun decideLater() {
        router.deferredSummons = router.deferredSummons + caseId
        onDismiss()
    }
    BackHandler(enabled = !working) { decideLater() }

    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(PleadColor.mahogany, SummonsView.foot))),
    ) {
        val kase = store.caseById(caseId)
        if (kase != null) {
            SummonsContent(kase, store, router, working, { working = it }, ::decideLater, onDismiss)
        } else {
            ContentUnavailable("Case not found", "questionmark.folder", color = PleadColor.cream, titleColor = PleadColor.cream)
        }
    }
}

@Composable
private fun SummonsContent(
    kase: Case,
    store: CaseStore,
    router: AppRouter,
    working: Boolean,
    setWorking: (Boolean) -> Unit,
    decideLater: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    var confirmPlea by remember { mutableStateOf<Plea?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // Settle Outside Court (amendment n): the Settlement Room over the summons, before any plea.
    var showSettlementRoom by remember { mutableStateOf(false) }
    val plaintiff = store.profile(kase.plaintiffId)

    /** Leave the summons for the settlement's response / "offer sent" sheet. */
    fun openSettlement() {
        router.sheetAfterSummons = AppSheet.settlementResponse(kase.id)
        showSettlementRoom = false
        onDismiss()
    }

    fun enter(plea: Plea) {
        setWorking(true); error = null
        scope.launch {
            try {
                store.enterPlea(plea, kase)
                view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
                if (plea == Plea.notGuilty) router.sheetAfterSummons = AppSheet.defence(kase.id)
                onDismiss()
            } catch (e: Exception) {
                error = (e as? EdgeError)?.message ?: "Couldn't reach the court. Try again."
            }
            setWorking(false)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(horizontal = PleadSpacing.xl)
                .padding(bottom = PleadSpacing.l),
            verticalArrangement = Arrangement.spacedBy(PleadSpacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth()) {
                Box(
                    Modifier
                        .defaultMinSize(minHeight = 44.dp)
                        .alpha(if (working) 0.45f else 1f)
                        .clickable(enabled = !working, role = Role.Button, onClick = decideLater),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Decide later", style = PleadType.uiButtonSecondary, color = PleadColor.cream.copy(alpha = 0.8f))
                }
                Spacer(Modifier.weight(1f))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(PleadSpacing.m)) {
                Text(
                    "YOU HAVE BEEN SUMMONED",
                    style = PleadType.ui(13f, FontWeight.Bold, relativeTo = TextStyleKind.footnote).copy(letterSpacing = 2.sp),
                    color = PleadColor.gold,
                )
                SummonsSeal(size = 150.dp)
                Text(kase.docketNumber, style = PleadType.metadataMedium, color = PleadColor.cream.copy(alpha = 0.7f))
                Text(kase.title, style = PleadType.caseTitle, color = PleadColor.cream, textAlign = TextAlign.Center)
                Text(
                    "${plaintiff?.displayName ?: "The plaintiff"} v. ${store.me?.displayName ?: "you"}",
                    style = PleadType.caseParties,
                    color = PleadColor.cream.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                )
            }

            SummonsFiling("The charge", kase.charge)
            SummonsFiling("${plaintiff?.displayName ?: "The plaintiff"} asks the court for", kase.remedyRequested)

            kase.deadlineAt?.let { deadline ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = PleadSpacing.xs).semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Plea due in", style = PleadType.body, color = PleadColor.cream.copy(alpha = 0.7f))
                    Spacer(Modifier.weight(1f))
                    Countdown(target = deadline, font = PleadType.body, color = PleadColor.cream)
                }
            }

            Text("The court awaits your plea.", style = PleadType.judgeSpeech, color = PleadColor.cream.copy(alpha = 0.8f))
        }

        // `.safeAreaInset(edge: .bottom)`: the pleas on the cover's darker foot.
        Box(Modifier.fillMaxWidth().background(SummonsView.foot).navigationBarsPadding()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .alpha(if (working) 0.45f else 1f)
                    .padding(horizontal = PleadSpacing.xl, vertical = PleadSpacing.m),
                verticalArrangement = Arrangement.spacedBy(PleadSpacing.m),
            ) {
                InlineError(error)
                if (kase.status == CaseStatus.summoned) {
                    AWButton(onClick = { confirmPlea = Plea.notGuilty }, style = AWButtonStyle.aw(AWButtonKind.onDark), enabled = !working) {
                        Text("Plead Not Guilty")
                    }
                    PleaGuiltyButton(enabled = !working) { confirmPlea = Plea.guilty }
                    if (store.canProposeSettlement(kase.id)) {
                        Box(Modifier.padding(top = PleadSpacing.xs)) {
                            SettlementEntryButtonSlot(null) { if (!working) showSettlementRoom = true }
                        }
                    } else if (store.hasPendingSettlement(kase.id)) {
                        Box(Modifier.padding(top = PleadSpacing.xs)) {
                            SettlementEntryButtonSlot("Settlement offer on the table") { if (!working) openSettlement() }
                        }
                    }
                } else {
                    Text("Plea entered.", style = PleadType.titleM, color = PleadColor.cream)
                }
            }
            if (working) {
                CircularProgressIndicator(color = PleadColor.cream, strokeWidth = 2.dp, modifier = Modifier.size(24.dp).align(Alignment.Center))
            }
        }
    }

    if (showSettlementRoom) {
        // Proposing hides the summons until the offer resolves; if it is rejected, withdrawn or
        // expires the summons shows again, unchanged.
        SettlementRoomSheetSlot(store, router, kase.id, onSent = ::openSettlement, onDismiss = { showSettlementRoom = false })
    }

    confirmPlea?.let { plea ->
        CaseDialog(
            SummonsView.confirmTitle(plea),
            SummonsView.confirmMessage(plea, plaintiff?.displayName),
            listOf(
                DialogAction(if (plea == Plea.guilty) "Plead Guilty" else "Plead Not Guilty", destructive = plea == Plea.guilty) { enter(plea) },
                DialogAction("Not yet", cancel = true),
            ),
            onDismiss = { confirmPlea = null },
        )
    }
}

@Composable
private fun SummonsFiling(label: String, text: String) {
    val shape = RoundedCornerShape(PleadRadius.card)
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.07f), shape)
            .border(1.dp, PleadColor.cream.copy(alpha = 0.1f), shape)
            .padding(PleadSpacing.l)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(PleadSpacing.s),
    ) {
        Text(label.uppercase(), style = PleadType.labelCapsTracked, color = PleadColor.blush)
        Text(text, style = PleadType.body, color = PleadColor.cream)
    }
}

/** Outlined rounded rectangle on mahogany for the guilty plea (secondary weight, still clearly a button). */
@Composable
private fun PleaGuiltyButton(enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduceMotion = accessibilityReduceMotion()
    val scale by animateFloatAsState(if (pressed && !reduceMotion) 0.97f else 1f, tween(120, easing = PleadMotion.easeOut), label = "pleaGuiltyPress")
    val shape = RoundedCornerShape(PleadRadius.button)
    Box(
        Modifier
            .scale(scale)
            .fillMaxWidth()
            .defaultMinSize(minHeight = 50.dp)
            .border(1.5.dp, PleadColor.cream.copy(alpha = 0.4f), shape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("Plead Guilty", style = PleadType.uiButtonSecondary, color = PleadColor.cream)
    }
}
