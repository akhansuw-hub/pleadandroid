// Port of ArgueWin/Features/Onboarding/LinkCoupleView.swift (LinkCoupleView, InviteSheet, LinkedCelebrationView,
// FloatingHearts).
package app.plead.android.features.onboarding

import android.content.Intent
import android.text.format.DateUtils
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.plead.android.app.AppModel
import app.plead.android.app.DemoHarness
import app.plead.android.designsystem.AWButton
import app.plead.android.designsystem.AWButtonKind
import app.plead.android.designsystem.AWButtonStyle
import app.plead.android.designsystem.AWCard
import app.plead.android.designsystem.AvatarPair
import app.plead.android.designsystem.InlineError
import app.plead.android.designsystem.PleadColor
import app.plead.android.designsystem.PleadCopy
import app.plead.android.designsystem.PleadFont
import app.plead.android.designsystem.PleadSpacing
import app.plead.android.designsystem.PrimaryButton
import app.plead.android.designsystem.SFSymbol
import app.plead.android.designsystem.ScalesMark
import app.plead.android.designsystem.SectionLabel
import app.plead.android.designsystem.accessibilityReduceMotion
import app.plead.android.designsystem.awBackground
import app.plead.android.designsystem.awInput
import app.plead.android.models.EdgeError
import app.plead.android.services.AppConfig
import app.plead.android.services.CaseStore
import app.plead.android.services.EdgeErrors
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.launch

/**
 * Onboarding link step: create a couple (code + share sheet) or enter a partner's code.
 * Linking is detected via Realtime on `couples.linked_at` → celebration cover.
 * Reachable mid-onboarding for the "join with code" path from a universal link.
 * Also where the paywall gate's Close lands: a solo user can share the invite, edit their avatar,
 * or wait for the partner, then Continue forward to the gate again.
 */
object LinkCoupleView {
    enum class Mode { choose, invite, join, linked }

    /** Amendment 2026-09-24 s: the group is centred in the safe area and capped for larger screens. */
    object Layout {
        val maxGroupWidth = 420.dp
        val avatarSize = 72.dp
        val avatarToTitle = 24.dp
        val titleToCopy = 8.dp
        val copyToActions = 28.dp
        val actionSpacing = 12.dp
        /** Breathing room kept above and below the group when it's tall enough to scroll. */
        val edgeMinimum = 16.dp
    }

    /** Debug screenshots only: `AWDemo YES AWLinkMode join|linked` opens this screen on that variant. */
    val initialMode: Mode
        get() {
            if (DemoHarness.isDemo) {
                when (DemoHarness.linkMode) {
                    "join" -> return Mode.join
                    "linked" -> return Mode.linked
                }
            }
            return Mode.choose
        }

    /** Existing unlinked couple → show its code straight away; linked (back from the gate) → Continue. */
    fun currentMode(mode: Mode, coupleExists: Boolean, coupleLinked: Boolean): Mode {
        if (mode == Mode.choose && coupleExists) return if (coupleLinked) Mode.linked else Mode.invite
        return mode
    }

    const val inviteSubject = "Join me on Plead"
    fun inviteMessage(code: String): String = "You've been summoned. Join me on Plead with code $code."
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkCoupleView(
    app: AppModel,
    modifier: Modifier = Modifier,
    /** Embedded in Home/Us for solo users (no "continue on my own" footer). */
    embedded: Boolean = false,
) {
    val store = app.store
    val links = app.links
    var mode by remember { mutableStateOf(LinkCoupleView.initialMode) }
    var code by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var editingAvatar by remember { mutableStateOf(false) }
    val codeFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val L = LinkCoupleView.Layout

    LaunchedEffect(links.pendingJoinCode) {
        val pending = links.pendingJoinCode ?: return@LaunchedEffect
        code = InviteCode.sanitize(pending); mode = LinkCoupleView.Mode.join
        app.holdLinkStep()
        links.pendingJoinCode = null
    }

    val couple = store.couple
    val currentMode = LinkCoupleView.currentMode(mode, couple != null, couple?.isLinked == true)

    fun createInvite() {
        if (working) return
        working = true; error = null
        scope.launch {
            try {
                store.createCouple(); mode = LinkCoupleView.Mode.invite
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = (e as? EdgeError)?.message ?: "Couldn't create an invite. Try again."
            }
            working = false
        }
    }

    fun join() {
        if (!InviteCode.isWellFormed(code) || working) return
        working = true; error = null
        scope.launch {
            try {
                store.joinCouple(code)
                app.joinedCouple()
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = EdgeErrors.joinMessage(e)
            }
            working = false
        }
    }

    Column(modifier.fillMaxSize().awBackground()) {
        if (!embedded && !app.onboardingDone) {
            // Reached from an invite link mid-onboarding: the rest of setup is one tap away.
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = PleadSpacing.l),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.defaultMinSize(minHeight = 44.dp)
                        .clickable { app.leaveLinkStepToOnboarding() }
                        .semantics(mergeDescendants = true) { role = Role.Button },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(SFSymbol.icon("chevron.left"), contentDescription = null, tint = PleadColor.subtleText, modifier = Modifier.size(18.dp))
                    Text("Back to setup", style = PleadFont.headline, color = PleadColor.subtleText)
                }
                Spacer(Modifier.weight(1f))
            }
        }
        // The scroll area takes the whole safe area (which shrinks when the keyboard shows); the group is centred
        // inside a column at least that tall, so it re-centres above the keyboard and falls back to scrolling when
        // large text makes it taller than the screen.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().imePadding()) {
            val minHeight = maxHeight
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    Modifier.fillMaxWidth().heightIn(min = minHeight).padding(horizontal = PleadSpacing.xl).padding(vertical = L.edgeMinimum),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Column(Modifier.widthIn(max = L.maxGroupWidth).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        // Avatar pair → title → copy → actions, assembling with the onboarding grammar.
                        Column(
                            Modifier.pleadReveal(PleadRevealKind.card, index = 0, delay = -OnboardingMotionTokens.cardDelay).padding(bottom = L.avatarToTitle),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(PleadSpacing.m),
                        ) {
                            AvatarPair(
                                me = store.me?.avatar,
                                partner = if (currentMode == LinkCoupleView.Mode.linked) store.partner?.avatar else null,
                                size = L.avatarSize, markYou = true,
                            )
                            if (store.me != null) {
                                AWButton(onClick = { editingAvatar = true }, style = AWButtonStyle.aw(AWButtonKind.secondary, fullWidth = false)) {
                                    Icon(SFSymbol.icon("paintbrush.pointed"), contentDescription = null, modifier = Modifier.size(20.dp))
                                    Text("Edit avatar")
                                }
                            }
                        }
                        Text(
                            if (currentMode == LinkCoupleView.Mode.linked) "You're a couple of record" else "Summon your partner",
                            style = PleadFont.hero, color = PleadColor.cocoa, textAlign = TextAlign.Center,
                            modifier = Modifier.semantics { heading() }.pleadReveal(PleadRevealKind.headline, delay = 0.10).padding(bottom = L.titleToCopy),
                        )
                        Text(
                            if (currentMode == LinkCoupleView.Mode.linked) {
                                "You and ${store.partner?.displayName ?: "your partner"} are linked. One subscription covers you both."
                            } else {
                                "Plead works in pairs. Send an invite, or enter the code your partner sent you."
                            },
                            style = PleadFont.body, color = PleadColor.subtleText, textAlign = TextAlign.Center,
                            modifier = Modifier.pleadReveal(PleadRevealKind.body, delay = 0.10).padding(bottom = L.copyToActions),
                        )
                        Column(
                            Modifier.fillMaxWidth().pleadReveal(PleadRevealKind.cta),
                            verticalArrangement = Arrangement.spacedBy(L.actionSpacing),
                        ) {
                            when (currentMode) {
                                LinkCoupleView.Mode.choose -> Column(verticalArrangement = Arrangement.spacedBy(PleadSpacing.m)) {
                                    PrimaryButton("Create an invite", systemImage = "paperplane.fill", isLoading = working && mode == LinkCoupleView.Mode.choose) { createInvite() }
                                    PrimaryButton("I have a code", kind = AWButtonKind.secondary) {
                                        mode = LinkCoupleView.Mode.join
                                    }
                                }
                                LinkCoupleView.Mode.invite -> InviteCard(store) { mode = LinkCoupleView.Mode.join }
                                LinkCoupleView.Mode.join -> JoinCard(
                                    code = code,
                                    onCode = { v -> code = InviteCode.sanitize(v) },
                                    error = error,
                                    working = working,
                                    focus = codeFocus,
                                    onJoin = ::join,
                                    onBack = {
                                        mode = LinkCoupleView.Mode.choose; error = null
                                    },
                                )
                                // Back from the gate with a linked, unsubscribed couple.
                                LinkCoupleView.Mode.linked -> PrimaryButton("Continue", systemImage = "arrow.right") { app.returnToGate() }
                            }
                            InlineError(error)
                            // No couple yet: an invite (or a partner's code) is the only way forward; Plead works in
                            // pairs and the subscription belongs to the couple. With an open invite the user may go on alone.
                            if (!embedded && app.onboardingDone && couple != null && !couple.isLinked && currentMode != LinkCoupleView.Mode.join) {
                                TextAction("Continue on my own", PleadColor.subtleText) { app.returnToGate() }
                            }
                        }
                    }
                }
            }
        }
    }

    if (editingAvatar) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { editingAvatar = false }, sheetState = sheetState, containerColor = PleadColor.background) {
            EditAvatarView(store, onDismiss = { editingAvatar = false }, onCancel = { editingAvatar = false })
        }
    }
}

@Composable
private fun TextAction(text: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 44.dp).clickable(onClick = onClick).semantics { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = PleadFont.headline, color = color)
    }
}

@Composable
private fun InviteCard(store: CaseStore, onHaveCode: () -> Unit) {
    val couple = store.couple ?: return
    val context = LocalContext.current
    AWCard(padding = PleadSpacing.xl) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(PleadSpacing.l)) {
            SectionLabel("Your invite code")
            SelectionContainer {
                Text(
                    couple.inviteCode,
                    style = PleadFont.ui(44f, FontWeight.ExtraBold).copy(fontFamily = FontFamily.Monospace, letterSpacing = 6.sp),
                    color = PleadColor.cocoa,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Invite code: ${couple.inviteCode.toList().joinToString(" ")}" },
                )
            }
            // iOS `ShareLink(item: url, subject:, message:)`.
            AWButton(onClick = {
                val url = AppConfig.inviteURL(couple.inviteCode)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, LinkCoupleView.inviteSubject)
                    putExtra(Intent.EXTRA_TEXT, "${LinkCoupleView.inviteMessage(couple.inviteCode)}\n$url")
                }
                runCatching { context.startActivity(Intent.createChooser(send, null)) }
            }, style = AWButtonStyle.aw(AWButtonKind.primary)) {
                Icon(SFSymbol.icon("square.and.arrow.up"), contentDescription = null, modifier = Modifier.size(20.dp))
                Text("Share invite link")
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
                CircularProgressIndicator(color = PleadColor.burgundy, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                Text("Waiting for your partner to join…", style = PleadFont.caption, color = PleadColor.subtleText)
            }
            val relative = DateUtils.getRelativeTimeSpanString(
                couple.inviteExpiresAt.toEpochMilli(), System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS,
            ).toString().replaceFirstChar { it.lowercase() }
            Text("Code expires $relative.", style = PleadFont.caption, color = PleadColor.subtleText)
        }
    }
    TextAction("I have a code instead", PleadColor.burgundy, onHaveCode)
}

@Composable
private fun JoinCard(
    code: String,
    onCode: (String) -> Unit,
    error: String?,
    working: Boolean,
    focus: FocusRequester,
    onJoin: () -> Unit,
    onBack: () -> Unit,
) {
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val style = PleadFont.ui(30f, FontWeight.ExtraBold).copy(fontFamily = FontFamily.Monospace, letterSpacing = 4.sp, textAlign = TextAlign.Center, color = PleadColor.cocoa)
    Column(verticalArrangement = Arrangement.spacedBy(PleadSpacing.m)) {
        SectionLabel("Partner's code")
        BasicTextField(
            value = code,
            onValueChange = onCode,
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(PleadColor.burgundy),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onJoin() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Invite code" },
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth().awInput(), contentAlignment = Alignment.Center) {
                    if (code.isEmpty()) Text("ABC123", style = style, color = PleadColor.cocoa.copy(alpha = 0.25f))
                    inner()
                }
            },
        )
        val problem = InviteCode.problem(code)
        if (problem != null && error == null) InlineError(problem)
        AWButton(onClick = onJoin, style = AWButtonStyle.aw(), enabled = InviteCode.isWellFormed(code) && !working) {
            if (working) CircularProgressIndicator(color = PleadColor.cream, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            else Text("Join")
        }
        TextAction("Back", PleadColor.subtleText, onBack)
    }
}

/** The invite, presented as a sheet over the tabs (onboarding screen 9's INVITE MY PARTNER). */
@Composable
fun InviteSheet(app: AppModel, onDone: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().awBackground()) {
        OnboardingSheetBar(title = "", trailing = { SheetBarAction("Done", bold = true, onClick = onDone) })
        LinkCoupleView(app, Modifier.weight(1f), embedded = true)
    }
}

/**
 * "YOU ARE NOW LEGALLY BOUND" — the rare, first-time delight moment, with the small print. A full-screen cover on
 * iOS: the caller shows it while `store.linkCelebration` and hides it in [onDone] (iOS `onDone()` then `dismiss()`;
 * RootView passes `model.finishLinkStep()` and the cover's binding clears `linkCelebration`).
 */
@Composable
fun LinkedCelebrationView(store: CaseStore, modifier: Modifier = Modifier, onDone: () -> Unit = {}) {
    val reduceMotion = accessibilityReduceMotion()
    var appeared by remember { mutableStateOf(false) }
    val progress = remember { Animatable(if (reduceMotion) 1f else 0f) }
    FeedbackOnChange(appeared, HapticFeedbackConstants.CONTEXT_CLICK) // `.sensoryFeedback(.success, trigger: appeared)`
    LaunchedEffect(Unit) {
        appeared = true
        if (!reduceMotion) {
            kotlinx.coroutines.delay(100)
            progress.animateTo(1f, tween(300, easing = CubicBezierEasing(0f, 0f, 0.58f, 1f)))
        }
    }
    Column(
        modifier.fillMaxSize().awBackground().statusBarsPadding().navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PleadSpacing.xl),
    ) {
        Spacer(Modifier.weight(1f))
        Box(contentAlignment = Alignment.Center) {
            if (!reduceMotion) FloatingHearts(Modifier.fillMaxWidth())
            AvatarPair(
                me = store.me?.avatar, partner = store.partner?.avatar, size = 104.dp, markYou = true,
                modifier = Modifier.graphicsLayer {
                    val v = progress.value
                    alpha = v
                    scaleX = 0.95f + 0.05f * v
                    scaleY = 0.95f + 0.05f * v
                },
            )
        }
        Column(
            Modifier.padding(horizontal = PleadSpacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(PleadSpacing.s),
        ) {
            ScalesMark(size = 30.dp)
            Text(PleadCopy.legallyBound, style = PleadFont.hero, textAlign = TextAlign.Center, color = PleadColor.cocoa)
            Text(
                store.partner?.let { "You and ${it.displayName} are officially a couple of record. Congratulations, or condolences." }
                    ?: "Congratulations, or condolences.",
                style = PleadFont.serif(18f),
                textAlign = TextAlign.Center,
                color = PleadColor.subtleText,
            )
            Text(
                PleadCopy.legallyBoundDisclaimer,
                style = PleadFont.ui(12f, FontWeight.Medium),
                color = PleadColor.subtleText.copy(alpha = 0.8f),
                modifier = Modifier.padding(top = PleadSpacing.xs),
            )
        }
        Spacer(Modifier.weight(1f))
        PrimaryButton("Enter the court", systemImage = "arrow.right", modifier = Modifier.padding(horizontal = PleadSpacing.xl).padding(bottom = PleadSpacing.l)) {
            onDone()
        }
    }
}

/** Blush hearts drifting up behind the couple. Only rendered when Reduce Motion is off. */
@Composable
private fun FloatingHearts(modifier: Modifier = Modifier) {
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                // ~30 fps like `TimelineView(.animation(minimumInterval: 1/30))`.
                if (now - last >= 33_000_000L) {
                    last = now
                    t = (now - start) / 1_000_000_000f
                }
            }
        }
    }
    val heart = rememberVectorPainter(SFSymbol.icon("heart.fill"))
    val strong = ColorFilter.tint(PleadColor.blush)
    val soft = ColorFilter.tint(PleadColor.blush.copy(alpha = 0.55f))
    Canvas(modifier.height(260.dp).clearAndSetSemantics { }) {
        val symbol = 20.dp.toPx()
        for (i in 0 until 9) {
            val speed = 0.12 + (i % 3) * 0.04
            val progress = ((t * speed + i / 9.0) % 1.0)
            val x = size.width * (0.1 + 0.8 * ((i * 37) % 9) / 8.0) + sin(t + i.toDouble()) * 10.dp.toPx()
            val y = size.height * (1 - progress)
            val alpha = sin(progress * PI).toFloat().coerceIn(0f, 1f)
            translate(left = (x - symbol / 2).toFloat(), top = (y - symbol / 2).toFloat()) {
                with(heart) { draw(Size(symbol, symbol), alpha = alpha, colorFilter = if (i % 2 == 0) strong else soft) }
            }
        }
    }
}

