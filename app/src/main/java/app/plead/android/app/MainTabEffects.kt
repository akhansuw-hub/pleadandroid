// The behaviour half of ArgueWin/App/MainTabView.swift (wave 1's MainTabScreen.kt draws the tab bar and sheet slot):
// the summons cover, settlement prompts and celebrations, and consuming routed deep links / tabs.
package app.plead.android.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.plead.android.features.summons.SummonsCover
import app.plead.android.services.SettlementPrompt

@Composable
fun MainTabEffects(model: AppModel) {
    val router = model.router
    val store = model.store
    val links = model.links

    // The summons cover (a full-screen cover on iOS). "Decide later" defers it this session.
    val summonsId = router.summonsCaseId
    var coverWasUp by remember { mutableStateOf(false) }
    if (summonsId != null) {
        key(summonsId) { SummonsCover(model, summonsId) { router.summonsCaseId = null } }
    }
    LaunchedEffect(summonsId) {
        if (summonsId != null) {
            coverWasUp = true
        } else if (coverWasUp) {
            // onDismiss of the cover.
            coverWasUp = false
            val next = router.sheetAfterSummons
            if (next != null) {
                router.sheetAfterSummons = null
                router.sheet = next
            } else {
                router.presentSettlementPrompt(store.settlementPrompt)
            }
        }
    }

    val pendingSummonsId = store.pendingSummons?.id
    LaunchedEffect(pendingSummonsId) {
        val id = pendingSummonsId ?: return@LaunchedEffect
        if (router.deferredSummons.contains(id) || router.summonsCaseId != null || router.sheet != null) return@LaunchedEffect
        router.summonsCaseId = id
    }

    // Settle Outside Court: an offer awaiting me opens its response sheet before the court (app open,
    // realtime arrival, entering the Court tab); an accepted settlement shows its seal once.
    val prompt = store.settlementPrompt
    LaunchedEffect(prompt) { router.presentSettlementPrompt(prompt) }

    val sheetClosed = router.sheet == null
    var firstSheetState by remember { mutableStateOf(true) }
    LaunchedEffect(sheetClosed) {
        if (firstSheetState) {
            firstSheetState = false
            return@LaunchedEffect
        }
        if (!sheetClosed) return@LaunchedEffect
        val s = store.settlementToCelebrate
        if (s != null) router.sheet = AppSheet.settlementAccepted(s.caseId) else router.presentSettlementPrompt(store.settlementPrompt)
    }

    val celebrateId = store.settlementToCelebrate?.id
    LaunchedEffect(celebrateId) {
        val s = store.settlementToCelebrate ?: return@LaunchedEffect
        if (router.summonsCaseId != null) return@LaunchedEffect
        // Swap over this case's own settlement sheets (I accepted, or the partner did while I watched).
        if (router.sheet == null || router.settlementSheetCaseId == s.caseId) router.sheet = AppSheet.settlementAccepted(s.caseId)
    }

    val tab = router.tab
    var firstTab by remember { mutableStateOf(true) }
    LaunchedEffect(tab) {
        if (firstTab) {
            firstTab = false
            return@LaunchedEffect
        }
        if (tab != AppTab.court) return@LaunchedEffect
        val id = router.courtCaseId ?: store.courtroomCase?.id ?: return@LaunchedEffect
        if (!store.pendingSettlementForMe(id)) return@LaunchedEffect
        val s = store.settlement(id) ?: return@LaunchedEffect
        router.presentSettlementPrompt(SettlementPrompt(caseId = id, settlementId = s.id, round = s.currentRound))
    }

    val route = links.pendingCaseRoute
    LaunchedEffect(route) {
        val r = route ?: return@LaunchedEffect
        links.pendingCaseRoute = null
        router.route(r, store)
    }

    val pendingTab = links.pendingTab
    LaunchedEffect(pendingTab) {
        val t = pendingTab ?: return@LaunchedEffect
        links.pendingTab = null
        router.sheet = null
        router.tab = t
    }
}
