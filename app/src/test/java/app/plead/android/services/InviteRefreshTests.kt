package app.plead.android.services

import app.plead.android.models.Couple
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.UUID

/**
 * Amendment bi: an unlinked couple whose invite has expired gets a fresh code before anyone shares it.
 * (Demo store: `createCouple()` plays the server, which keeps the couple and mints a new 7-day code.)
 */
class InviteRefreshTests {
    @get:Rule val main = MainDispatcherRule()

    private fun store(expiresInSeconds: Long, linked: Boolean = false): CaseStore {
        val couple = Couple(
            id = UUID.randomUUID(), inviteCode = "KX7P2Q",
            inviteExpiresAt = Instant.now().plusSeconds(expiresInSeconds),
            linkedAt = if (linked) Instant.now() else null,
        )
        val me = PreviewData.onboardingMe.copy(coupleId = couple.id)
        return CaseStore(preview = me, partner = null, couple = couple)
    }

    @Test fun expiredInviteIsRefreshed() = runTest(main.dispatcher) {
        val s = store(-60)
        assertTrue(s.couple!!.inviteIsExpired)
        s.refreshInviteIfNeeded()
        assertFalse(s.couple!!.inviteIsExpired)
        assertNotEquals("KX7P2Q", s.couple!!.inviteCode)
        assertFalse(s.couple!!.isLinked)
    }

    @Test fun openInviteIsLeftAlone() = runTest(main.dispatcher) {
        val s = store(3600)
        val before = s.couple
        s.refreshInviteIfNeeded()
        assertEquals(before, s.couple)
        // createCouple() is what the invite screens call; it must not rotate a working code either.
        assertEquals(before, s.createCouple())
    }

    @Test fun linkedCoupleIsLeftAlone() = runTest(main.dispatcher) {
        val s = store(-60, linked = true)
        val before = s.couple
        s.refreshInviteIfNeeded()
        assertEquals(before, s.couple)
    }

    @Test fun noCoupleIsLeftAlone() = runTest(main.dispatcher) {
        val s = CaseStore(preview = PreviewData.onboardingMe, partner = null, couple = null)
        s.refreshInviteIfNeeded()
        assertNull(s.couple)
    }
}
