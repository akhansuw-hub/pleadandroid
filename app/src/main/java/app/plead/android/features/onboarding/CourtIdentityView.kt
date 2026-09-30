// Port of ArgueWin/Features/Onboarding/CourtIdentityView.swift: Your Court Identity (amendment ak).
package app.plead.android.features.onboarding

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.plead.android.app.AppModel
import app.plead.android.app.DemoHarness
import app.plead.android.designsystem.InlineError
import app.plead.android.designsystem.PixelAvatar
import app.plead.android.designsystem.PixelAvatarView
import app.plead.android.designsystem.PleadSpacing
import app.plead.android.designsystem.PleadType
import app.plead.android.designsystem.SFSymbol
import app.plead.android.designsystem.TextStyleKind
import app.plead.android.designsystem.accessibilityReduceMotion
import app.plead.android.designsystem.awBackground
import app.plead.android.designsystem.pleadShadow
import app.plead.android.designsystem.swiftSpring
import app.plead.android.models.Avatar
import kotlinx.coroutines.launch

// MARK: - Tokens (Your Court Identity + Bring in Your Partner)

/**
 * Centralised sizes and timings for the identity and partner screens (amendment ak: no per-page magic numbers).
 * Colours come from `OnboardingPalette`, spacing from `PleadSpacing`, type from `PleadType`, timings from
 * `OnboardingMotionTokens`.
 */
object OnboardingIdentityTokens {
    /** Editorial headline (Fraunces), scales with font size from `.title`. */
    val headline: TextStyle = PleadType.display(30f, weight = FontWeight.SemiBold, relativeTo = TextStyleKind.title)
    /** The elegant legal "v." between the two parties. */
    val versus: TextStyle = PleadType.display(34f, weight = FontWeight.SemiBold, italic = true, relativeTo = TextStyleKind.title)
    /** Name on the IN COURT plate. */
    val plateName: TextStyle = PleadType.ui(26f, FontWeight.ExtraBold, relativeTo = TextStyleKind.title2)
    /** Names on the versus tiles. */
    val partyName: TextStyle = PleadType.ui(17f, FontWeight.ExtraBold, relativeTo = TextStyleKind.headline)

    /** Pixel art renders at whole multiples of the 16-cell sprite (never blurred). Points (dp). */
    fun pixelSize(scale: Int): Float = (PixelAvatar.side * scale).toFloat()
    /** The live preview's avatar: 6× (96 pt) on a 116 pt parchment tile. */
    val previewAvatar: Float = pixelSize(6)
    const val previewTile: Float = 116f
    /** Picker tiles: 3× (48 pt) sprite on a 64 pt tile. */
    val pickerAvatar: Float = pixelSize(3)
    const val pickerTile: Float = 64f
    const val pickerRing: Float = 3f
    const val pickerMarker: Float = 20f
    /** The chosen tile grows very slightly, then settles. */
    const val pickerSelectedScale: Float = 1.04f
    /** Versus tiles: 4× (64 pt) sprite. */
    val versusAvatar: Float = pixelSize(4)
    const val versusTileMinHeight: Float = 150f
    /** Name lines before truncation. */
    const val nameLines: Int = 2

    const val plateRadius: Float = 26f
    val tileRadius: Dp = OnboardingRadius.card
    const val cardShadow: Float = 18f
    const val cardShadowY: Float = 8f

    /** Summons envelope on invite: rises this far while fading out. */
    const val envelopeRise: Float = 110f
    const val envelopeDuration: Double = 0.7
    const val envelopeSize: Float = 22f
}

/** Small burgundy uppercase court-state label ("YOUR COURT IDENTITY", "NAME ON THE DOCKET"). */
@Composable
fun IdentityKicker(
    text: String,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = OnboardingPalette.burgundy,
    alignment: Alignment.Horizontal = Alignment.CenterHorizontally,
) {
    Text(
        text.uppercase(),
        style = PleadType.labelCapsTracked,
        color = color,
        textAlign = if (alignment == Alignment.Start) TextAlign.Start else TextAlign.Center,
        modifier = modifier.fillMaxWidth(),
    )
}

/** Fraunces screen headline; keeps `onboarding.title` and the header trait. */
@Composable
fun IdentityEditorialTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = OnboardingIdentityTokens.headline,
        color = OnboardingPalette.wine,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().semantics { heading() }.testTag("onboarding.title"),
    )
}

/** Paper-white input, 14 pt corners, hairline border (Swift `View.onboardingInput()`). */
fun Modifier.onboardingInput(): Modifier {
    val shape = RoundedCornerShape(OnboardingRadius.input)
    return this
        .defaultMinSize(minHeight = 52.dp)
        .background(OnboardingPalette.paper, shape)
        .border(1.dp, OnboardingPalette.border, shape)
        .padding(horizontal = PleadSpacing.l)
}

/** The text style `onboardingInput()` applies (`.system(.body, design: .rounded, weight: .medium)`, cocoa). */
val OnboardingInputTextStyle: TextStyle = TextStyle(fontSize = TextStyleKind.body.defaultSize.sp, fontWeight = FontWeight.Medium, color = OnboardingPalette.cocoa)

// MARK: - Screen

/**
 * Onboarding redesign 2.0 (amendment ak) · Your Court Identity: a large live IN COURT plate, the avatar picker
 * (eight presets or the full creator) and the name on the docket. No login here (amendment p): the CTA creates a
 * Supabase anonymous user when there is no session yet, then writes the profile. The account is secured after
 * the paywall (SecureAccountView).
 * Motion: headline → plate → picker → field → CTA. The plate crossfades on every avatar or name change; the
 * chosen tile scales very slightly and settles (one light haptic). Reduce Motion: fades only.
 */
object CourtIdentityView {
    const val kicker = "Your court identity"
    const val headline = "How should the court know you?"
    const val subtitle = "Build the identity that appears whenever you enter court."
    const val cta = "Save my identity"
    const val nameLabel = "Name on the docket"
    const val nameHelper = "This is how your name will appear during cases."

    /** A customised avatar takes the first tile's place so the selection is always visible. */
    fun isCustom(current: Avatar): Boolean = !OnboardingAvatars.presets.contains(current)

    fun displayed(i: Int, preset: Avatar, current: Avatar): Avatar = if (isCustom(current) && i == 0) current else preset

    fun isSelected(i: Int, preset: Avatar, current: Avatar): Boolean = if (isCustom(current)) i == 0 else current == preset

    /** The tiles showing the selection (always exactly one). */
    fun selectedIndices(current: Avatar): List<Int> =
        OnboardingAvatars.presets.indices.filter { isSelected(it, OnboardingAvatars.presets[it], current) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourtIdentityView(app: AppModel, modifier: Modifier = Modifier) {
    val model = app.onboardingModel
    val auth = app.auth
    val store = app.store
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var customising by remember { mutableStateOf(false) }
    var customAvatar by remember { mutableStateOf(Avatar.default) }
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    /** A relaunch with an existing (anonymous) session is still loading the profile / couple. */
    val accountLoading = auth.userId != null && !store.hasLoaded
    val busy = saving || accountLoading

    // Demo captures only (`AWDemo YES AWDemoName "…"`): long-name screenshots without typing.
    LaunchedEffect(Unit) { DemoHarness.demoName?.let { model.displayName = it.take(30) } }

    /**
     * SAVE MY IDENTITY: anonymous session if there is none (demo mode: the in-memory auth, instantly), then
     * `profiles.display_name` + `avatar_json`.
     */
    val save: () -> Unit = save@{
        if (!OnboardingFlow.canAdvance(OnboardingStep.identity, model.displayName) || saving || accountLoading) return@save
        focus.clearFocus()
        saving = true; error = null
        val name = OnboardingFlow.trimmedName(model.displayName).take(30)
        val avatar = model.avatar
        scope.launch {
            try {
                app.saveCourtIdentity(name, avatar)
            } catch (e: AppModel.CourtIdentityError) {
                error = if (e == AppModel.CourtIdentityError.session) {
                    "Couldn't set up your court identity. Check your connection and try again."
                } else {
                    "Couldn't save your profile. Check your connection and try again."
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (_: Exception) {
                error = "Couldn't save your profile. Check your connection and try again."
            } finally {
                saving = false
            }
        }
    }

    OnboardingShell(
        modifier = modifier,
        hero = {
            Column(verticalArrangement = Arrangement.spacedBy(OnboardingKitTokens.Spacing.section)) {
                Column(verticalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
                    IdentityKicker(CourtIdentityView.kicker, Modifier.pleadReveal(PleadRevealKind.headline))
                    IdentityEditorialTitle(CourtIdentityView.headline, Modifier.pleadReveal(PleadRevealKind.headline))
                    OnboardingSubtitle(CourtIdentityView.subtitle, Modifier.pleadReveal(PleadRevealKind.body))
                }
                CourtIdentityPreview(model.displayName, model.avatar, Modifier.pleadReveal(PleadRevealKind.card, index = 0))
            }
        },
        content = {
            item {
                // The picker arrives as one group: a fade, no per-tile stagger.
                Column(
                    Modifier.pleadReveal(PleadRevealKind.card, index = 1, offset = androidx.compose.ui.geometry.Offset.Zero, scale = 1f, spring = false),
                    verticalArrangement = Arrangement.spacedBy(PleadSpacing.xs),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IdentityKicker("Choose your avatar", Modifier.weight(1f).semantics { heading() }, alignment = Alignment.Start)
                        Row(
                            Modifier
                                .defaultMinSize(minHeight = 44.dp)
                                .clickable {
                                    customAvatar = model.avatar
                                    customising = true
                                }
                                .semantics(mergeDescendants = true) { role = Role.Button }
                                .testTag("onboarding.avatar.customise"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(SFSymbol.icon("paintbrush.pointed"), contentDescription = null, tint = OnboardingPalette.burgundy, modifier = Modifier.size(18.dp))
                            Text("Customise", style = PleadType.ui(15f, FontWeight.Bold, relativeTo = TextStyleKind.subheadline), color = OnboardingPalette.burgundy)
                        }
                    }
                    AvatarPicker(model)
                }
            }
            item {
                Column(Modifier.pleadReveal(PleadRevealKind.body, index = 2), verticalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
                    IdentityKicker(CourtIdentityView.nameLabel, Modifier.semantics { heading() }, alignment = Alignment.Start)
                    BasicTextField(
                        value = model.displayName,
                        onValueChange = { v -> model.displayName = if (v.length > 30) v.take(30) else v },
                        singleLine = true,
                        textStyle = OnboardingInputTextStyle,
                        cursorBrush = SolidColor(OnboardingPalette.burgundy),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { save() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = "Name on the docket"
                                stateDescription = CourtIdentityView.nameHelper
                            }
                            .testTag("onboarding.name"),
                        decorationBox = { inner ->
                            Box(Modifier.fillMaxWidth().onboardingInput(), contentAlignment = Alignment.CenterStart) {
                                if (model.displayName.isEmpty()) {
                                    Text("First name", style = OnboardingInputTextStyle, color = OnboardingPalette.cocoa.copy(alpha = 0.35f))
                                }
                                inner()
                            }
                        },
                    )
                    Text(
                        CourtIdentityView.nameHelper,
                        style = PleadType.metadata,
                        color = OnboardingPalette.secondaryText,
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                }
            }
            item { InlineError(error) }
        },
        cta = {
            CourtPrimaryButton(
                title = CourtIdentityView.cta,
                identifier = "onboarding.primary",
                isLoading = busy,
                enabled = OnboardingFlow.canAdvance(OnboardingStep.identity, model.displayName),
                action = save,
            )
        },
    )

    if (customising) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { customising = false }, sheetState = sheetState, containerColor = OnboardingPalette.cream) {
            Column(Modifier.fillMaxSize().awBackground()) {
                OnboardingSheetBar(
                    title = "Customise avatar",
                    leading = { SheetBarAction("Cancel") { customising = false } },
                    trailing = {
                        SheetBarAction("Done", bold = true) {
                            model.avatar = customAvatar
                            customising = false
                        }
                    },
                )
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(PleadSpacing.l)) {
                    AvatarCreatorView(customAvatar, { customAvatar = it })
                }
            }
        }
    }
}

// MARK: Avatar picker

/** One horizontal row of tiles (eight presets); scrolls sideways on narrow screens and large text. */
@Composable
private fun AvatarPicker(model: OnboardingModel) {
    OnboardingHaptic(OnboardingHaptics.Moment.avatarSelected, model.avatar)
    val presets = OnboardingAvatars.presets
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            // Room for the ring, the marker and the settle scale.
            .padding(vertical = PleadSpacing.m, horizontal = PleadSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(PleadSpacing.m),
    ) {
        presets.forEachIndexed { i, preset ->
            AvatarChoice(
                avatar = CourtIdentityView.displayed(i, preset, model.avatar),
                selected = CourtIdentityView.isSelected(i, preset, model.avatar),
                modifier = Modifier
                    .semantics { contentDescription = "Avatar ${i + 1} of ${presets.size}" }
                    .testTag("onboarding.avatar.$i"),
            ) { model.avatar = preset }
        }
    }
}

// MARK: - CourtIdentityPreview

/**
 * The live IN COURT plate: deep wine card, gold "IN COURT", the pixel avatar large at an integer scale on a
 * parchment tile, the name as it will appear and "COURT MEMBER". Bound to the same state as the name field and
 * the picker; crossfades on every change. Long names wrap to two lines, then truncate. One TalkBack element.
 */
object CourtIdentityPreview {
    const val plateTitle = "IN COURT"
    const val role = "COURT MEMBER"
    const val placeholderName = "YOUR NAME"

    /** The name as the court shows it: trimmed and uppercased; "YOUR NAME" until one is typed. */
    fun displayName(name: String): String {
        val n = OnboardingFlow.trimmedName(name)
        return if (n.isEmpty()) placeholderName else n.uppercase()
    }

    fun hasName(name: String): Boolean = OnboardingFlow.trimmedName(name).isNotEmpty()

    /** Compact one-line form ("IN COURT: ARIF"; "IN COURT: YOU" before a name is typed). */
    fun label(name: String): String {
        val n = OnboardingFlow.trimmedName(name)
        return "IN COURT: ${if (n.isEmpty()) "YOU" else n.uppercase()}"
    }

    /** What TalkBack reads for the whole plate. */
    fun accessibilityText(name: String, avatar: Avatar): String {
        val n = OnboardingFlow.trimmedName(name)
        val who = if (n.isEmpty()) "No name yet" else n
        return "In court preview. $who, court member. ${PixelAvatar.description(avatar)}"
    }
}

/** Earlier name of the preview (kept for existing callers and tests). */
typealias InCourtPreview = CourtIdentityPreview

@Composable
fun CourtIdentityPreview(name: String, avatar: Avatar, modifier: Modifier = Modifier) {
    val T = OnboardingIdentityTokens
    val shape = RoundedCornerShape(T.plateRadius.dp)
    val fade = tween<Float>(OnboardingMotionTokens.previewCrossfade.millis(), easing = androidx.compose.animation.core.CubicBezierEasing(0.42f, 0f, 0.58f, 1f))
    Column(
        modifier
            .fillMaxWidth()
            .pleadShadow(OnboardingPalette.wine.copy(alpha = 0.18f), radius = T.cardShadow.dp, y = T.cardShadowY.dp, shape = shape)
            .background(OnboardingPalette.wine, shape)
            .border(1.dp, OnboardingPalette.gold.copy(alpha = 0.35f), shape)
            .clearAndSetSemantics { contentDescription = CourtIdentityPreview.accessibilityText(name, avatar) }
            .testTag("onboarding.identityPreview")
            .padding(PleadSpacing.l),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PleadSpacing.m),
    ) {
        Text(CourtIdentityPreview.plateTitle, style = PleadType.labelCapsTracked, color = OnboardingPalette.goldLight)
        Box(
            Modifier.size(T.previewTile.dp).background(OnboardingPalette.parchment, RoundedCornerShape(T.tileRadius)),
            contentAlignment = Alignment.Center,
        ) {
            Crossfade(avatar, animationSpec = fade, label = "previewAvatar") { a -> PixelAvatarView(a, size = T.previewAvatar.dp) }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(PleadSpacing.xs)) {
            Crossfade(CourtIdentityPreview.displayName(name), animationSpec = fade, label = "previewName") { shown ->
                Text(
                    shown,
                    style = T.plateName,
                    color = OnboardingPalette.cream.copy(alpha = if (CourtIdentityPreview.hasName(name)) 1f else 0.45f),
                    textAlign = TextAlign.Center,
                    maxLines = T.nameLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(CourtIdentityPreview.role, style = PleadType.labelCapsTracked, color = OnboardingPalette.gold)
        }
    }
}

// MARK: - Avatar tile

/**
 * One avatar tile: paper tile with a hairline; when chosen a burgundy ring and a small gold marker (a check,
 * never colour alone), scaling very slightly and settling. Reduce Motion: no scale, a fade.
 */
@Composable
fun AvatarChoice(avatar: Avatar, selected: Boolean, modifier: Modifier = Modifier, action: () -> Unit) {
    val T = OnboardingIdentityTokens
    val reduceMotion = accessibilityReduceMotion()
    val shape = RoundedCornerShape(T.tileRadius)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (selected && !reduceMotion) T.pickerSelectedScale else 1f,
        if (reduceMotion) tween(OnboardingMotionTokens.previewCrossfade.millis())
        else swiftSpring(OnboardingMotionTokens.cardDuration.start.toFloat(), OnboardingMotionTokens.cardBounce.toFloat()),
        label = "avatarChoice",
    )
    val markerAlpha by animateFloatAsState(if (selected) 1f else 0f, tween(OnboardingMotionTokens.previewCrossfade.millis()), label = "marker")
    Box(
        modifier
            .pleadPress(pressed)
            .pleadMicroSpring(selected, active = selected)
            .scale(scale)
            .clickable(interactionSource = interaction, indication = null, onClick = action)
            .semantics {
                role = Role.Button
                this.selected = selected
            },
    ) {
        Box(
            Modifier
                .size(T.pickerTile.dp)
                .background(if (selected) OnboardingPalette.parchment else OnboardingPalette.paper, shape)
                .border(if (selected) T.pickerRing.dp else 1.dp, if (selected) OnboardingPalette.burgundy else OnboardingPalette.border, shape),
            contentAlignment = Alignment.Center,
        ) {
            PixelAvatarView(avatar, size = T.pickerAvatar.dp)
        }
        if (markerAlpha > 0f) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (T.pickerMarker * 0.3f).dp, y = (-T.pickerMarker * 0.3f).dp)
                    .graphicsLayer {
                        alpha = markerAlpha
                        val s = 0.8f + 0.2f * markerAlpha
                        scaleX = s; scaleY = s
                    }
                    .size(T.pickerMarker.dp)
                    .background(OnboardingPalette.gold, CircleShape)
                    .border(2.dp, OnboardingPalette.cream, CircleShape)
                    .clearAndSetSemantics { },
                contentAlignment = Alignment.Center,
            ) {
                Icon(SFSymbol.icon("checkmark"), contentDescription = null, tint = OnboardingPalette.wine, modifier = Modifier.size((T.pickerMarker * 0.55f).dp))
            }
        }
    }
}
