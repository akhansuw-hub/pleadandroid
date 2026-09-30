// Port of ArgueWin/Services/AuthService.swift. Amendment az: on Android the account is secured with Sign in with
// Google (Credential Manager → Supabase `IDToken`), Sign in with Apple through the Supabase OAuth browser flow (so an
// account secured on iPhone restores here), or the unchanged email one-time code.
package app.plead.android.services

import android.app.Activity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.SignOutScope
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.exception.AuthErrorCode
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.handleDeeplinks
import io.github.jan.supabase.auth.providers.Apple
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserSession
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

// MARK: - Auth backend

/** What the app knows about the signed-in Supabase user. */
data class AuthUserState(
    val id: UUID,
    /** JWT `is_anonymous`: created silently at THAT'S ME (amendment p), until an identity is linked. */
    val isAnonymous: Boolean,
    val email: String? = null,
)

/** A native Sign in with Apple result: the identity token plus the raw nonce whose hash Apple signed. */
data class AppleCredential(val idToken: String, val nonce: String?, val givenName: String? = null)

/** A Sign in with Google result (Credential Manager): the Google ID token plus the raw nonce whose hash it carries. */
data class GoogleCredential(val idToken: String, val nonce: String?, val givenName: String? = null)

/**
 * Linking onto the anonymous user failed because that Apple ID / Google account / email already belongs to another
 * Plead account (`identity_already_exists` / `email_exists`). [providerUnavailable] (Android only): the Supabase
 * project has no Apple / Google provider configured, so that button degrades to an explanation (amendment az).
 */
sealed class AuthLinkError(message: String) : Exception(message) {
    data object identityInUse : AuthLinkError("That sign-in already belongs to another Plead account.")
    data object providerUnavailable : AuthLinkError("That sign-in option isn't available right now. Use another one.")
}

/** Swift `AuthError.sessionMissing`. */
class AuthSessionMissing : Exception("No session")

/**
 * Everything [AuthService] asks of Supabase Auth. Live: [SupabaseAuthBackend]. Previews, tests and
 * the demo harness: [LocalAuthBackend] (no network).
 */
interface AuthBackend {
    suspend fun signInAnonymously(): AuthUserState
    suspend fun signInWithApple(credential: AppleCredential): AuthUserState

    /** Adds the Apple identity to the current (anonymous) user. The uid never changes. */
    suspend fun linkApple(credential: AppleCredential): AuthUserState
    suspend fun sendSignInCode(email: String)
    suspend fun verifySignInCode(email: String, code: String): AuthUserState

    /** Sets the email on the current (anonymous) user; Supabase emails a link + 6-digit code. */
    suspend fun linkEmail(email: String)

    /** Confirms that email (`email_change` OTP): the anonymous user becomes a permanent one, same uid. */
    suspend fun verifyEmailLink(email: String, code: String): AuthUserState
    suspend fun signOut()

    // Android (amendment az). Defaults make an in-memory backend treat Google exactly like Apple.

    /** Sign in with Google to an existing account (the conflict path). */
    suspend fun signInWithGoogle(credential: GoogleCredential): AuthUserState =
        signInWithApple(AppleCredential(credential.idToken, credential.nonce, credential.givenName))

    /** Adds the Google identity to the current (anonymous) user (`POST /token?grant_type=id_token`, link_identity). */
    suspend fun linkGoogle(credential: GoogleCredential): AuthUserState =
        linkApple(AppleCredential(credential.idToken, credential.nonce, credential.givenName))

    /**
     * Sign in with Apple on Android: the Supabase OAuth browser flow. `link` = link the Apple identity onto the
     * current anonymous user (`linkIdentity`), else sign in to that Apple account. The result arrives later through
     * `plead://login-callback` ([AuthService.handleAuthCallback]); an in-memory backend completes at once and returns
     * the new state.
     */
    suspend fun appleOAuth(link: Boolean): AuthUserState? =
        if (link) linkApple(AppleCredential("oauth", null)) else signInWithApple(AppleCredential("oauth", null))

    /** Whether the dashboard has this external provider turned on (`GET /auth/v1/settings`). */
    suspend fun isProviderEnabled(provider: String): Boolean = true
}

class SupabaseAuthBackend(val client: SupabaseClient) : AuthBackend {

    override suspend fun signInAnonymously(): AuthUserState {
        client.auth.signInAnonymously()
        return currentState() ?: throw AuthSessionMissing()
    }

    override suspend fun signInWithApple(credential: AppleCredential): AuthUserState {
        client.auth.signInWith(IDToken) {
            idToken = credential.idToken
            provider = Apple
            nonce = credential.nonce
        }
        return currentState() ?: throw AuthSessionMissing()
    }

    override suspend fun linkApple(credential: AppleCredential): AuthUserState = mapped {
        // Native flow: `POST /token?grant_type=id_token` with `link_identity: true` on the current session.
        client.auth.linkIdentityWithIdToken(Apple, credential.idToken) { nonce = credential.nonce }
        currentState() ?: throw AuthSessionMissing()
    }

    override suspend fun signInWithGoogle(credential: GoogleCredential): AuthUserState {
        client.auth.signInWith(IDToken) {
            idToken = credential.idToken
            provider = Google
            nonce = credential.nonce
        }
        return currentState() ?: throw AuthSessionMissing()
    }

    override suspend fun linkGoogle(credential: GoogleCredential): AuthUserState = mapped {
        client.auth.linkIdentityWithIdToken(Google, credential.idToken) { nonce = credential.nonce }
        currentState() ?: throw AuthSessionMissing()
    }

    override suspend fun appleOAuth(link: Boolean): AuthUserState? {
        if (!isProviderEnabled("apple")) throw AuthLinkError.providerUnavailable
        mapped {
            if (link) client.auth.linkIdentity(Apple, redirectUrl = AppConfig.authRedirectURL)
            else client.auth.signInWith(Apple, redirectUrl = AppConfig.authRedirectURL)
        }
        // The browser owns the flow now; the session arrives through `plead://login-callback`.
        return null
    }

    override suspend fun isProviderEnabled(provider: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL(AppConfig.supabaseURL.toString().trimEnd('/') + "/auth/v1/settings")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                setRequestProperty("apikey", AppConfig.supabaseAnonKey)
                connectTimeout = 8_000
                readTimeout = 8_000
            }
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            val settings = JSONCoding.json.parseToJsonElement(text) as? JsonObject
            val external = settings?.get("external") as? JsonObject
            (external?.get(provider) as? JsonPrimitive)?.booleanOrNull ?: false
        }.getOrDefault(true) // Offline: let the provider's own error speak.
    }

    override suspend fun sendSignInCode(email: String) {
        client.auth.signInWith(OTP, redirectUrl = AppConfig.authRedirectURL) {
            this.email = email
            createUser = false
        }
    }

    override suspend fun verifySignInCode(email: String, code: String): AuthUserState {
        client.auth.verifyEmailOtp(type = OtpType.Email.EMAIL, email = email, token = code)
        return currentState() ?: throw AuthSessionMissing()
    }

    override suspend fun linkEmail(email: String) {
        mapped { client.auth.updateUser(redirectUrl = AppConfig.authRedirectURL) { this.email = email } }
    }

    override suspend fun verifyEmailLink(email: String, code: String): AuthUserState = mapped {
        client.auth.verifyEmailOtp(type = OtpType.Email.EMAIL_CHANGE, email = email, token = code)
        currentState() ?: throw AuthSessionMissing()
    }

    override suspend fun signOut() {
        client.auth.signOut()
    }

    private fun currentState(): AuthUserState? = client.auth.currentSessionOrNull()?.let(::authUserState)

    companion object {
        /** `identity_already_exists` (Apple / Google) / `email_exists` (email) → [AuthLinkError.identityInUse]. */
        fun mapped(error: Throwable): Throwable {
            val e = error as? AuthRestException ?: return error
            val code = e.errorCode
            if (code == AuthErrorCode.IdentityAlreadyExists || code == AuthErrorCode.EmailExists || code == AuthErrorCode.UserAlreadyExists) {
                return AuthLinkError.identityInUse
            }
            if (e.error == "validation_failed" && (e.errorDescription ?: "").contains("provider is not enabled", ignoreCase = true)) {
                return AuthLinkError.providerUnavailable
            }
            return error
        }

        private suspend inline fun <T> mapped(block: () -> T): T = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw mapped(e)
        }

        /** The Supabase session as the app's user state (anonymous from the JWT `is_anonymous` claim). */
        fun authUserState(session: UserSession): AuthUserState? {
            val id = parseUUID(session.user?.id) ?: jwtClaims(session.accessToken)?.let { parseUUID((it["sub"] as? JsonPrimitive)?.content) }
                ?: return null
            val anonymous = (jwtClaims(session.accessToken)?.get("is_anonymous") as? JsonPrimitive)?.booleanOrNull ?: false
            return AuthUserState(id = id, isAnonymous = anonymous, email = session.user?.email?.takeIf { it.isNotEmpty() })
        }

        /** Decodes a JWT's payload (no verification: the server already did). */
        fun jwtClaims(token: String): JsonObject? = runCatching {
            val part = token.split(".")[1]
            val bytes = Base64.getUrlDecoder().decode(part.padEnd((part.length + 3) / 4 * 4, '='))
            JSONCoding.json.parseToJsonElement(bytes.decodeToString()) as? JsonObject
        }.getOrNull()
    }
}

/**
 * In-memory auth for previews, tests and `AWDemo` (no backend): every call succeeds instantly.
 * `identityInUse` makes linking fail as if the Apple ID / email already had an account
 * (`existingAccountId`), which "sign in instead" then switches to.
 */
class LocalAuthBackend(
    private var current: AuthUserState?,
    private val anonymousId: UUID = UUID.randomUUID(),
    private val existingAccountId: UUID = UUID.randomUUID(),
    private val identityInUse: Boolean = false,
) : AuthBackend {
    private val lock = Mutex()
    private var pendingEmail: String? = null

    override suspend fun signInAnonymously(): AuthUserState = lock.withLock {
        val s = AuthUserState(id = anonymousId, isAnonymous = true, email = null)
        current = s
        s
    }

    override suspend fun signInWithApple(credential: AppleCredential): AuthUserState = lock.withLock {
        val s = AuthUserState(id = if (identityInUse) existingAccountId else (current?.id ?: existingAccountId), isAnonymous = false, email = null)
        current = s
        s
    }

    override suspend fun linkApple(credential: AppleCredential): AuthUserState = lock.withLock {
        val s = current ?: throw AuthSessionMissing()
        if (identityInUse) throw AuthLinkError.identityInUse
        val linked = s.copy(isAnonymous = false)
        current = linked
        linked
    }

    override suspend fun sendSignInCode(email: String) {
        lock.withLock { pendingEmail = email }
    }

    override suspend fun verifySignInCode(email: String, code: String): AuthUserState = lock.withLock {
        val s = AuthUserState(id = existingAccountId, isAnonymous = false, email = email)
        current = s
        s
    }

    override suspend fun linkEmail(email: String) = lock.withLock {
        if (current == null) throw AuthSessionMissing()
        if (identityInUse) throw AuthLinkError.identityInUse
        pendingEmail = email
    }

    override suspend fun verifyEmailLink(email: String, code: String): AuthUserState = lock.withLock {
        val s = current ?: throw AuthSessionMissing()
        val linked = s.copy(isAnonymous = false, email = email)
        current = linked
        linked
    }

    override suspend fun signOut() = lock.withLock { current = null }
}

// MARK: - AuthService

/**
 * Supabase Auth for the app (amendment p: anonymous-first). THAT'S ME creates an anonymous user;
 * SecureAccountView links Google / Apple / an email onto it, keeping the uid (amendment az).
 * `userId` is the single source of truth for "has a session"; `isAnonymous` says whether it is secured.
 * Every property is Compose snapshot state (Swift `@Observable`); call from the main thread.
 */
class AuthService private constructor(
    private val client: SupabaseClient?,
    private val backend: AuthBackend,
    previewUserId: UUID?,
    anonymous: Boolean,
    resolved: Boolean,
) {
    var userId: UUID? by mutableStateOf(previewUserId)
        private set
    var email: String? by mutableStateOf(null)
        private set

    /** The session belongs to an anonymous user (no Apple ID / Google / email linked yet). */
    var isAnonymous: Boolean by mutableStateOf(previewUserId != null && anonymous)
        private set

    /** False until the first auth event arrives (drives the launch splash; never flash sign-in). */
    var isResolved: Boolean by mutableStateOf(resolved)
        private set

    /**
     * Called whenever the signed-in user changes (sign in, sign out, account switch). Never on a link:
     * linking keeps the uid.
     */
    var onUserChange: (suspend (UUID?) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listenJob: Job? = null

    /** `onUserChange` calls run one after another; callers that need the account loaded await the latest. */
    private var changeTask: Deferred<Unit>? = null

    /** Raw nonce for the in-flight Google request (the hashed one goes to Google). */
    private var currentNonce: String? = null

    /** The address a link / sign-in code was sent to. */
    var pendingEmail: String? = null
        private set

    private val _linkConflicts = MutableSharedFlow<AuthLinkError>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * Android: an OAuth browser flow (Sign in with Apple) came back with an error through `plead://login-callback`
     * (`identity_already_exists` → [AuthLinkError.identityInUse]). SecureAccountView shows the conflict sheet.
     */
    val linkConflicts: SharedFlow<AuthLinkError> = _linkConflicts.asSharedFlow()

    /** Live: the process-wide Supabase client (or none, which behaves like an empty in-memory backend). */
    constructor(client: SupabaseClient? = if (SupabaseService.isConfigured) SupabaseService.shared else null) : this(
        client = client,
        backend = client?.let(::SupabaseAuthBackend) ?: LocalAuthBackend(current = null),
        previewUserId = null,
        anonymous = false,
        resolved = client == null,
    )

    /** Preview / test / demo initialiser: a fixed starting state and an in-memory backend. */
    constructor(previewUserId: UUID?, anonymous: Boolean = false, backend: AuthBackend? = null) : this(
        client = null,
        backend = backend ?: LocalAuthBackend(current = previewUserId?.let { AuthUserState(it, anonymous, null) }),
        previewUserId = previewUserId,
        anonymous = anonymous,
        resolved = true,
    )

    fun start() {
        val client = client ?: return
        if (listenJob != null) return
        listenJob = scope.launch {
            // A session stored on the device can outlive its account (deleted server-side or from another device);
            // every call then fails "invalid or expired session" with no way out. Validate a restored session
            // against the server once per launch and purge it locally when the server rejects it — only on an auth
            // error, never a network failure, so offline launches keep their session.
            runCatching { client.auth.awaitInitialization() }
            if (client.auth.currentSessionOrNull() != null) {
                try {
                    client.auth.retrieveUserForCurrentSession(updateSession = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AuthRestException) {
                    runCatching { client.auth.signOut(SignOutScope.LOCAL) }
                } catch (_: Exception) {
                }
            }
            client.auth.sessionStatus.collect { status ->
                when (status) {
                    // supabase-kt refreshes an expired stored session itself (iOS drops an expired initial session).
                    is SessionStatus.Authenticated -> apply(SupabaseAuthBackend.authUserState(status.session))
                    is SessionStatus.NotAuthenticated -> apply(null)
                    is SessionStatus.RefreshFailure -> if (!isResolved) apply(null) // offline launch without a usable session
                    is SessionStatus.Initializing -> Unit
                }
            }
        }
    }

    /**
     * Mirrors a new auth state. A different uid runs `onUserChange` (serialised); the same uid (token
     * refresh, identity linked) only updates `isAnonymous` / `email`.
     */
    private suspend fun apply(state: AuthUserState?) {
        val newId = state?.id
        val changed = newId != userId || !isResolved
        userId = newId
        email = state?.email
        isAnonymous = state?.isAnonymous ?: false
        if (changed) {
            val previous = changeTask
            changeTask = scope.async {
                previous?.await()
                onUserChange?.invoke(newId)
                Unit
            }
        }
        changeTask?.await()
        isResolved = true
    }

    // MARK: Anonymous (amendment p)

    /**
     * THAT'S ME with no session: creates the anonymous user. Returns once the account is loaded
     * (RevenueCat is identified with the uid before this returns, so a purchase attaches to it).
     */
    suspend fun signInAnonymously() {
        if (userId != null) return
        apply(backend.signInAnonymously())
    }

    // MARK: Sign in with Google (amendment az)

    /**
     * Credential Manager's Google sheet, with a hashed nonce. Returns null when the user cancelled; throws
     * [AuthLinkError.providerUnavailable] when no Google web client id is configured (`GOOGLE_WEB_CLIENT_ID`) or the
     * device has no Google account to offer.
     */
    suspend fun googleCredential(activity: Activity): GoogleCredential? {
        val clientId = AppConfig.googleWebClientID ?: throw AuthLinkError.providerUnavailable
        val nonce = randomNonce()
        currentNonce = nonce
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(clientId)
            .setFilterByAuthorizedAccounts(false)
            .setNonce(sha256(nonce))
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        return try {
            val result = CredentialManager.create(activity).getCredential(activity, request)
            val google = GoogleIdTokenCredential.createFrom(result.credential.data)
            GoogleCredential(idToken = google.idToken, nonce = currentNonce, givenName = google.givenName)
        } catch (e: GetCredentialCancellationException) {
            null
        } catch (e: NoCredentialException) {
            throw AuthLinkError.providerUnavailable
        } finally {
            currentNonce = null
        }
    }

    /** Links the Google account onto the current anonymous user. Same uid. Throws [AuthLinkError.identityInUse]. */
    suspend fun linkGoogle(credential: GoogleCredential) {
        apply(backend.linkGoogle(credential))
    }

    /** "Sign in to that account instead" for Google. */
    suspend fun signInInstead(google: GoogleCredential) {
        apply(backend.signInWithGoogle(google))
    }

    // MARK: Sign in with Apple

    /**
     * Links the Apple ID onto the current anonymous user (`linkIdentityWithIdToken`). The uid, and so
     * the profile, couple and RevenueCat app user id, stay the same. Throws [AuthLinkError.identityInUse].
     */
    suspend fun linkApple(credential: AppleCredential) {
        apply(backend.linkApple(credential))
    }

    /**
     * Android's Sign in with Apple: the Supabase OAuth browser flow (amendment az). `link` = secure the anonymous
     * account; else sign in to the Apple account ("sign in instead"). The live flow completes later through
     * `plead://login-callback`; the in-memory backend completes at once. Throws [AuthLinkError.providerUnavailable]
     * when the dashboard has no Apple provider.
     */
    suspend fun appleOAuth(link: Boolean) {
        val state = backend.appleOAuth(link)
        if (state != null) apply(state)
    }

    /** Whether Sign in with Apple can be offered (the Supabase Apple provider is on). */
    suspend fun isAppleAvailable(): Boolean = backend.isProviderEnabled("apple")

    // MARK: Email

    /** Sets the email on the current anonymous user: Supabase sends a magic link + 6-digit code. */
    suspend fun linkEmail(address: String) {
        backend.linkEmail(address)
        pendingEmail = address
    }

    /** The 6-digit code from that email (`email_change`). Same uid afterwards. */
    suspend fun verifyEmailLink(code: String) {
        val pendingEmail = pendingEmail ?: throw AuthSessionMissing()
        apply(backend.verifyEmailLink(pendingEmail, code))
    }

    // MARK: Signing in to an existing account (identity conflict)

    /**
     * "Sign in to that account instead": the anonymous session is replaced by the existing account
     * (the anonymous user and its couple are left behind). The normal gate applies afterwards.
     */
    suspend fun signInInstead(apple: AppleCredential) {
        apply(backend.signInWithApple(apple))
    }

    /** Emails a sign-in code for an existing account (the email conflict path). */
    suspend fun sendSignInCode(address: String) {
        backend.sendSignInCode(address)
        pendingEmail = address
    }

    suspend fun verifySignInCode(code: String) {
        val pendingEmail = pendingEmail ?: throw AuthSessionMissing()
        apply(backend.verifySignInCode(pendingEmail, code))
    }

    /**
     * Handles `plead://login-callback?...` from the magic link or an OAuth browser flow (sign-in, email link, Apple
     * link). Returns true if consumed.
     */
    suspend fun handleAuthCallback(url: URI): Boolean {
        val client = client ?: return false
        if (url.scheme != AppConfig.authRedirectScheme || url.host != AppConfig.authRedirectHost) return false
        val query = parameters(url.rawQuery)
        val fragment = parameters(url.rawFragment)
        val errorCode = query["error_code"] ?: fragment["error_code"]
        if (errorCode != null) {
            if (errorCode == "identity_already_exists" || errorCode == "email_exists" || errorCode == "user_already_exists") {
                _linkConflicts.tryEmit(AuthLinkError.identityInUse)
            } else if ((query["error_description"] ?: fragment["error_description"] ?: "").contains("provider is not enabled", ignoreCase = true)) {
                _linkConflicts.tryEmit(AuthLinkError.providerUnavailable)
            }
            return true
        }
        runCatching {
            val code = query["code"]
            if (code != null) {
                client.auth.exchangeCodeForSession(code)
            } else if (url.rawFragment != null) {
                // Implicit-flow tokens in the fragment: supabase-kt's own deep-link handler imports them.
                client.handleDeeplinks(android.content.Intent(android.content.Intent.ACTION_VIEW, url.toString().toUri()))
            }
        }
        // A link keeps the uid (no auth-state change): refresh the anonymous flag / email from the new session.
        client.auth.currentSessionOrNull()?.let(SupabaseAuthBackend::authUserState)?.let { apply(it) }
        return true
    }

    suspend fun signOut() {
        runCatching { backend.signOut() }
        pendingEmail = null
        apply(null)
    }

    /** Current access token for authorised calls outside the SDK (none currently). */
    val accessToken: String? get() = client?.auth?.currentAccessTokenOrNull()

    // MARK: Nonce helpers

    companion object {
        fun randomNonce(length: Int = 32): String {
            val charset = "0123456789ABCDEFGHIJKLMNOPQRSTUVXYZabcdefghijklmnopqrstuvwxyz-._"
            val random = SecureRandom()
            return (0 until length).map { charset[random.nextInt(charset.length)] }.joinToString("")
        }

        fun sha256(input: String): String =
            MessageDigest.getInstance("SHA-256").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }

        private fun parameters(raw: String?): Map<String, String> {
            if (raw.isNullOrEmpty()) return emptyMap()
            return raw.split("&").mapNotNull { part ->
                val i = part.indexOf('=')
                if (i <= 0) null else URLDecoder.decode(part.substring(0, i), "UTF-8") to URLDecoder.decode(part.substring(i + 1), "UTF-8")
            }.toMap()
        }
    }
}
