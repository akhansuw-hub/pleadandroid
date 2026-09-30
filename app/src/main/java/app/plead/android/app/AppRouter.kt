// Port of ArgueWin/App/AppRouter.swift: the tab / sheet enums, the router state, and the per-tab NavHost skeleton.
// Shared file (PORT.md §5): add cases, routes and methods; never rename or remove one.
//
// Still owed by later waves (they need CaseStore / CaseFlow / DeepLinkRouter from wave 2a):
//   presentSettlementPrompt(prompt: SettlementPrompt?): Boolean, open(action: CaseAction, kase: Case): Boolean,
//   route(route: CaseRoute, store: CaseStore). Add them to AppRouter below, ported 1:1 from the Swift file.
package app.plead.android.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.plead.android.services.parseUUID
import java.util.UUID

enum class AppTab { home, cases, court, us }

sealed class AppSheet {
    data object fileCase : AppSheet()
    data class defence(val caseId: UUID) : AppSheet()
    data class scheduling(val caseId: UUID) : AppSheet()
    data object settings : AppSheet()

    /** Invite share (onboarding screen 9's INVITE MY PARTNER, after the gate). */
    data object invite : AppSheet()

    /** Screen B: the chooser picks the court's judgement (amendment j). */
    data class chooseJudgement(val caseId: UUID) : AppSheet()

    /** Settle Outside Court (amendment n): propose terms, or, while an offer awaits me, counter them. */
    data class settlementRoom(val caseId: UUID) : AppSheet()

    /** The receiver's Accept / Counter / Reject sheet (the proposer sees "Offer sent · waiting"). */
    data class settlementResponse(val caseId: UUID) : AppSheet()

    /** "SETTLED OUT OF COURT", shown once per device when a settlement is accepted. */
    data class settlementAccepted(val caseId: UUID) : AppSheet()

    /** Swift `Identifiable.id` (ids print upper-case like Swift's `UUID` interpolation). */
    val id: String
        get() = when (this) {
            fileCase -> "fileCase"
            is defence -> "defence-${caseId.upper()}"
            is scheduling -> "scheduling-${caseId.upper()}"
            settings -> "settings"
            invite -> "invite"
            is chooseJudgement -> "chooseJudgement-${caseId.upper()}"
            is settlementRoom -> "settlementRoom-${caseId.upper()}"
            is settlementResponse -> "settlementResponse-${caseId.upper()}"
            is settlementAccepted -> "settlementAccepted-${caseId.upper()}"
        }

    private fun UUID.upper() = toString().uppercase()
}

/**
 * Navigation state for the signed-in app: selected tab, per-tab stacks, and the single root-level sheet /
 * full-screen cover. Every property is Compose snapshot state (Swift `@Observable`), so screens that read it
 * recompose when it changes. Main-thread only (Swift `@MainActor`).
 */
@Stable
class AppRouter {
    var tab: AppTab by mutableStateOf(AppTab.home)
    var sheet: AppSheet? by mutableStateOf(null)

    /** Full-screen summons for a case where I'm the defendant. */
    var summonsCaseId: UUID? by mutableStateOf(null)

    /** Explicit case for the Court tab (else CaseStore.courtroomCase). */
    var courtCaseId: UUID? by mutableStateOf(null)
    var homePath: List<UUID> by mutableStateOf(emptyList())
    var casesPath: List<UUID> by mutableStateOf(emptyList())

    /** Summonses the user chose to "decide later" this session (don't auto-present again). */
    var deferredSummons: Set<UUID> by mutableStateOf(emptySet())

    /** Sheet to present once the summons cover has finished dismissing. */
    var sheetAfterSummons: AppSheet? by mutableStateOf(null)

    /**
     * Settlement prompts already shown this session (by settlement id + round), so a dismissed response sheet
     * doesn't reappear on every state change.
     */
    var shownSettlementPrompts: Set<String> by mutableStateOf(emptySet())

    /** The case this sheet is about, when it is a settlement sheet. */
    val settlementSheetCaseId: UUID?
        get() = when (val s = sheet) {
            is AppSheet.settlementRoom -> s.caseId
            is AppSheet.settlementResponse -> s.caseId
            is AppSheet.settlementAccepted -> s.caseId
            else -> null
        }

    fun showRecord(caseId: UUID) {
        when (tab) {
            AppTab.home -> homePath = homePath + caseId
            else -> {
                tab = AppTab.cases
                casesPath = listOf(caseId)
            }
        }
    }

    fun reset() {
        tab = AppTab.home; sheet = null; summonsCaseId = null; courtCaseId = null
        homePath = emptyList(); casesPath = emptyList(); deferredSummons = emptySet(); sheetAfterSummons = null
        shownSettlementPrompts = emptySet()
    }
}

// MARK: - NavHost skeleton

/** Navigation Compose routes inside a tab's stack (iOS `NavigationStack` + `navigationDestination(for: UUID.self)`). */
object AppRoutes {
    const val root = "root"
    const val caseIdArg = "caseId"
    const val caseDetail = "case/{$caseIdArg}"
    fun caseDetail(caseId: UUID): String = "case/$caseId"
}

/**
 * One tab's stack: the tab's root screen with case records pushed on top, driven by the router path
 * ([AppRouter.homePath] / [AppRouter.casesPath]) exactly like the iOS `NavigationStack(path:)`. Programmatic
 * changes to [path] rebuild the stack; the system back gesture pops it and reports the shorter path through
 * [onPathChange].
 */
@Composable
fun TabNavHost(
    path: List<UUID>,
    onPathChange: (List<UUID>) -> Unit,
    navController: NavHostController = rememberNavController(),
    root: @Composable () -> Unit,
    caseDetail: @Composable (UUID) -> Unit,
) {
    val currentOnPathChange by rememberUpdatedState(onPathChange)
    val currentPath by rememberUpdatedState(path)
    // The case ids currently on the stack above the root (only this function navigates this controller).
    val built = remember(navController) { mutableListOf<UUID>() }
    NavHost(navController = navController, startDestination = AppRoutes.root) {
        composable(AppRoutes.root) { root() }
        composable(
            AppRoutes.caseDetail,
            arguments = listOf(navArgument(AppRoutes.caseIdArg) { type = NavType.StringType }),
        ) { entry ->
            entry.caseId()?.let { caseDetail(it) }
        }
    }
    // Router → stack.
    LaunchedEffect(path) {
        if (built == path) return@LaunchedEffect
        navController.popBackStack(AppRoutes.root, inclusive = false)
        path.forEach { navController.navigate(AppRoutes.caseDetail(it)) }
        built.clear()
        built.addAll(path)
    }
    // Stack → router: the system back gesture pops one record; report the shorter path. A rebuild above runs
    // without suspending, so this only ever sees its end state.
    LaunchedEffect(navController) {
        navController.currentBackStackEntryFlow.collect { entry ->
            val now = if (entry.destination.route == AppRoutes.caseDetail) {
                val index = built.lastIndexOf(entry.caseId())
                if (index >= 0) built.take(index + 1) else built.toList()
            } else {
                emptyList()
            }
            if (now != built) {
                built.clear()
                built.addAll(now)
            }
            if (now != currentPath) currentOnPathChange(now)
        }
    }
}

private fun NavBackStackEntry.caseId(): UUID? = parseUUID(arguments?.getString(AppRoutes.caseIdArg))
