package app.plead.android.app

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppRouterTests {
    @Test fun showRecordPushesOnHomeElseSwitchesToCases() {
        val r = AppRouter()
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        r.showRecord(a)
        assertEquals(listOf(a), r.homePath)
        r.tab = AppTab.court
        r.showRecord(b)
        assertEquals(AppTab.cases, r.tab)
        assertEquals(listOf(b), r.casesPath)
    }

    @Test fun sheetIdsAndSettlementCase() {
        val id = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000014")
        assertEquals("settlementRoom-AAAAAAAA-0000-0000-0000-000000000014", AppSheet.settlementRoom(id).id)
        assertEquals("fileCase", AppSheet.fileCase.id)
        val r = AppRouter()
        r.sheet = AppSheet.settlementAccepted(id)
        assertEquals(id, r.settlementSheetCaseId)
        r.sheet = AppSheet.defence(id)
        assertNull(r.settlementSheetCaseId)
    }

    @Test fun resetClearsEverything() {
        val r = AppRouter()
        r.tab = AppTab.us; r.sheet = AppSheet.settings; r.homePath = listOf(UUID.randomUUID())
        r.shownSettlementPrompts = setOf("x-1")
        r.reset()
        assertEquals(AppTab.home, r.tab)
        assertNull(r.sheet)
        assertEquals(emptyList<UUID>(), r.homePath)
        assertEquals(emptySet<String>(), r.shownSettlementPrompts)
    }
}
