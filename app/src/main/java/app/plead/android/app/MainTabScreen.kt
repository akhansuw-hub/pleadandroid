// Port of the tab shell in ArgueWin/App/MainTabView.swift: four peer tabs (Home, Cases, Court, Us), each with its
// own stack, and the root-level sheet slot above them. The behaviour half (summons cover, settlement prompts,
// routed links) is MainTabEffects.kt.
package app.plead.android.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import app.plead.android.designsystem.PleadMotion
import app.plead.android.designsystem.accessibilityReduceMotion
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import app.plead.android.courtroom.JudgeSprite
import app.plead.android.features.casedetail.CaseSheetHost
import app.plead.android.features.cases.CasesTab
import app.plead.android.features.court.CourtTab
import app.plead.android.features.defence.DefenceSheet
import app.plead.android.features.filecase.FileCaseSheet
import app.plead.android.features.home.HomeTab
import app.plead.android.features.judgement.JudgementSelectionView
import app.plead.android.features.onboarding.EditAvatarView
import app.plead.android.features.onboarding.InviteSheet
import app.plead.android.features.onboarding.OnboardingPreviewScreens
import app.plead.android.features.scheduling.SchedulingSheet
import app.plead.android.features.settings.SettingsView
import app.plead.android.features.settlement.SettlementAcceptedView
import app.plead.android.features.settlement.SettlementResponseSheet
import app.plead.android.features.settlement.SettlementRoomView
import app.plead.android.features.us.UsTab
import app.plead.android.models.JudgePersona
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.plead.android.designsystem.PleadColor
import app.plead.android.designsystem.PleadSpacing
import app.plead.android.designsystem.PleadType
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily

/** Tab bar item: label and SF Symbol (iOS fills the symbol on the selected tab). */
private data class TabItem(val tab: AppTab, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val tabItems = listOf(
    TabItem(AppTab.home, "Home", Icons.Outlined.Home, Icons.Filled.Home),                                 // house
    TabItem(AppTab.cases, "Cases", Icons.Outlined.Folder, Icons.Filled.Folder),                            // folder
    TabItem(AppTab.court, "Court", Icons.Outlined.AccountBalance, Icons.Filled.AccountBalance),            // building.columns
    TabItem(AppTab.us, "Us", Icons.Outlined.FavoriteBorder, Icons.Filled.Favorite),                        // heart
)

@Composable
fun MainTabScreen(model: AppModel, modifier: Modifier = Modifier) {
    val router = model.router
    Column(modifier.fillMaxSize().background(PleadColor.background)) {
        // iOS `TabView` keeps each tab's state while another tab shows (scroll position, the pushed case record, the
        // nav stack): each tab's saveable state lives in this holder under the tab's name and is restored when it comes
        // back. Only the selected tab stays composed, so the courtroom's motion stops when the Court tab is left (iOS
        // `CourtroomScene.onDisappear`: `entrance.end(); motion.disappear()`).
        val tabStates = rememberSaveableStateHolder()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val tab = router.tab
            tabStates.SaveableStateProvider(tab.name) {
                when (tab) {
                    AppTab.home -> HomeTab(model)
                    AppTab.cases -> CasesTab(model)
                    // The courtroom draws edge to edge: under the status bar and (it measures itself
                    // `CourtTabLayout.tabBarHeight` + the navigation bar taller than this slot) under the tab bar below,
                    // which is drawn after it.
                    AppTab.court -> CourtTab(model)
                    AppTab.us -> UsTab(model, editAvatar = editAvatarSlot(model), judgeSprite = judgeSpriteSlot)
                }
            }
        }
        PleadTabBar(selected = router.tab, onSelect = { router.tab = it })
    }

    router.sheet?.let { sheet ->
        key(sheet.id) { MainSheet(model, sheet) }
    }
}

/** Us / Settings "Edit avatar": `EditAvatarView` (AvatarCreatorView.swift). */
private fun editAvatarSlot(model: AppModel): @Composable (onDone: () -> Unit) -> Unit = { done -> EditAvatarView(model.store, onDismiss = done) }

/** The Us tab's judge tiles: the courtroom's pixel judge (`JudgeSprite`, CourtArt). */
private val judgeSpriteSlot: @Composable (JudgePersona, Dp) -> Unit = { persona, cell -> JudgeSprite(persona, cell = cell.value) }

/**
 * The root-level sheet slot (Swift `.sheet(item: $router.sheet)`). The filing sheets bring their own bottom sheet;
 * the others are presented full height in the same host, keyed on `sheet.id` so a swap (room → response) rebuilds
 * the content. Swipe-down is refused while a send is in flight (`SheetScaffold(dismissDisabled:)`).
 */
@Composable
private fun MainSheet(model: AppModel, sheet: AppSheet) {
    val router = model.router
    val dismiss = { router.sheet = null }
    when (sheet) {
        AppSheet.fileCase -> FileCaseSheet(model, dismiss)
        is AppSheet.defence -> DefenceSheet(model, sheet.caseId, dismiss)
        is AppSheet.scheduling -> SchedulingSheet(model, sheet.caseId, dismiss)
        AppSheet.settings -> CaseSheetHost(onDismissRequest = dismiss) {
            SettingsView(
                model, dismiss,
                editAvatar = editAvatarSlot(model),
                onboardingPreview = { close -> OnboardingPreviewScreens(model, close) },
            )
        }
        AppSheet.invite -> CaseSheetHost(onDismissRequest = dismiss) { InviteSheet(model, onDone = dismiss) }
        is AppSheet.chooseJudgement -> CaseSheetHost(onDismissRequest = dismiss) { JudgementSelectionView(sheet.caseId, model, dismiss) }
        is AppSheet.settlementRoom -> CaseSheetHost(onDismissRequest = dismiss) { SettlementRoomView(sheet.caseId, model, dismiss) }
        is AppSheet.settlementResponse -> CaseSheetHost(onDismissRequest = dismiss) { SettlementResponseSheet(sheet.caseId, model, dismiss) }
        is AppSheet.settlementAccepted -> CaseSheetHost(onDismissRequest = dismiss) { SettlementAcceptedView(sheet.caseId, model, dismiss) }
    }
}

/**
 * The iOS tab bar (AppDelegate.styleNavigationBars): paper white at 96 %, a hairline separator on top,
 * cocoa icons and labels at 72 % (semibold 10), the selected tab burgundy (bold 10). Over the Court it renders dark
 * as iOS 26 does there ([TabBarStyle.court]); the colours cross-fade on the switch (instant with Reduce Motion).
 * iOS 26's selection pill sits behind the selected item ([TabBarPill]) and slides to a newly selected tab.
 */
@Composable
fun PleadTabBar(selected: AppTab, onSelect: (AppTab) -> Unit, modifier: Modifier = Modifier) {
    val style = TabBarStyle.forTab(selected)
    val reduceMotion = accessibilityReduceMotion()
    val spec: AnimationSpec<Color> = if (reduceMotion) snap() else PleadMotion.fade()
    val background by animateColorAsState(style.background, spec, label = "tabBarBackground")
    val separator by animateColorAsState(style.separator, spec, label = "tabBarSeparator")
    val unselected by animateColorAsState(style.unselected, spec, label = "tabBarUnselected")
    val selectedTint by animateColorAsState(style.selected, spec, label = "tabBarSelected")
    Column(
        modifier
            // UI tests: the bar's frame (iOS `app.tabBars.firstMatch`); the bar itself has no other semantics.
            .testTag("tabBar")
            .fillMaxWidth()
            .background(background)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        HorizontalDivider(thickness = 0.5.dp, color = separator)
        // The 49 dp item row (its height is part of `CourtTabLayout.tabBarHeight`); the selected-tab pill is drawn
        // behind the items.
        Box(Modifier.fillMaxWidth().height(49.dp)) {
            TabBarPill(selectedIndex = tabItems.indexOfFirst { it.tab == selected }, count = tabItems.size, style = style)
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                tabItems.forEach { item ->
                    val isSelected = item.tab == selected
                    val tint = if (isSelected) selectedTint else unselected
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .semantics { this.selected = isSelected }
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Tab,
                                onClick = { onSelect(item.tab) },
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        // Icon and label centred on the pill, as iOS 26 centres them on its selection capsule.
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = if (isSelected) item.selectedIcon else item.icon,
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(26.dp),
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = item.label,
                            color = tint,
                            style = TextStyle(
                                fontFamily = FontFamily.Default,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                fontSize = 10.sp,
                            ),
                        )
                    }
                }
            }
        }
    }
}
