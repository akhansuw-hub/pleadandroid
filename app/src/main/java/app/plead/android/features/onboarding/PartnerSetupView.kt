// Port of ArgueWin/Features/Onboarding/PartnerSetupView.swift: Bring in Your Partner (amendments ak, as, aw).
package app.plead.android.features.onboarding

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.plead.android.app.AppModel
import app.plead.android.app.DemoHarness
import app.plead.android.designsystem.InlineError
import app.plead.android.designsystem.PixelAvatarView
import app.plead.android.designsystem.PleadSpacing
import app.plead.android.designsystem.PleadType
import app.plead.android.designsystem.SFSymbol
import app.plead.android.designsystem.TextStyleKind
import app.plead.android.designsystem.accessibilityReduceMotion
import app.plead.android.designsystem.pleadShadow
import app.plead.android.designsystem.swiftSpring
import app.plead.android.models.Avatar
import app.plead.android.models.EdgeError
import app.plead.android.models.Profile
import app.plead.android.services.Analytics
import app.plead.android.services.AppConfig
import app.plead.android.services.EdgeErrors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Onboarding redesign 2.0 (amendment ak) · Bring in Your Partner. A large `PartnerVersusCard` ("YOU v. PARTNER")
 * shows the format cases will take. Amendment aw: the step no longer asks for the partner's name (they type their
 * own when they join); the card reads "YOU v. PARTNER" until the couple is linked. The date is optional. Both paths
 * create the couple (`create_couple`) so the gate has something to attach premium to; INVITE PARTNER also opens the
 * share sheet. A user who already joined a couple sees their linked partner instead.
 * Amendment as: a third option, I HAVE AN INVITE CODE, switches the step to code entry for invited partners
 * (`join_couple` works for the anonymous session THAT'S ME created). After a join the step shows the linked state
 * (no share sheet, no celebration cover) and onboarding continues; the gate then skips the paywall when the
 * partner already paid. An invite link opened during onboarding lands here with the code prefilled.
 * Motion: the two profile cards slide in from opposite sides and settle toward the centre; the CTAs follow.
 * When an invite is sent a tiny summons envelope exits upward (an animation layer only: the invite logic never
 * waits on it). Linking with a partner gives one light haptic. Reduce Motion: fades only.
 */
object PartnerSetupView {
    const val kicker = "Bring in your partner"
    const val headline = "Every case needs another side."
    const val subtitle = "Add your partner now or invite them later."
    const val inviteTitle = "Invite Partner"
    const val laterTitle = "I'll Do This Later"
    // Amendment as: invited partners.
    const val haveCodeTitle = "I Have an Invite Code"
    const val joinKicker = "Answer the summons"
    const val joinHeadline = "Enter your invite code."
    const val joinSubtitle = "It's in the invite your partner sent you. Once you're linked, one subscription covers you both."
    const val codeLabel = "Partner's invite code"
    const val codeHint = "Six letters and numbers."
    const val joinTitle = "Join My Partner"
    const val joinBackTitle = "Back"
    const val linkedKicker = "Couple of record"

    fun linkedHeadline(partner: String): String {
        val name = OnboardingFlow.trimmedName(partner)
        return "You're linked with ${if (name.isEmpty()) "your partner" else name}."
    }

    fun linkedSubtitle(partner: String, premium: Boolean): String {
        val name = OnboardingFlow.trimmedName(partner)
        return if (premium) "${if (name.isEmpty()) "Your partner" else name}'s subscription already covers you both."
        else "One subscription covers you both. Only one of you needs to pay."
    }

    /** Debug screenshots only: `AWDemo YES AWPartnerJoin YES|<CODE>` opens the step on code entry. */
    val initialJoinCode: String?
        get() {
            val v = DemoHarness.partnerJoin ?: return null
            return if (v.uppercase() == "YES") "" else InviteCode.sanitize(v)
        }

    /** When the versus card's two profiles have settled on entry: the CTAs follow. */
    val docketSettled: Double
        get() = PleadRevealParameters.make(PleadRevealKind.card, index = 0, reduceMotion = false).delay + OnboardingMotionTokens.versusDuration

    fun shareMessage(code: String): String = "You've been summoned. Join me on Plead with code $code."
}

private enum class PartnerAction { invite, later }

@Composable
fun PartnerSetupView(app: AppModel, modifier: Modifier = Modifier) {
    val model = app.onboardingModel
    val store = app.store
    val links = app.links
    var working by remember { mutableStateOf<PartnerAction?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var shareURL by remember { mutableStateOf<String?>(null) }
    var pickingDate by remember { mutableStateOf(false) }
    var linkedHaptic by remember { mutableStateOf(false) }
    /** Bumped once per invite sent; drives the envelope layer only. */
    var envelopes by remember { mutableIntStateOf(0) }
    // Amendment as: the code-entry state of this step.
    val initial = remember { PartnerSetupView.initialJoinCode }
    var joining by remember { mutableStateOf(initial != null) }
    var code by remember { mutableStateOf(initial ?: "") }
    var joinWorking by remember { mutableStateOf(false) }
    var joinError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val linkedPartner: Profile? = if (store.couple?.isLinked == true) store.partner else null
    val myName = store.me?.displayName ?: model.displayName
    val myAvatar = store.me?.avatar ?: model.avatar

    OnboardingHaptic(OnboardingHaptics.Moment.partnerLinked, linkedHaptic)
    var previousPartnerId by remember { mutableStateOf(linkedPartner?.id) }
    LaunchedEffect(linkedPartner?.id) {
        if (previousPartnerId == null && linkedPartner?.id != null) linkedHaptic = !linkedHaptic
        previousPartnerId = linkedPartner?.id
    }
    // Amendment as: an invite link opened during onboarding lands on code entry, prefilled.
    LaunchedEffect(links.pendingJoinCode) {
        val pending = links.pendingJoinCode ?: return@LaunchedEffect
        if (linkedPartner != null) return@LaunchedEffect
        code = InviteCode.sanitize(pending)
        joinError = null
        joining = true
        links.pendingJoinCode = null
    }

    fun run(action: PartnerAction) {
        if (working != null) return
        working = action; error = null
        Analytics.track(
            if (action == PartnerAction.invite) "partner_invite_started" else "partner_invite_skipped",
            mapOf("has_date" to (model.togetherSince != null).toString()),
        )
        scope.launch {
            try {
                val couple = store.createCouple()
                runCatching { store.saveTogetherSince(model.togetherSince) }
                if (action == PartnerAction.invite) {
                    envelopes += 1 // the summons leaves; purely visual, the share sheet does not wait for it
                    shareURL = AppConfig.inviteURL(couple.inviteCode)
                } else {
                    model.advance()
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (action == PartnerAction.invite) {
                    error = (e as? EdgeError)?.message ?: "Couldn't create your invite. Try again."
                } else {
                    // "Later" never blocks onboarding: the gate's link step creates the couple afterwards.
                    model.advance()
                }
            } finally {
                working = null
            }
        }
    }

    /**
     * `join_couple` as the (anonymous) onboarding session. `couple_join` moves me out of my own empty invite
     * couple if I made one first, so I never end up in two.
     */
    fun join() {
        if (!InviteCode.isWellFormed(code) || joinWorking) return
        joinWorking = true; joinError = null
        Analytics.track("partner_join_started", mapOf("source" to "onboarding"))
        scope.launch {
            try {
                store.joinCouple(code, celebrate = false)
                app.joinedCoupleDuringOnboarding()
                Analytics.track("partner_join_succeeded", mapOf("source" to "onboarding", "couple_premium" to store.isPremium.toString()))
                joining = false
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                Analytics.track("partner_join_failed", mapOf("source" to "onboarding", "code" to ((e as? EdgeError)?.code ?: "unknown")))
                joinError = EdgeErrors.joinMessage(e)
            } finally {
                joinWorking = false
            }
        }
    }

    Box(modifier) {
        when {
            linkedPartner != null -> PartnerLinkedView(app, linkedPartner, myName, myAvatar)
            joining -> PartnerJoinView(
                code = code,
                onCode = { v ->
                    code = InviteCode.sanitize(v)
                    joinError = null
                },
                joinWorking = joinWorking,
                joinError = joinError,
                onJoin = ::join,
                onBack = {
                    joinError = null
                    joining = false
                },
            )
            else -> PartnerForm(
                model = model,
                myName = myName,
                myAvatar = myAvatar,
                working = working,
                error = error,
                envelopes = envelopes,
                onInvite = { run(PartnerAction.invite) },
                onHaveCode = {
                    error = null; joinError = null
                    Analytics.track("partner_join_opened", mapOf("source" to "onboarding"))
                    joining = true
                },
                onLater = { run(PartnerAction.later) },
                onPickDate = { pickingDate = true },
            )
        }
    }

    shareURL?.let { url ->
        val message = PartnerSetupView.shareMessage(store.couple?.inviteCode ?: "")
        ActivityShareSheet(items = listOf(message, url)) {
            shareURL = null
            model.advance()
        }
    }
    if (pickingDate) {
        TogetherSincePicker(date = model.togetherSince, onDateChange = { model.togetherSince = it }, onDismiss = { pickingDate = false })
    }
}

private fun formatLong(instant: Instant): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).format(instant.atZone(ZoneId.systemDefault()))

@Composable
private fun PartnerHeader(kicker: String, headline: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
        IdentityKicker(kicker, Modifier.pleadReveal(PleadRevealKind.headline))
        IdentityEditorialTitle(headline, Modifier.pleadReveal(PleadRevealKind.headline))
        OnboardingSubtitle(subtitle, Modifier.pleadReveal(PleadRevealKind.body))
    }
}

// MARK: Form

@Composable
private fun PartnerForm(
    model: OnboardingModel,
    myName: String,
    myAvatar: Avatar,
    working: PartnerAction?,
    error: String?,
    envelopes: Int,
    onInvite: () -> Unit,
    onHaveCode: () -> Unit,
    onLater: () -> Unit,
    onPickDate: () -> Unit,
) {
    OnboardingShell(
        hero = {
            Column(verticalArrangement = Arrangement.spacedBy(OnboardingKitTokens.Spacing.section)) {
                PartnerHeader(PartnerSetupView.kicker, PartnerSetupView.headline, PartnerSetupView.subtitle)
                // Amendment aw: no typed name; the partner's own name arrives when they link.
                PartnerVersusCard(
                    me = myName, myAvatar = myAvatar, partner = "", partnerAvatar = null,
                    modifier = Modifier.pleadReveal(PleadRevealKind.card, index = 0, offset = Offset.Zero, scale = 1f, spring = false),
                )
            }
        },
        content = {
            item {
                Column(Modifier.pleadReveal(PleadRevealKind.body, index = 1), verticalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
                    IdentityKicker("Together since (optional)", Modifier.semantics { heading() }, alignment = Alignment.Start)
                    val since = model.togetherSince
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .onboardingInput()
                            .clickable(onClick = onPickDate)
                            .semantics(mergeDescendants = true) {
                                role = Role.Button
                                contentDescription = "Together since"
                                stateDescription = since?.let(::formatLong) ?: "Not set"
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            since?.let(::formatLong) ?: "Add a date",
                            style = OnboardingInputTextStyle,
                            color = if (since == null) OnboardingPalette.secondaryText else OnboardingPalette.cocoa,
                            modifier = Modifier.weight(1f).padding(vertical = 14.dp),
                        )
                        Icon(SFSymbol.icon("calendar"), contentDescription = null, tint = OnboardingPalette.mahogany.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
                    }
                }
            }
            item { InlineError(error) }
        },
        cta = {
            Box(contentAlignment = Alignment.TopCenter) {
                CourtPrimaryButton(
                    title = PartnerSetupView.inviteTitle, identifier = "onboarding.primary",
                    isLoading = working == PartnerAction.invite, enabled = working == null, action = onInvite,
                )
                SummonsEnvelopeFlight(envelopes)
            }
            OutlinedCourtButton(PartnerSetupView.haveCodeTitle, "onboarding.partner.haveCode", enabled = working == null, action = onHaveCode)
            QuietTextButton(PartnerSetupView.laterTitle, isLoading = working == PartnerAction.later, enabled = working == null, action = onLater)
        },
    )
}

// MARK: Invite code (amendment as)

@Composable
private fun PartnerJoinView(
    code: String,
    onCode: (String) -> Unit,
    joinWorking: Boolean,
    joinError: String?,
    onJoin: () -> Unit,
    onBack: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(Unit) { if (code.isEmpty()) runCatching { focus.requestFocus() } }
    val codeStyle = TextStyle(
        fontFamily = FontFamily.Monospace, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold,
        letterSpacing = 6.sp, textAlign = TextAlign.Center, color = OnboardingPalette.cocoa,
    )
    OnboardingShell(
        hero = { PartnerHeader(PartnerSetupView.joinKicker, PartnerSetupView.joinHeadline, PartnerSetupView.joinSubtitle) },
        content = {
            item {
                Column(Modifier.pleadReveal(PleadRevealKind.body, index = 1), verticalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
                    IdentityKicker(PartnerSetupView.codeLabel, Modifier.semantics { heading() }, alignment = Alignment.Start)
                    BasicTextField(
                        value = code,
                        onValueChange = onCode,
                        singleLine = true,
                        textStyle = codeStyle,
                        cursorBrush = SolidColor(OnboardingPalette.burgundy),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Go,
                        ),
                        keyboardActions = KeyboardActions(onGo = {
                            focusManager.clearFocus()
                            onJoin()
                        }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus)
                            .semantics { contentDescription = "Invite code" }
                            .testTag("onboarding.partner.code"),
                        decorationBox = { inner ->
                            Box(Modifier.fillMaxWidth().onboardingInput(), contentAlignment = Alignment.Center) {
                                if (code.isEmpty()) Text("ABC234", style = codeStyle, color = OnboardingPalette.cocoa.copy(alpha = 0.25f))
                                inner()
                            }
                        },
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PleadSpacing.m)) {
                        Text(PartnerSetupView.codeHint, style = PleadType.metadata, color = OnboardingPalette.secondaryText)
                        Spacer(Modifier.weight(1f))
                        // iOS `PasteButton`: reads the clipboard only when tapped.
                        TextButton(
                            onClick = { clipboard.getText()?.text?.let { onCode(InviteCode.sanitize(it)) } },
                            modifier = Modifier
                                .background(OnboardingPalette.burgundy.copy(alpha = 0.12f), RoundedCornerShape(50))
                                .testTag("onboarding.partner.paste"),
                        ) {
                            Icon(Icons.Outlined.ContentPaste, contentDescription = null, tint = OnboardingPalette.burgundy, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Paste", style = PleadType.bodyStrong, color = OnboardingPalette.burgundy)
                        }
                    }
                }
            }
            item { InlineError(joinError ?: InviteCode.problem(code), Modifier.testTag("onboarding.partner.codeError")) }
        },
        cta = {
            CourtPrimaryButton(
                title = PartnerSetupView.joinTitle, identifier = "onboarding.partner.join", isLoading = joinWorking,
                enabled = InviteCode.isWellFormed(code),
            ) {
                focusManager.clearFocus()
                onJoin()
            }
            QuietTextButton(PartnerSetupView.joinBackTitle, identifier = "onboarding.partner.joinBack", enabled = !joinWorking) {
                focusManager.clearFocus()
                onBack()
            }
        },
    )
}

// MARK: Already linked

@Composable
private fun PartnerLinkedView(app: AppModel, partner: Profile, myName: String, myAvatar: Avatar) {
    val model = app.onboardingModel
    OnboardingShell(
        hero = {
            Column(verticalArrangement = Arrangement.spacedBy(OnboardingKitTokens.Spacing.section)) {
                PartnerHeader(
                    PartnerSetupView.linkedKicker,
                    PartnerSetupView.linkedHeadline(partner.displayName),
                    PartnerSetupView.linkedSubtitle(partner.displayName, app.store.isPremium),
                    Modifier.semantics(mergeDescendants = true) { }.testTag("onboarding.partner.linked"),
                )
                PartnerVersusCard(
                    me = myName, myAvatar = myAvatar, partner = partner.displayName, partnerAvatar = partner.avatar,
                    modifier = Modifier.pleadReveal(PleadRevealKind.card, index = 0, offset = Offset.Zero, scale = 1f, spring = false),
                )
            }
        },
        content = {},
        cta = { CourtPrimaryButton(title = "Continue", identifier = "onboarding.primary") { model.advance() } },
    )
}

// MARK: - PartnerVersusCard

/**
 * "YOU v. PARTNER": a paper case card with the two parties as profile tiles and an elegant italic gold "v."
 * between them (editorial, not a fighting game). The partner reads "PARTNER" until they link (amendment aw); their
 * avatar is a quiet silhouette until they link. On appear the two tiles slide in from opposite sides and settle
 * toward the centre. Reusable wherever a future case needs previewing. One TalkBack element.
 */
object PartnerVersusCard {
    const val title = "FUTURE CASE PREVIEW"
    const val caption = "Cases will appear in this format."
    const val versus = "v."
    const val youPlaceholder = "YOU"
    const val partnerPlaceholder = "PARTNER"

    fun leftName(me: String): String = OnboardingFlow.trimmedName(me).let { if (it.isEmpty()) youPlaceholder else it.uppercase() }
    fun rightName(partner: String): String = OnboardingFlow.trimmedName(partner).let { if (it.isEmpty()) partnerPlaceholder else it.uppercase() }

    /** "ARIF v. SOPHIE" / "YOU v. PARTNER". */
    fun heading(me: String, partner: String): String = "${leftName(me)} $versus ${rightName(partner)}"

    fun accessibilityText(me: String, partner: String): String {
        val a = OnboardingFlow.trimmedName(me)
        val b = OnboardingFlow.trimmedName(partner)
        return "Future case preview: ${if (a.isEmpty()) "You" else a} versus ${if (b.isEmpty()) "your partner" else b}. $caption"
    }
}

@Composable
fun PartnerVersusCard(me: String, myAvatar: Avatar?, partner: String, partnerAvatar: Avatar?, modifier: Modifier = Modifier) {
    val T = OnboardingIdentityTokens
    val reduceMotion = accessibilityReduceMotion()
    // `dynamicTypeSize.isAccessibilitySize` (wave 2b convention: font scale ≥ 1.6).
    val stacked = LocalDensity.current.fontScale >= 1.6f
    val shape = RoundedCornerShape(T.plateRadius.dp)
    val shiftPx = with(LocalDensity.current) { (if (reduceMotion) 0f else OnboardingMotionTokens.versusShift).dp.toPx() }
    val settle = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (settle.value == 1f) return@LaunchedEffect
        delay(OnboardingMotionTokens.cardDelay.millis().toLong())
        settle.animateTo(
            1f,
            if (reduceMotion) tween(OnboardingMotionTokens.versusDuration.millis(), easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f))
            else swiftSpring(OnboardingMotionTokens.versusDuration.toFloat(), OnboardingMotionTokens.cardBounce.toFloat()),
        )
    }
    val start = OnboardingMotionTokens.revealStartOpacity.toFloat()
    fun Modifier.settling(direction: Float) = graphicsLayer {
        val v = settle.value
        translationX = direction * shiftPx * (1f - v)
        alpha = (start + (1f - start) * v).coerceIn(0f, 1f)
    }

    Column(
        modifier
            .fillMaxWidth()
            .pleadShadow(OnboardingPalette.wine.copy(alpha = 0.08f), radius = T.cardShadow.dp, y = T.cardShadowY.dp, shape = shape)
            .background(OnboardingPalette.paper, shape)
            .border(1.dp, OnboardingPalette.border, shape)
            .testTag("onboarding.versusCard")
            .clearAndSetSemantics { contentDescription = PartnerVersusCard.accessibilityText(me, partner) }
            .padding(PleadSpacing.l),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PleadSpacing.l),
    ) {
        Text(PartnerVersusCard.title, style = PleadType.labelCapsTracked, color = OnboardingPalette.secondaryText)
        val versus: @Composable (Modifier) -> Unit = { m ->
            Text(PartnerVersusCard.versus, style = T.versus, color = OnboardingPalette.gold, modifier = m.settling(0f))
        }
        if (stacked) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
                VersusParty(PartnerVersusCard.leftName(me), myAvatar, OnboardingPalette.blush.copy(alpha = 0.35f), Modifier.fillMaxWidth().settling(-1f))
                versus(Modifier)
                VersusParty(PartnerVersusCard.rightName(partner), partnerAvatar, OnboardingPalette.parchment, Modifier.fillMaxWidth().settling(1f))
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
                VersusParty(PartnerVersusCard.leftName(me), myAvatar, OnboardingPalette.blush.copy(alpha = 0.35f), Modifier.weight(1f).settling(-1f))
                versus(Modifier)
                VersusParty(PartnerVersusCard.rightName(partner), partnerAvatar, OnboardingPalette.parchment, Modifier.weight(1f).settling(1f))
            }
        }
        Text(PartnerVersusCard.caption, style = PleadType.metadata, color = OnboardingPalette.secondaryText, textAlign = TextAlign.Center)
    }
}

@Composable
private fun VersusParty(name: String, avatar: Avatar?, fill: Color, modifier: Modifier) {
    val T = OnboardingIdentityTokens
    Column(
        modifier
            .defaultMinSize(minHeight = T.versusTileMinHeight.dp)
            .background(fill, RoundedCornerShape(T.tileRadius))
            .padding(vertical = PleadSpacing.l, horizontal = PleadSpacing.s),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PleadSpacing.m, Alignment.CenterVertically),
    ) {
        Box(Modifier.size(T.versusAvatar.dp), contentAlignment = Alignment.Center) {
            if (avatar != null) {
                Crossfade(avatar, label = "versusAvatar") { a -> PixelAvatarView(a, size = T.versusAvatar.dp) }
            } else {
                // Until they link: a quiet silhouette, never a real face.
                PixelAvatarView(
                    OnboardingAvatars.presets[0],
                    Modifier
                        .graphicsLayer {
                            alpha = 0.14f
                            compositingStrategy = CompositingStrategy.Offscreen
                        }
                        .drawWithContent {
                            drawContent()
                            drawRect(Color.Black, blendMode = BlendMode.SrcIn)
                        },
                    size = T.versusAvatar.dp,
                    outlined = false,
                )
            }
        }
        Crossfade(name, animationSpec = tween(OnboardingMotionTokens.previewCrossfade.millis()), label = "versusName") { n ->
            Text(n, style = T.partyName, color = OnboardingPalette.wine, textAlign = TextAlign.Center, maxLines = T.nameLines, overflow = TextOverflow.Ellipsis)
        }
    }
}

// MARK: - Summons envelope

/**
 * A tiny summons envelope that rises and fades each time `trigger` changes. Visual only: never hit-tests, never
 * read by TalkBack, and nothing waits for it. Reduce Motion: a short fade in place.
 */
@Composable
fun SummonsEnvelopeFlight(trigger: Int, modifier: Modifier = Modifier) {
    val T = OnboardingIdentityTokens
    val reduceMotion = accessibilityReduceMotion()
    val rise = remember { Animatable(0f) }
    val opacity = remember { Animatable(0f) }
    val scale = remember { Animatable(1f) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(trigger) {
        if (first) {
            first = false
            return@LaunchedEffect
        }
        val total = T.envelopeDuration
        rise.snapTo(0f); opacity.snapTo(0f); scale.snapTo(1f)
        launch { rise.animateTo(if (reduceMotion) 0f else T.envelopeRise, tween(total.millis())) }
        launch { scale.animateTo(if (reduceMotion) 1f else 0.8f, tween(total.millis())) }
        launch {
            opacity.animateTo(1f, tween((total * 0.2).millis(), easing = LinearEasing))
            delay((total * 0.4).millis().toLong())
            opacity.animateTo(0f, tween((total * 0.4).millis(), easing = LinearEasing))
        }
    }
    val density = LocalDensity.current
    Icon(
        SFSymbol.icon("envelope.fill"),
        contentDescription = null,
        tint = OnboardingPalette.gold,
        modifier = modifier
            .size(T.envelopeSize.dp)
            .graphicsLayer {
                translationY = -with(density) { rise.value.dp.toPx() }
                alpha = opacity.value
                scaleX = scale.value
                scaleY = scale.value
            }
            .clearAndSetSemantics { },
    )
}

/** Quiet secondary action: burgundy uppercase text, no fill, 44 pt target. Keeps `onboarding.secondary`. */
@Composable
fun QuietTextButton(
    title: String,
    modifier: Modifier = Modifier,
    identifier: String = "onboarding.secondary",
    isLoading: Boolean = false,
    enabled: Boolean = true,
    action: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .pleadPress(pressed)
            .fillMaxWidth()
            .defaultMinSize(minHeight = 44.dp)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = action)
            .semantics {
                role = Role.Button
                contentDescription = title
            }
            .testTag(identifier),
        contentAlignment = Alignment.Center,
    ) {
        if (isLoading) {
            CircularProgressIndicator(color = OnboardingPalette.burgundy, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        } else {
            Text(
                title.uppercase(),
                style = PleadType.uiButtonSecondary.copy(letterSpacing = 0.8.sp),
                color = OnboardingPalette.burgundy,
                textAlign = TextAlign.Center,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
    }
}

/**
 * Amendment as: a secondary action that must read as a real choice next to the primary (paper fill, burgundy
 * hairline border, burgundy uppercase label). Same height as the kit's secondary buttons.
 */
@Composable
fun OutlinedCourtButton(title: String, identifier: String, modifier: Modifier = Modifier, enabled: Boolean = true, action: () -> Unit) {
    val shape = RoundedCornerShape(OnboardingKitTokens.Radius.button)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .pleadPress(pressed)
            .graphicsLayer { alpha = if (enabled) 1f else 0.42f }
            .fillMaxWidth()
            .defaultMinSize(minHeight = OnboardingKitTokens.Size.secondaryHeight)
            .background(OnboardingPalette.paper, shape)
            .border(1.dp, OnboardingPalette.burgundy.copy(alpha = 0.45f), shape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = action)
            .semantics {
                role = Role.Button
                contentDescription = CourtPrimaryButton.spokenTitle(title)
            }
            .testTag(identifier)
            .padding(horizontal = PleadSpacing.l),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title.uppercase(),
            style = PleadType.ui(15f, FontWeight.Bold, relativeTo = TextStyleKind.subheadline).copy(letterSpacing = 0.8.sp),
            color = OnboardingPalette.burgundy,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

/**
 * Native date picker (iOS: graphical `DatePicker` in a sheet), with Remove for the optional date. Dates after
 * today are not selectable; Done stores the start of the chosen day.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TogetherSincePicker(date: Instant?, onDateChange: (Instant?) -> Unit, onDismiss: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val initialLocal = date?.atZone(zone)?.toLocalDate() ?: LocalDate.now(zone).minusYears(1)
    val todayUtcMillis = LocalDate.now(zone).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initialLocal.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= todayUtcMillis
            override fun isSelectableYear(year: Int): Boolean = year <= LocalDate.now(zone).year
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { millis ->
                    val day = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                    onDateChange(day.atStartOfDay(zone).toInstant())
                }
                onDismiss()
            }) { Text("Done", style = PleadType.bodyStrong, color = OnboardingPalette.burgundy) }
        },
        dismissButton = {
            if (date != null) {
                TextButton(onClick = {
                    onDateChange(null)
                    onDismiss()
                }) { Text("Remove", color = OnboardingPalette.burgundy) }
            } else {
                TextButton(onClick = onDismiss) { Text("Cancel", color = OnboardingPalette.burgundy) }
            }
        },
    ) {
        DatePicker(
            state = state,
            title = { Text("Together since", style = PleadType.titleM, color = OnboardingPalette.cocoa, modifier = Modifier.padding(start = 24.dp, top = 16.dp)) },
            colors = DatePickerDefaults.colors(
                selectedDayContainerColor = OnboardingPalette.burgundy,
                todayDateBorderColor = OnboardingPalette.burgundy,
                selectedYearContainerColor = OnboardingPalette.burgundy,
            ),
        )
    }
}

// MARK: - Helpers

/** Swift `IdentifiedURL` (the `.sheet(item:)` adapter); kept for parity with callers that hold an invite URL. */
data class IdentifiedURL(val url: String) {
    val id: String get() = url
}
