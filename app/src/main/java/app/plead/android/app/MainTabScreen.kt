// Port of the tab shell in ArgueWin/App/MainTabView.swift: four peer tabs (Home, Cases, Court, Us), each with its
// own stack, and the root-level sheet slot above them. Wave 1 draws the real tab bar with placeholder bodies;
// wave 2b replaces each placeholder with the tab's screen and adds the summons cover and settlement prompts.
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTabScreen(router: AppRouter, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().background(PleadColor.background)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (router.tab) {
                AppTab.home -> TabNavHost(
                    path = router.homePath,
                    onPathChange = { router.homePath = it },
                    root = { TabPlaceholder("Home", "Features/Home/HomeView.swift", "3d") },
                    caseDetail = { TabPlaceholder("Case record", "Features/CaseDetail/CaseDetailView.swift", "3d") },
                )
                AppTab.cases -> TabNavHost(
                    path = router.casesPath,
                    onPathChange = { router.casesPath = it },
                    root = { TabPlaceholder("Cases", "Features/Cases/CasesView.swift", "3d") },
                    caseDetail = { TabPlaceholder("Case record", "Features/CaseDetail/CaseDetailView.swift", "3d") },
                )
                AppTab.court -> TabPlaceholder("Court", "Features/Court/CourtTabView.swift", "2b / 3a", dark = true)
                AppTab.us -> TabPlaceholder("Us", "Features/Us/UsView.swift", "3e")
            }
        }
        PleadTabBar(selected = router.tab, onSelect = { router.tab = it })
    }

    router.sheet?.let { sheet ->
        ModalBottomSheet(onDismissRequest = { router.sheet = null }, containerColor = PleadColor.background) {
            TabPlaceholder(sheet.id, sheetOwner(sheet), "3b-3e", modifier = Modifier.height(320.dp))
        }
    }
}

/** Which iOS file (and so which port wave) owns each sheet. */
private fun sheetOwner(sheet: AppSheet): String = when (sheet) {
    AppSheet.fileCase -> "Features/FileCase/FileCaseView.swift"
    is AppSheet.defence -> "Features/Defence/DefenceView.swift"
    is AppSheet.scheduling -> "Features/Scheduling/SchedulingView.swift"
    AppSheet.settings -> "Features/Settings/SettingsView.swift"
    AppSheet.invite -> "InviteSheet"
    is AppSheet.chooseJudgement -> "Features/Judgement/JudgementSelectionView.swift"
    is AppSheet.settlementRoom -> "Features/Settlement/SettlementRoomView.swift"
    is AppSheet.settlementResponse -> "Features/Settlement/SettlementResponseSheet.swift"
    is AppSheet.settlementAccepted -> "Features/Settlement/SettlementAcceptedView.swift"
}

/**
 * The iOS tab bar (AppDelegate.styleNavigationBars): paper white at 96 %, a hairline separator on top,
 * cocoa icons and labels at 72 % (semibold 10), the selected tab burgundy (bold 10).
 */
@Composable
fun PleadTabBar(selected: AppTab, onSelect: (AppTab) -> Unit, modifier: Modifier = Modifier) {
    val unselected = PleadColor.cocoa.copy(alpha = 0.72f)
    Column(
        modifier
            .fillMaxWidth()
            .background(PleadColor.paperWhite.copy(alpha = 0.96f))
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        HorizontalDivider(thickness = 0.5.dp, color = PleadColor.separator)
        Row(Modifier.fillMaxWidth().height(49.dp), verticalAlignment = Alignment.CenterVertically) {
            tabItems.forEach { item ->
                val isSelected = item.tab == selected
                val tint = if (isSelected) PleadColor.burgundy else unselected
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .semantics { this.selected = isSelected }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Tab,
                            onClick = { onSelect(item.tab) },
                        )
                        .padding(top = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Top,
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

/** Wave-1 body: names the iOS file that owns this screen and the port wave that brings it over. */
@Composable
private fun TabPlaceholder(
    title: String,
    iosFile: String,
    wave: String,
    modifier: Modifier = Modifier,
    dark: Boolean = false,
) {
    val background = if (dark) PleadColor.courtBackdrop else PleadColor.background
    val foreground: Color = if (dark) PleadColor.cream else PleadColor.text
    Box(
        modifier.fillMaxSize().background(background).statusBarsPadding().padding(PleadSpacing.xl),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = PleadType.displayL, color = foreground, textAlign = TextAlign.Center)
            Spacer(Modifier.height(PleadSpacing.s))
            Text(iosFile, style = PleadType.metadata, color = foreground.copy(alpha = 0.7f), textAlign = TextAlign.Center)
            Text("Port wave $wave", style = PleadType.labelCapsTracked, color = foreground.copy(alpha = 0.7f))
        }
    }
}
