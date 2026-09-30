// Wave 3a: `CaseStore` as the Court tab's store (`CourtTabStore`, wave 2b's seam for Swift `@Environment(CaseStore.self)`
// in CourtTabView.swift). An adapter rather than a conformance, so CaseStore.kt (wave 2a) stays untouched: every member
// reads the store's snapshot state, so the Court tab recomposes exactly as it does on the store itself.
package app.plead.android.services

import app.plead.android.features.court.CourtTabStore
import app.plead.android.models.Case
import app.plead.android.models.Profile
import app.plead.android.models.Settlement
import java.util.UUID

/** `CaseStore` seen through `CourtTabStore`. */
class CaseStoreCourt(val store: CaseStore) : CourtTabStore {
    override val me: Profile? get() = store.me
    override val partner: Profile? get() = store.partner
    override val courtroomCase: Case? get() = store.courtroomCase
    override val closedCases: List<Case> get() = store.closedCases
    override fun caseById(id: UUID): Case? = store.caseById(id)
    override fun settlement(caseId: UUID): Settlement? = store.settlement(caseId)
    override fun pendingSettlementForMe(caseId: UUID): Boolean = store.pendingSettlementForMe(caseId)
}

/** The Court tab's view of this store. */
val CaseStore.court: CourtTabStore get() = CaseStoreCourt(this)
