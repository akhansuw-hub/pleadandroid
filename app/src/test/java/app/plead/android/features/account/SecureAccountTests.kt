// Android half of ArgueWinTests/SecureAccountTests.swift (amendment az): the Google and Apple-OAuth paths of
// SecureAccountModel, the callback conflict, the demo stages and the screen's copy. The iOS file has no view
// assertions of its own; its `AnonymousAuthTests` are in app/AppModelTests.kt and its `SecureAccountFlowTests` in
// SecureAccountFlowTests.kt.
package app.plead.android.features.account

import app.plead.android.services.AuthLinkError
import app.plead.android.services.GoogleCredential
import app.plead.android.services.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SecureAccountTests {
    @get:Rule val main = MainDispatcherRule()

    private val google = GoogleCredential(idToken = "google-token", nonce = "nonce")

    @Test fun googleLinksKeepingTheUid() = runTest(main.dispatcher) {
        val backend = FakeSecureAuthBackend()
        val auth = anonymousAuth(backend)
        val flow = SecureAccountModel(auth)
        flow.google(google)
        assertEquals(SecureAccountModel.Outcome.linkedGoogle, flow.outcome)
        assertEquals(backend.anonymousId, auth.userId)
        assertFalse(auth.isAnonymous)
    }

    @Test fun cancelledGoogleSheetDoesNothing() = runTest(main.dispatcher) {
        val backend = FakeSecureAuthBackend()
        val flow = SecureAccountModel(anonymousAuth(backend))
        flow.google(null)
        assertNull(flow.outcome)
        assertNull(flow.error)
        assertEquals(0, backend.appleLinks)
    }

    @Test fun googleConflictCancelThenSignIn() = runTest(main.dispatcher) {
        val backend = FakeSecureAuthBackend(identityInUse = true)
        val auth = anonymousAuth(backend)
        val flow = SecureAccountModel(auth)
        flow.google(google)
        assertEquals(SecureAccountModel.Conflict.google(google), flow.conflict)
        assertEquals("google", flow.conflict?.provider)
        assertTrue(auth.isAnonymous)
        flow.cancelConflict()
        assertEquals(SecureAccountModel.Stage.choose, flow.stage)
        flow.google(google)
        flow.confirmConflict()
        assertEquals(SecureAccountModel.Outcome.signedInInstead, flow.outcome)
        assertEquals(backend.existingId, auth.userId)
    }

    @Test fun googleUnavailableSaysSo() = runTest(main.dispatcher) {
        val flow = SecureAccountModel(anonymousAuth(FakeSecureAuthBackend()))
        flow.googleFailed(unavailable = true)
        assertEquals(AuthLinkError.providerUnavailable.message, flow.error)
        flow.googleFailed()
        assertEquals(SecureAccountModel.googleError, flow.error)
    }

    /** The in-memory backend completes the OAuth link at once (live: through `plead://login-callback`). */
    @Test fun appleOAuthLinks() = runTest(main.dispatcher) {
        val backend = FakeSecureAuthBackend()
        val auth = anonymousAuth(backend)
        val flow = SecureAccountModel(auth)
        flow.appleOAuth()
        assertEquals(SecureAccountModel.Outcome.linkedApple, flow.outcome)
        assertEquals(1, backend.appleLinks)
        assertEquals(backend.anonymousId, auth.userId)
    }

    @Test fun appleOAuthConflictSignsInThroughTheBrowser() = runTest(main.dispatcher) {
        val backend = FakeSecureAuthBackend(identityInUse = true)
        val auth = anonymousAuth(backend)
        val flow = SecureAccountModel(auth)
        flow.appleOAuth()
        assertEquals(SecureAccountModel.Conflict.apple(null), flow.conflict)
        flow.confirmConflict()
        assertEquals(SecureAccountModel.Outcome.signedInInstead, flow.outcome)
        assertEquals(backend.existingId, auth.userId)
        assertEquals(1, backend.appleSignIns)
        assertFalse(flow.appleSignsIn)
    }

    @Test fun appleIsHiddenWhenTheProviderIsOff() = runTest(main.dispatcher) {
        assertFalse(anonymousAuth(FakeSecureAuthBackend(appleEnabled = false)).isAppleAvailable())
        assertTrue(anonymousAuth(FakeSecureAuthBackend()).isAppleAvailable())
    }

    /** `plead://login-callback?error_code=identity_already_exists`: Apple on the first stage, email on the others. */
    @Test fun callbackConflictMatchesTheStage() = runTest(main.dispatcher) {
        val flow = SecureAccountModel(anonymousAuth(FakeSecureAuthBackend()))
        flow.linkConflictReceived(AuthLinkError.identityInUse)
        assertEquals(SecureAccountModel.Conflict.apple(null), flow.conflict)
        flow.cancelConflict()
        flow.useEmail()
        flow.email = "arif@example.com"
        flow.linkConflictReceived(AuthLinkError.identityInUse)
        assertEquals(SecureAccountModel.Conflict.email("arif@example.com"), flow.conflict)
        flow.cancelConflict()
        flow.linkConflictReceived(AuthLinkError.providerUnavailable)
        assertNull(flow.conflict)
        assertEquals(AuthLinkError.providerUnavailable.message, flow.error)
    }

    @Test fun demoStagesOpenWhereTheFlagSays() = runTest(main.dispatcher) {
        val auth = anonymousAuth(FakeSecureAuthBackend())
        SecureAccountModel(auth).also { SecureAccountModel.applyDemo(it, "email") }.let {
            assertEquals(SecureAccountModel.Stage.email, it.stage)
            assertEquals("arif@example.com", it.email)
        }
        SecureAccountModel(auth).also { SecureAccountModel.applyDemo(it, "code") }.let {
            assertEquals(SecureAccountModel.Stage.code, it.stage)
        }
        SecureAccountModel(auth).also { SecureAccountModel.applyDemo(it, "conflict") }.let {
            assertEquals("apple", it.conflict?.provider)
        }
        SecureAccountModel(auth).also { SecureAccountModel.applyDemo(it, null) }.let {
            assertEquals(SecureAccountModel.Stage.choose, it.stage)
            assertNull(it.conflict)
        }
    }

    /** Plead, never the internal project name; no "Premium"; no prices; the Android copy says "phone". */
    @Test fun copyIsPleadAndPriceFree() {
        for (s in SecureAccountCopy.all) {
            assertFalse(s, s.contains("ArgueWin", ignoreCase = true))
            assertFalse(s, s.contains("Premium", ignoreCase = true))
            assertFalse(s, Regex("[$£€]\\s?\\d").containsMatchIn(s))
            assertFalse(s, s.contains("iPhone"))
        }
        assertEquals("Check arif@example.com. Tap the link on this phone, or enter the code.", SecureAccountCopy.check("arif@example.com"))
    }
}
