// Port of ArgueWinTests/SecureAccountTests.swift → `SecureAccountFlowTests` (SecureAccountView's state machine,
// including the identity-already-in-use path). The `AnonymousAuthTests` half was ported by wave 2a (app/AppModelTests.kt).
package app.plead.android.features.account

import app.plead.android.app.AppGate
import app.plead.android.app.AppModel
import app.plead.android.services.AppleCredential
import app.plead.android.services.AuthBackend
import app.plead.android.services.AuthLinkError
import app.plead.android.services.AuthService
import app.plead.android.services.AuthUserState
import app.plead.android.services.MainDispatcherRule
import app.plead.android.services.PreviewData
import app.plead.android.services.PurchasesService
import app.plead.android.services.PushService
import app.plead.android.services.UserDefaults
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** A fake Supabase Auth: fixed uids, counts calls, can report "identity already in use". */
internal class FakeSecureAuthBackend(
    val anonymousId: UUID = UUID.randomUUID(),
    val existingId: UUID = UUID.randomUUID(),
    var identityInUse: Boolean = false,
    var appleEnabled: Boolean = true,
) : AuthBackend {
    private var current: AuthUserState? = null
    var anonymousSignIns = 0
    var appleSignIns = 0
    var appleLinks = 0
    val emailLinks = mutableListOf<String>()

    override suspend fun signInAnonymously(): AuthUserState {
        anonymousSignIns += 1
        return AuthUserState(anonymousId, isAnonymous = true).also { current = it }
    }

    override suspend fun signInWithApple(credential: AppleCredential): AuthUserState {
        appleSignIns += 1
        return AuthUserState(existingId, isAnonymous = false).also { current = it }
    }

    override suspend fun linkApple(credential: AppleCredential): AuthUserState {
        appleLinks += 1
        if (identityInUse) throw AuthLinkError.identityInUse
        val s = current ?: throw AuthLinkError.identityInUse
        return s.copy(isAnonymous = false).also { current = it }
    }

    override suspend fun sendSignInCode(email: String) = Unit

    override suspend fun verifySignInCode(email: String, code: String): AuthUserState =
        AuthUserState(existingId, isAnonymous = false, email = email).also { current = it }

    override suspend fun linkEmail(email: String) {
        if (identityInUse) throw AuthLinkError.identityInUse
        emailLinks.add(email)
    }

    override suspend fun verifyEmailLink(email: String, code: String): AuthUserState {
        val s = current ?: throw AuthLinkError.identityInUse
        return s.copy(isAnonymous = false, email = email).also { current = it }
    }

    override suspend fun signOut() {
        current = null
    }

    override suspend fun isProviderEnabled(provider: String): Boolean = if (provider == "apple") appleEnabled else true
}

internal val credential = AppleCredential(idToken = "token", nonce = "nonce")

internal suspend fun anonymousAuth(backend: FakeSecureAuthBackend): AuthService {
    val auth = AuthService(previewUserId = null, backend = backend)
    auth.signInAnonymously()
    return auth
}

class SecureAccountFlowTests {
    @get:Rule val main = MainDispatcherRule()

    @Test fun appleLinks() = runTest(main.dispatcher) {
        val backend = FakeSecureAuthBackend()
        val auth = anonymousAuth(backend)
        val flow = SecureAccountModel(auth)
        flow.apple(credential)
        assertEquals(SecureAccountModel.Outcome.linkedApple, flow.outcome)
        assertNull(flow.conflict)
        assertEquals(backend.anonymousId, auth.userId)
        assertFalse(auth.isAnonymous)
    }

    @Test fun cancelledAppleSheetDoesNothing() = runTest(main.dispatcher) {
        val backend = FakeSecureAuthBackend()
        val flow = SecureAccountModel(anonymousAuth(backend))
        flow.apple(null)
        assertNull(flow.outcome)
        assertNull(flow.error)
        assertEquals(0, backend.appleLinks)
    }

    @Test fun appleConflictCancelThenSignIn() = runTest(main.dispatcher) {
        val backend = FakeSecureAuthBackend(identityInUse = true)
        val auth = anonymousAuth(backend)
        val flow = SecureAccountModel(auth)

        flow.apple(credential)
        assertEquals(SecureAccountModel.Conflict.apple(credential), flow.conflict)
        assertNull(flow.outcome)
        assertTrue(auth.isAnonymous)

        flow.cancelConflict()
        assertNull(flow.conflict)
        assertEquals(SecureAccountModel.Stage.choose, flow.stage)
        assertEquals(backend.anonymousId, auth.userId)

        flow.apple(credential)
        flow.confirmConflict()
        assertEquals(SecureAccountModel.Outcome.signedInInstead, flow.outcome)
        assertEquals(backend.existingId, auth.userId)
        assertFalse(auth.isAnonymous)
        assertEquals(1, backend.appleSignIns)
    }

    @Test fun emailLinksWithTheCode() = runTest(main.dispatcher) {
        val backend = FakeSecureAuthBackend()
        val auth = anonymousAuth(backend)
        val flow = SecureAccountModel(auth)
        flow.useEmail()
        assertEquals(SecureAccountModel.Stage.email, flow.stage)
        flow.email = "nope"
        assertFalse(flow.emailValid)
        flow.email = " arif@example.com "
        flow.sendEmail()
        assertEquals(SecureAccountModel.Stage.code, flow.stage)
        assertEquals(listOf("arif@example.com"), backend.emailLinks)
        flow.code = "12a34 56789"
        assertEquals("123456", flow.code)
        flow.verify()
        assertEquals(SecureAccountModel.Outcome.linkedEmail, flow.outcome)
        assertEquals(backend.anonymousId, auth.userId)
        assertFalse(auth.isAnonymous)
    }

    @Test fun emailConflictSignsInWithACode() = runTest(main.dispatcher) {
        val backend = FakeSecureAuthBackend(identityInUse = true)
        val auth = anonymousAuth(backend)
        val flow = SecureAccountModel(auth)
        flow.useEmail()
        flow.email = "arif@example.com"
        flow.sendEmail()
        assertEquals(SecureAccountModel.Conflict.email("arif@example.com"), flow.conflict)

        flow.cancelConflict()
        assertEquals(SecureAccountModel.Stage.email, flow.stage)

        flow.sendEmail()
        flow.confirmConflict()
        assertEquals(SecureAccountModel.Stage.signInCode, flow.stage)
        assertTrue(auth.isAnonymous) // nothing is left behind until the code is entered
        flow.code = "654321"
        flow.verify()
        assertEquals(SecureAccountModel.Outcome.signedInInstead, flow.outcome)
        assertEquals(backend.existingId, auth.userId)
    }

    /**
     * Demo `AWSecureConflict`: signing in to the existing account loads that account (Sam & Alex) and
     * the normal gate applies (premium, onboarded → tabs).
     */
    @Test fun conflictSignInSwitchesAccountThroughTheGate() = runTest(main.dispatcher) {
        val backend = FakeSecureAuthBackend(anonymousId = PreviewData.anonId, existingId = PreviewData.meId, identityInUse = true)
        val paid = PreviewData.anonymousPaidModel()
        val m = AppModel(
            auth = AuthService(previewUserId = PreviewData.anonId, anonymous = true, backend = backend), store = paid.store,
            purchases = PurchasesService(), push = PushService(context = null), defaults = UserDefaults.inMemory(),
        )
        m.start()
        backend.signInAnonymously() // the fake's current session = the anonymous user
        assertEquals(AppGate.Destination.secureAccount, m.phase)
        val flow = SecureAccountModel(m.auth)
        flow.apple(credential)
        flow.confirmConflict()
        assertEquals(PreviewData.meId, m.auth.userId)
        assertEquals(PreviewData.meId, m.store.me?.id)
        assertEquals(PreviewData.meId, m.purchases.identifiedUserId)
        assertEquals(AppGate.Destination.tabs, m.phase)
    }
}
