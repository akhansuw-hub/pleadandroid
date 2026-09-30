// Port of ArgueWin/Features/Account/SecureAccountView.swift: "Secure your court record" (amendment p), with the
// Android sign-in options of amendment az: Sign in with Google (Credential Manager → Supabase, first), Sign in
// with Apple through the Supabase OAuth browser flow (hidden when the dashboard has no Apple provider), and the
// unchanged email one-time code.
package app.plead.android.features.account

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.plead.android.app.AppModel
import app.plead.android.app.DemoHarness
import app.plead.android.designsystem.AvatarPair
import app.plead.android.designsystem.InlineError
import app.plead.android.designsystem.PleadSpacing
import app.plead.android.designsystem.SFSymbol
import app.plead.android.designsystem.TextStyleKind
import app.plead.android.designsystem.monospacedDigit
import app.plead.android.features.onboarding.OnboardingFieldLabel
import app.plead.android.features.onboarding.OnboardingPage
import app.plead.android.features.onboarding.OnboardingPalette
import app.plead.android.features.onboarding.OnboardingPrimaryButton
import app.plead.android.features.onboarding.OnboardingRadius
import app.plead.android.features.onboarding.OnboardingSecondaryButton
import app.plead.android.features.onboarding.OnboardingSubtitle
import app.plead.android.features.onboarding.OnboardingTitle
import app.plead.android.models.Avatar
import app.plead.android.services.Analytics
import app.plead.android.services.AppleCredential
import app.plead.android.services.AuthLinkError
import app.plead.android.services.AuthService
import app.plead.android.services.GoogleCredential
import kotlinx.coroutines.launch

// MARK: - State machine

/**
 * SecureAccountView's flow (amendment p, amendment az on Android), kept free of views so it is unit-tested with a
 * fake auth:
 *
 *     choose ──Google──▶ linked                          (linkIdentityWithIdToken, same uid)
 *       │  │  └─ identity in use ─▶ conflict(google) ─Sign in─▶ signed in to that account
 *       │  ├─Apple (OAuth)─▶ linked                      (linkIdentity in the browser, same uid)
 *       │  │  └─ identity in use ─▶ conflict(apple) ─Sign in─▶ signed in to that account
 *       │  │                                  └─Cancel─▶ choose
 *       └─email─▶ email ─send─▶ code ─verify─▶ linked   (updateUser(email) + email_change OTP)
 *                   └─ email in use ─▶ conflict(email) ─Sign in─▶ signInCode ─verify─▶ signed in
 *                                               └─Cancel─▶ email
 */
class SecureAccountModel(private val auth: AuthService) {
    enum class Stage {
        /** Sign in with Google (primary), Sign in with Apple + "Use email instead". */
        choose,
        /** Entering the email to link. */
        email,
        /** 6-digit code that links the email onto this (anonymous) account. */
        code,
        /** 6-digit code that signs in to the existing account (email conflict → Sign in). */
        signInCode,
    }

    sealed class Conflict {
        /** Apple: a native-style credential (in-memory / tests), or null for the OAuth browser flow. */
        data class apple(val credential: AppleCredential?) : Conflict()
        data class google(val credential: GoogleCredential) : Conflict()
        data class email(val address: String) : Conflict()

        val provider: String
            get() = when (this) {
                is apple -> "apple"
                is google -> "google"
                is email -> "email"
            }

        val message: String
            get() = when (this) {
                is apple -> "That Apple ID already has a Plead account. Sign in to it instead? Your current setup on this phone will be left behind."
                is google -> "That Google account already has a Plead account. Sign in to it instead? Your current setup on this phone will be left behind."
                is email -> "That email already has a Plead account. Sign in to it instead? Your current setup on this phone will be left behind."
            }
    }

    enum class Outcome { linkedApple, linkedGoogle, linkedEmail, signedInInstead }

    var stage: Stage by mutableStateOf(Stage.choose)
        private set
    var email: String by mutableStateOf("")

    private var _code by mutableStateOf("")
    var code: String
        get() = _code
        set(value) {
            _code = value.filter { it.isDigit() }.take(6)
        }

    var busy: Boolean by mutableStateOf(false)
        private set
    var error: String? by mutableStateOf(null)
        private set

    /** Drives the "already has a Plead account" sheet. */
    var conflict: Conflict? by mutableStateOf(null)
    var outcome: Outcome? by mutableStateOf(null)
        private set

    /** "Sign in instead" couldn't reuse the Apple credential: the next Apple tap signs in rather than links. */
    var appleSignsIn: Boolean by mutableStateOf(false)
        private set

    /** The same for Google. */
    var googleSignsIn: Boolean by mutableStateOf(false)
        private set

    private var shownTracked = false

    val emailValid: Boolean
        get() {
            val e = email.trim(' ')
            val at = e.indexOf('@')
            if (at < 0) return false
            return e.length >= 5 && e.substring(at + 1).contains('.')
        }

    val codeComplete: Boolean get() = code.length == 6
    private val trimmedEmail: String get() = email.trim(' ')

    fun shown(reason: String) {
        if (shownTracked) return
        shownTracked = true
        Analytics.track("secure_account_shown", mapOf("reason" to reason))
    }

    // MARK: Google

    /** The Credential Manager sheet returned a credential (null = cancelled): link it, or sign in after a conflict. */
    suspend fun google(credential: GoogleCredential?) {
        if (credential == null || busy) return
        if (googleSignsIn) return signIn(google = credential)
        busy = true
        error = null
        try {
            auth.linkGoogle(credential)
            outcome = Outcome.linkedGoogle
            Analytics.track("linked_google")
        } catch (e: AuthLinkError.identityInUse) {
            conflict = Conflict.google(credential)
            Analytics.track("identity_conflict", mapOf("provider" to "google"))
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = googleError
        } finally {
            busy = false
        }
    }

    /** Credential Manager itself failed (no Google account on the device, no web client id configured…). */
    fun googleFailed(unavailable: Boolean = false) {
        error = if (unavailable) AuthLinkError.providerUnavailable.message else googleError
    }

    private suspend fun signIn(google: GoogleCredential) {
        busy = true
        error = null
        try {
            auth.signInInstead(google)
            googleSignsIn = false
            outcome = Outcome.signedInInstead
            Analytics.track("conflict_signed_in", mapOf("provider" to "google"))
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            // The token may have expired: a fresh Google sheet signs in (rather than links) next time.
            googleSignsIn = true
            stage = Stage.choose
            error = "Tap Sign in with Google again to sign in to your account."
        } finally {
            busy = false
        }
    }

    // MARK: Apple

    /**
     * The Apple credential (null = cancelled): link it, or sign in after a conflict. Android reaches this only with
     * an in-memory backend (demo / tests); the live app uses [appleOAuth].
     */
    suspend fun apple(credential: AppleCredential?) {
        if (credential == null || busy) return
        if (appleSignsIn) return signIn(apple = credential)
        busy = true
        error = null
        try {
            auth.linkApple(credential)
            outcome = Outcome.linkedApple
            Analytics.track("linked_apple")
        } catch (e: AuthLinkError.identityInUse) {
            conflict = Conflict.apple(credential)
            Analytics.track("identity_conflict", mapOf("provider" to "apple"))
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = appleError
        } finally {
            busy = false
        }
    }

    /**
     * Sign in with Apple on Android (amendment az): the Supabase OAuth browser flow. Links the Apple ID onto the
     * anonymous user (or signs in, after a conflict). The live flow finishes later through `plead://login-callback`:
     * the gate moves on once the session is no longer anonymous, and a conflict comes back through
     * [linkConflictReceived]. An in-memory backend completes here.
     */
    suspend fun appleOAuth() {
        if (busy) return
        val signingIn = appleSignsIn
        busy = true
        error = null
        try {
            auth.appleOAuth(link = !signingIn)
            if (auth.userId != null && !auth.isAnonymous) {
                if (signingIn) {
                    appleSignsIn = false
                    outcome = Outcome.signedInInstead
                    Analytics.track("conflict_signed_in", mapOf("provider" to "apple"))
                } else {
                    outcome = Outcome.linkedApple
                    Analytics.track("linked_apple")
                }
            }
        } catch (e: AuthLinkError.identityInUse) {
            conflict = Conflict.apple(null)
            Analytics.track("identity_conflict", mapOf("provider" to "apple"))
        } catch (e: AuthLinkError.providerUnavailable) {
            error = e.message
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = appleError
        } finally {
            busy = false
        }
    }

    /**
     * `plead://login-callback` answered `identity_already_exists` (AuthService.linkConflicts): the browser link could
     * not attach the identity. On the email stages it is the email link; otherwise the Apple OAuth link.
     */
    fun linkConflictReceived(error: AuthLinkError) {
        if (error != AuthLinkError.identityInUse) {
            this.error = error.message
            return
        }
        conflict = when (stage) {
            Stage.email, Stage.code, Stage.signInCode -> Conflict.email(trimmedEmail)
            Stage.choose -> Conflict.apple(null)
        }
        Analytics.track("identity_conflict", mapOf("provider" to conflict!!.provider))
    }

    fun appleFailed() {
        error = appleError
    }

    // MARK: Email

    fun useEmail() {
        error = null
        stage = Stage.email
    }

    fun backToChoose() {
        error = null
        stage = Stage.choose
    }

    fun useDifferentEmail() {
        error = null
        code = ""
        stage = Stage.email
    }

    suspend fun sendEmail() {
        if (!emailValid || busy) return
        busy = true
        error = null
        try {
            auth.linkEmail(trimmedEmail)
            code = ""
            stage = Stage.code
        } catch (e: AuthLinkError.identityInUse) {
            conflict = Conflict.email(trimmedEmail)
            Analytics.track("identity_conflict", mapOf("provider" to "email"))
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = "We couldn't send the email. Check the address and try again."
        } finally {
            busy = false
        }
    }

    suspend fun verify() {
        if (!codeComplete || busy) return
        busy = true
        error = null
        try {
            if (stage == Stage.signInCode) {
                auth.verifySignInCode(code)
                outcome = Outcome.signedInInstead
                Analytics.track("conflict_signed_in", mapOf("provider" to "email"))
            } else {
                auth.verifyEmailLink(code)
                outcome = Outcome.linkedEmail
                Analytics.track("linked_email")
            }
        } catch (e: AuthLinkError.identityInUse) {
            conflict = Conflict.email(trimmedEmail)
            Analytics.track("identity_conflict", mapOf("provider" to "email"))
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = "That code didn't work. It may have expired, so request a new one."
        } finally {
            busy = false
        }
    }

    // MARK: Conflict

    /** "Sign in": leave the anonymous setup behind and sign in to the account that owns the identity. */
    suspend fun confirmConflict() {
        val conflict = conflict ?: return
        if (busy) return
        this.conflict = null
        when (conflict) {
            is Conflict.apple -> {
                val credential = conflict.credential
                if (credential != null) {
                    signIn(apple = credential)
                } else {
                    appleSignsIn = true
                    appleOAuth()
                }
            }
            is Conflict.google -> signIn(google = conflict.credential)
            is Conflict.email -> {
                busy = true
                error = null
                try {
                    auth.sendSignInCode(conflict.address)
                    code = ""
                    stage = Stage.signInCode
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = "We couldn't send the email. Check the address and try again."
                } finally {
                    busy = false
                }
            }
        }
    }

    fun cancelConflict() {
        val conflict = conflict ?: return
        this.conflict = null
        stage = if (conflict is Conflict.email) Stage.email else Stage.choose
    }

    private suspend fun signIn(apple: AppleCredential) {
        busy = true
        error = null
        try {
            auth.signInInstead(apple)
            appleSignsIn = false
            outcome = Outcome.signedInInstead
            Analytics.track("conflict_signed_in", mapOf("provider" to "apple"))
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            // The token may have expired: a fresh Apple sheet signs in (rather than links) next time.
            appleSignsIn = true
            stage = Stage.choose
            error = "Tap Sign in with Apple again to sign in to your account."
        } finally {
            busy = false
        }
    }

    /** Demo / screenshots: open on a given stage (DEBUG). */
    fun demoSet(stage: Stage, email: String = "", code: String = "") {
        this.stage = stage
        this.email = email
        this.code = code
    }

    companion object {
        const val appleError = "Sign in with Apple didn't work. Please try again."
        const val googleError = "Sign in with Google didn't work. Please try again."

        /** `AWSecureStage email|code|conflict` opens on that stage / the conflict sheet (screenshots). */
        fun applyDemo(m: SecureAccountModel, stage: String? = DemoHarness.secureStage) {
            when (stage) {
                "email" -> m.demoSet(Stage.email, email = "arif@example.com")
                "code" -> m.demoSet(Stage.code, email = "arif@example.com", code = "")
                "conflict" -> m.conflict = Conflict.apple(AppleCredential(idToken = "demo", nonce = null))
                else -> Unit
            }
        }
    }
}

// MARK: - View

/** Every user-visible string of the screen (tests check brand / pricing words against it). */
object SecureAccountCopy {
    const val title = "Secure your court record"
    const val subtitle = "Your cases, your partner link and your subscription stay with you on any phone."
    const val google = "Sign in with Google"
    const val apple = "Sign in with Apple"
    const val useEmail = "Use email instead"
    const val footnote = "One tap. We never post on your behalf."
    const val sendLink = "Send link"
    const val backToChoose = "Back to Sign in with Google"
    const val signIn = "Sign in"
    const val secure = "Secure my record"
    const val differentEmail = "Use a different email"
    const val emailLabel = "Your email"
    const val emailPlaceholder = "you@example.com"
    const val emailNote = "We'll email you a link and a 6-digit code."
    const val signInCodeLabel = "Sign-in code"
    const val codeLabel = "6-digit code"
    const val codePlaceholder = "123456"
    const val conflictTitle = "You already have an account"
    const val cancel = "Cancel"

    fun check(email: String) = "Check $email. Tap the link on this phone, or enter the code."

    val all: List<String>
        get() = listOf(
            title, subtitle, google, apple, useEmail, footnote, sendLink, backToChoose, signIn, secure, differentEmail,
            emailLabel, emailNote, signInCodeLabel, codeLabel, conflictTitle, cancel, check("you@example.com"),
            SecureAccountModel.appleError, SecureAccountModel.googleError,
        ) + listOf(
            SecureAccountModel.Conflict.apple(null), SecureAccountModel.Conflict.email("a@b.co"),
        ).map { it.message }
}

/**
 * "Secure your court record" (amendment p): a full-screen root state shown once the gate resolves
 * (purchase / restore, or the partner paid) while the session is anonymous. Not skippable: no close.
 * Sign in with Google / Apple links that identity onto the anonymous user; email links an address with the
 * 6-digit code. Success hands back to the gate (the tabs).
 */
@Composable
fun SecureAccountView(app: AppModel, modifier: Modifier = Modifier) {
    val auth = app.auth
    val store = app.store
    val model = remember(auth) { SecureAccountModel(auth).also { SecureAccountModel.applyDemo(it) } }
    LaunchedEffect(model) { model.shown(reason = if (app.identityRequired) "identity_required" else "gate") }
    // OAuth callbacks that could not attach the identity (`plead://login-callback?error_code=identity_already_exists`).
    LaunchedEffect(model) { auth.linkConflicts.collect { model.linkConflictReceived(it) } }
    // Amendment az: Apple is offered only when the Supabase Apple provider is on.
    var appleAvailable by remember { mutableStateOf(false) }
    LaunchedEffect(auth) { appleAvailable = runCatching { auth.isAppleAvailable() }.getOrDefault(false) }

    Box(modifier.fillMaxSize().background(OnboardingPalette.cream)) {
        SecureAccountContent(
            model = model,
            me = store.me?.avatar,
            partner = if (store.couple?.isLinked == true) store.partner?.avatar else null,
            // No backend (demo harness / previews): the buttons "succeed" through the in-memory auth.
            localAuth = store.backend == null,
            appleAvailable = appleAvailable,
            auth = auth,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SecureAccountContent(
    model: SecureAccountModel,
    me: Avatar?,
    partner: Avatar?,
    localAuth: Boolean,
    appleAvailable: Boolean,
    auth: AuthService,
) {
    val scope = rememberCoroutineScope()
    val emailFocus = remember { FocusRequester() }
    val codeFocus = remember { FocusRequester() }
    LaunchedEffect(model.stage) {
        runCatching {
            when (model.stage) {
                SecureAccountModel.Stage.email -> emailFocus.requestFocus()
                SecureAccountModel.Stage.code, SecureAccountModel.Stage.signInCode -> codeFocus.requestFocus()
                SecureAccountModel.Stage.choose -> Unit
            }
        }
    }
    // The first stage is one centred group (seal, title, buttons) instead of a top/bottom split; the
    // email and code stages keep the footer so the keyboard never covers the primary button.
    OnboardingPage(
        spacing = PleadSpacing.l,
        centered = model.stage == SecureAccountModel.Stage.choose,
        content = {
            Header(me, partner)
            when (model.stage) {
                SecureAccountModel.Stage.choose -> Column(
                    Modifier.fillMaxWidth().padding(top = PleadSpacing.s),
                    verticalArrangement = Arrangement.spacedBy(PleadSpacing.m),
                ) {
                    ChooseFooter(model, localAuth, appleAvailable, auth)
                }
                SecureAccountModel.Stage.email -> EmailEntry(model, emailFocus) { scope.launch { model.sendEmail() } }
                SecureAccountModel.Stage.code, SecureAccountModel.Stage.signInCode -> CodeEntry(model, codeFocus) {
                    scope.launch { model.verify() }
                }
            }
            InlineError(model.error)
        },
        footer = {
            when (model.stage) {
                SecureAccountModel.Stage.choose -> Unit
                SecureAccountModel.Stage.email -> {
                    OnboardingPrimaryButton(SecureAccountCopy.sendLink, isLoading = model.busy, enabled = model.emailValid) {
                        scope.launch { model.sendEmail() }
                    }
                    QuietButton(SecureAccountCopy.backToChoose) { model.backToChoose() }
                }
                SecureAccountModel.Stage.code, SecureAccountModel.Stage.signInCode -> {
                    OnboardingPrimaryButton(
                        if (model.stage == SecureAccountModel.Stage.signInCode) SecureAccountCopy.signIn else SecureAccountCopy.secure,
                        isLoading = model.busy,
                        enabled = model.codeComplete,
                    ) { scope.launch { model.verify() } }
                    QuietButton(SecureAccountCopy.differentEmail) { model.useDifferentEmail() }
                }
            }
        },
    )

    val conflict = model.conflict
    if (conflict != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
        ModalBottomSheet(
            onDismissRequest = { model.cancelConflict() },
            sheetState = sheetState,
            containerColor = OnboardingPalette.cream,
        ) {
            ConflictSheet(
                message = conflict.message,
                onSignIn = { scope.launch { model.confirmConflict() } },
                onCancel = { model.cancelConflict() },
            )
        }
    }
}

// MARK: Header

@Composable
private fun Header(me: Avatar?, partner: Avatar?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(PleadSpacing.l)) {
        RecordSeal(me, partner)
        Column(verticalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
            OnboardingTitle(SecureAccountCopy.title)
            OnboardingSubtitle(SecureAccountCopy.subtitle)
        }
    }
}

// MARK: Choose

@Composable
private fun ChooseFooter(model: SecureAccountModel, localAuth: Boolean, appleAvailable: Boolean, auth: AuthService) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    ProviderButton(
        title = SecureAccountCopy.google,
        identifier = "secure.google",
        hint = "Links your Google account to this court record",
        background = Color.White,
        foreground = Color(0xFF1F1F1F),
        border = Color(0xFF747775),
        busy = model.busy,
        glyph = { GoogleGlyph() },
    ) {
        scope.launch {
            if (localAuth) {
                model.google(GoogleCredential(idToken = "demo", nonce = null))
                return@launch
            }
            val activity = context.findActivity() ?: return@launch model.googleFailed()
            val credential = try {
                auth.googleCredential(activity)
            } catch (e: AuthLinkError.providerUnavailable) {
                return@launch model.googleFailed(unavailable = true)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch model.googleFailed()
            }
            model.google(credential)
        }
    }
    if (appleAvailable) {
        ProviderButton(
            title = SecureAccountCopy.apple,
            identifier = "secure.apple",
            hint = "Links your Apple ID to this court record",
            background = Color.Black,
            foreground = Color.White,
            border = null,
            busy = model.busy,
            glyph = null,
        ) {
            scope.launch {
                if (localAuth && !model.appleSignsIn) model.apple(AppleCredential(idToken = "demo", nonce = null))
                else model.appleOAuth()
            }
        }
    }
    QuietButton(SecureAccountCopy.useEmail) { model.useEmail() }
    Text(
        SecureAccountCopy.footnote,
        style = TextStyle(fontSize = TextStyleKind.footnote.defaultSize.sp, fontWeight = FontWeight.Medium),
        color = OnboardingPalette.secondaryText,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Google / Apple sign-in button: 56 dp, the provider's own colours, a spinner while busy. */
@Composable
private fun ProviderButton(
    title: String,
    identifier: String,
    hint: String,
    background: Color,
    foreground: Color,
    border: Color?,
    busy: Boolean,
    glyph: (@Composable () -> Unit)?,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(OnboardingRadius.action)
    Box(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .background(background, shape)
            .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier)
            .clickable(enabled = !busy, onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = "$title. $hint"
            }
            .testTag(identifier),
        contentAlignment = Alignment.Center,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            glyph?.invoke()
            Text(
                title,
                style = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.Medium),
                color = foreground,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
        if (busy) {
            Box(Modifier.matchParentSize().background(background.copy(alpha = 0.75f), shape), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = foreground, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** The Google "G" (drawn: no Google brand asset is bundled; replace with the official mark before release). */
@Composable
private fun GoogleGlyph() {
    Text(
        "G",
        style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
        color = Color(0xFF4285F4),
        modifier = Modifier.clearAndSetSemantics { },
    )
}

// MARK: Email

@Composable
private fun EmailEntry(model: SecureAccountModel, focus: FocusRequester, onSubmit: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
        OnboardingFieldLabel(SecureAccountCopy.emailLabel)
        OnboardingInput(
            value = model.email,
            onValueChange = { model.email = it },
            placeholder = SecureAccountCopy.emailPlaceholder,
            label = "Email address",
            keyboard = KeyboardOptions(
                capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                keyboardType = KeyboardType.Email, imeAction = ImeAction.Send,
            ),
            actions = KeyboardActions(onSend = { onSubmit() }),
            focus = focus,
        )
        Text(
            SecureAccountCopy.emailNote,
            style = TextStyle(fontSize = TextStyleKind.footnote.defaultSize.sp, fontWeight = FontWeight.Medium),
            color = OnboardingPalette.secondaryText,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun CodeEntry(model: SecureAccountModel, focus: FocusRequester, onComplete: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
        OnboardingFieldLabel(if (model.stage == SecureAccountModel.Stage.signInCode) SecureAccountCopy.signInCodeLabel else SecureAccountCopy.codeLabel)
        Text(
            SecureAccountCopy.check(model.email.trim(' ')),
            style = TextStyle(fontSize = TextStyleKind.subheadline.defaultSize.sp, fontWeight = FontWeight.Medium),
            color = OnboardingPalette.secondaryText,
            modifier = Modifier.fillMaxWidth(),
        )
        OnboardingInput(
            value = model.code,
            onValueChange = {
                model.code = it
                if (model.code.length == 6) onComplete()
            },
            placeholder = SecureAccountCopy.codePlaceholder,
            label = "Six digit code",
            keyboard = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            actions = KeyboardActions(onDone = { onComplete() }),
            focus = focus,
            textStyle = TextStyle(fontSize = TextStyleKind.title.defaultSize.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center).monospacedDigit(),
        )
    }
}

/** Swift `.onboardingInput()`: paper-white input, 14 pt corners, hairline border, 52 pt tall. */
@Composable
private fun OnboardingInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    label: String,
    keyboard: KeyboardOptions,
    actions: KeyboardActions,
    focus: FocusRequester,
    textStyle: TextStyle = TextStyle(fontSize = TextStyleKind.body.defaultSize.sp, fontWeight = FontWeight.Medium),
) {
    val shape = RoundedCornerShape(OnboardingRadius.input)
    val style = textStyle.copy(color = OnboardingPalette.cocoa)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(OnboardingPalette.burgundy),
        keyboardOptions = keyboard,
        keyboardActions = actions,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .semantics { contentDescription = label },
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 52.dp)
                    .background(OnboardingPalette.paper, shape)
                    .border(1.dp, OnboardingPalette.border, shape)
                    .padding(horizontal = PleadSpacing.l),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text(placeholder, style = style.copy(color = OnboardingPalette.cocoa.copy(alpha = 0.35f)), modifier = Modifier.fillMaxWidth())
                }
                inner()
            }
        },
    )
}

@Composable
private fun QuietButton(title: String, action: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 44.dp).clickable(onClick = action).semantics { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title,
            style = TextStyle(fontSize = TextStyleKind.subheadline.defaultSize.sp, fontWeight = FontWeight.SemiBold),
            color = OnboardingPalette.secondaryText,
            textAlign = TextAlign.Center,
        )
    }
}

/** The couple's pixel avatars on a parchment docket card with a gold seal: "the record" being secured. */
@Composable
private fun RecordSeal(me: Avatar?, partner: Avatar?) {
    val shape = RoundedCornerShape(OnboardingRadius.card)
    Box(Modifier.clearAndSetSemantics { }) {
        Box(
            Modifier
                .background(OnboardingPalette.parchment, shape)
                .border(1.dp, OnboardingPalette.border, shape)
                .padding(horizontal = PleadSpacing.xl, vertical = PleadSpacing.l),
        ) {
            AvatarPair(me = me, partner = partner, size = 64.dp, showHeart = partner != null)
        }
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 12.dp, y = 12.dp)
                .size(40.dp)
                .background(OnboardingPalette.burgundy, CircleShape)
                .border(2.5.dp, OnboardingPalette.gold, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(SFSymbol.icon("lock.fill"), contentDescription = null, tint = OnboardingPalette.cream, modifier = Modifier.size(18.dp))
        }
    }
}

/** "That Apple ID already has a Plead account…": Sign in / Cancel. */
@Composable
private fun ConflictSheet(message: String, onSignIn: () -> Unit, onCancel: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(PleadSpacing.xl)
            .padding(top = PleadSpacing.m),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PleadSpacing.l),
    ) {
        Icon(
            SFSymbol.icon("person.crop.circle.badge.exclamationmark"),
            contentDescription = null,
            tint = OnboardingPalette.burgundy,
            modifier = Modifier.size(40.dp),
        )
        Text(
            SecureAccountCopy.conflictTitle,
            style = TextStyle(fontSize = TextStyleKind.title2.defaultSize.sp, fontWeight = FontWeight.ExtraBold),
            color = OnboardingPalette.wine,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            message,
            style = TextStyle(fontSize = TextStyleKind.body.defaultSize.sp, fontWeight = FontWeight.Medium),
            color = OnboardingPalette.cocoa,
            textAlign = TextAlign.Center,
        )
        Column(Modifier.padding(top = PleadSpacing.s), verticalArrangement = Arrangement.spacedBy(PleadSpacing.m)) {
            OnboardingPrimaryButton(SecureAccountCopy.signIn, action = onSignIn)
            OnboardingSecondaryButton(SecureAccountCopy.cancel, action = onCancel)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
