// Port of ArgueWin/Features/Onboarding/WidgetSetupInstructionsSheet.swift. SHOW ME HOW on screen 8 (brief §4): how to
// add Plead to the Home Screen, how court updates reach the Lock Screen, and whether they are on. Guidance only:
// Android has no widget permission and Plead never fakes one. `widget_setup_instructions_opened {surface}` fires when
// a card is opened (Home is open on arrival).
//
// Android: widgets are added from the launcher's widget picker; the Lock Screen card explains the ongoing
// "court in session" notification (Android has no Lock Screen widgets on phones), and the Live Activities row is that
// notification's status (Plead's notifications enabled). Changed strings are listed in STATUS.md, wave 3b.
package app.plead.android.features.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.plead.android.app.AppModel
import app.plead.android.app.DemoHarness
import app.plead.android.designsystem.PleadSpacing
import app.plead.android.designsystem.SFSymbol
import app.plead.android.designsystem.TextStyleKind
import app.plead.android.designsystem.pleadShadow
import kotlinx.coroutines.launch
import app.plead.android.features.paywall.PixelSprite
import app.plead.android.features.paywall.PixelGlyph
import app.plead.android.features.paywall.PixelInk

object WidgetSetupInstructionsSheet {
    /** iOS "Add Plead to your iPhone". */
    const val title = "Add Plead to your phone"
    /** iOS "You add widgets yourself, from the Home Screen or Lock Screen. It takes seconds." */
    const val subtitle = "You add widgets yourself, from the Home Screen. It takes seconds."

    /** The cards open on arrival: Home, or `AWWidgetSheet lock|both` (DEBUG). */
    fun initialSurfaces(flag: String?): Set<WidgetSetupSurface> = when (flag) {
        "lock" -> setOf(WidgetSetupSurface.lock)
        "both" -> setOf(WidgetSetupSurface.home, WidgetSetupSurface.lock)
        else -> setOf(WidgetSetupSurface.home)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetSetupInstructionsSheet(app: AppModel, onDismiss: () -> Unit) {
    val model = app.onboardingModel
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(emptySet<WidgetSetupSurface>()) }

    fun open(surface: WidgetSetupSurface) {
        if (surface in expanded) return
        expanded = expanded + surface
        model.widgetInstructionsOpened(surface)
    }

    fun toggle(surface: WidgetSetupSurface) {
        if (surface in expanded) expanded = expanded - surface else open(surface)
    }

    LaunchedEffect(Unit) {
        val initial = WidgetSetupInstructionsSheet.initialSurfaces(if (DemoHarness.isDemo) DemoHarness.widgetSheet else null)
        for (surface in WidgetSetupSurface.entries) if (surface in initial) open(surface)
        model.refreshWidgetSetup()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = OnboardingPalette.cream,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = PleadSpacing.xl)
                    .padding(top = PleadSpacing.xl, bottom = PleadSpacing.m),
                verticalArrangement = Arrangement.spacedBy(PleadSpacing.m),
            ) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PleadSpacing.xs), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        WidgetSetupInstructionsSheet.title,
                        style = TextStyle(fontSize = TextStyleKind.title2.defaultSize.sp, fontWeight = FontWeight.ExtraBold),
                        color = OnboardingPalette.wine,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        WidgetSetupInstructionsSheet.subtitle,
                        style = TextStyle(fontSize = TextStyleKind.subheadline.defaultSize.sp, fontWeight = FontWeight.Medium),
                        color = OnboardingPalette.secondaryText,
                        textAlign = TextAlign.Center,
                    )
                }
                for (surface in WidgetSetupSurface.entries) {
                    WidgetSetupCard(surface, isExpanded = surface in expanded) { toggle(surface) }
                }
                LiveActivitiesStatusRow(enabled = model.liveActivitiesEnabled)
            }
            OnboardingPrimaryButton(
                "Done",
                modifier = Modifier.padding(horizontal = PleadSpacing.xl).padding(vertical = PleadSpacing.s),
            ) {
                scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
            }
        }
    }
}

val WidgetSetupSurface.title: String
    get() = when (this) {
        WidgetSetupSurface.home -> "Home Screen"
        WidgetSetupSurface.lock -> "Lock Screen"
    }

val WidgetSetupSurface.subtitle: String
    get() = when (this) {
        WidgetSetupSurface.home -> "Your case, one glance away"
        WidgetSetupSurface.lock -> "Summons before you unlock"
    }

/** Brief §4 steps, in order (Android: the launcher's widget picker; Lock Screen = the court-session notification). */
val WidgetSetupSurface.steps: List<String>
    get() = when (this) {
        WidgetSetupSurface.home -> listOf(
            "Touch and hold an empty spot on the Home Screen",
            "Tap Widgets",
            "Search for Plead",
            "Choose a size",
            "Drag it onto your Home Screen",
        )
        WidgetSetupSurface.lock -> listOf(
            "Open Settings, then Notifications",
            "Tap Notifications on lock screen",
            "Choose to show notifications",
            "Plead's court updates appear before you unlock",
        )
    }

internal val WidgetSetupSurface.glyph: PixelSprite
    get() = when (this) {
        WidgetSetupSurface.home -> WidgetSetupSprites.homeScreen
        WidgetSetupSurface.lock -> WidgetSetupSprites.lockScreen
    }

/** One expandable guidance card: pixel glyph + title; numbered steps when open. */
@Composable
private fun WidgetSetupCard(surface: WidgetSetupSurface, isExpanded: Boolean, onTap: () -> Unit) {
    val shape = RoundedCornerShape(OnboardingRadius.card)
    val rotation by animateFloatAsState(if (isExpanded) 180f else 0f, tween(220), label = "chevron")
    Column(
        Modifier
            .fillMaxWidth()
            .pleadShadow(OnboardingPalette.cocoa.copy(alpha = 0.05f), radius = 10.dp, y = 3.dp, shape = shape)
            .background(OnboardingPalette.paper, shape)
            .border(
                if (isExpanded) 1.5.dp else 1.dp,
                if (isExpanded) OnboardingPalette.burgundy.copy(alpha = 0.45f) else OnboardingPalette.border,
                shape,
            )
            .padding(PleadSpacing.m + 2.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onTap)
                .clearAndSetSemantics {
                    role = Role.Button
                    contentDescription = "${surface.title} widget steps"
                    stateDescription = if (isExpanded) "Expanded" else "Collapsed"
                    onClick(label = if (isExpanded) "Hides the steps" else "Shows the steps") { onTap(); true }
                },
            horizontalArrangement = Arrangement.spacedBy(PleadSpacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(46.dp).background(OnboardingPalette.parchment, RoundedCornerShape(OnboardingRadius.marker)),
                contentAlignment = Alignment.Center,
            ) {
                PixelGlyph(surface.glyph, Modifier.size(30.dp, 40.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    surface.title,
                    style = TextStyle(fontSize = TextStyleKind.headline.defaultSize.sp, fontWeight = FontWeight.ExtraBold),
                    color = OnboardingPalette.wine,
                )
                Text(
                    surface.subtitle,
                    style = TextStyle(fontSize = TextStyleKind.subheadline.defaultSize.sp, fontWeight = FontWeight.Medium),
                    color = OnboardingPalette.secondaryText,
                )
            }
            Icon(
                SFSymbol.icon("chevron.down"),
                contentDescription = null,
                tint = OnboardingPalette.burgundy,
                modifier = Modifier.size(18.dp).rotate(rotation),
            )
        }
        AnimatedVisibility(visible = isExpanded, enter = fadeIn(tween(220)), exit = fadeOut(tween(220))) {
            Column(Modifier.padding(top = PleadSpacing.m), verticalArrangement = Arrangement.spacedBy(PleadSpacing.s - 1.dp)) {
                surface.steps.forEachIndexed { index, step ->
                    Row(
                        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { },
                        horizontalArrangement = Arrangement.spacedBy(PleadSpacing.m),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(22.dp).background(OnboardingPalette.goldLight, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${index + 1}",
                                style = TextStyle(fontSize = TextStyleKind.caption.defaultSize.sp, fontWeight = FontWeight.ExtraBold),
                                color = OnboardingPalette.wine,
                            )
                        }
                        Text(
                            step,
                            style = TextStyle(fontSize = TextStyleKind.subheadline.defaultSize.sp, fontWeight = FontWeight.SemiBold),
                            color = OnboardingPalette.cocoa,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/** Live update copy (iOS `LiveActivitiesStatusRow` strings, adapted: the court-session notification). */
object LiveActivitiesStatusCopy {
    /** iOS "Live Activities are on — summons and verdicts appear on your Lock Screen automatically." */
    const val on = "Live updates are on — summons and verdicts appear on your Lock Screen automatically."
    /** iOS "Live Activities are off for Plead". */
    const val off = "Live updates are off for Plead"
    /** iOS "Turn them on in Settings › Plead › Live Activities." */
    const val offHint = "Turn them on in Settings › Apps › Plead › Notifications."
}

/** Live update availability. Off → say so and point at Settings (Plead can't turn them on for you). */
@Composable
private fun LiveActivitiesStatusRow(enabled: Boolean) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                if (enabled) OnboardingPalette.parchment.copy(alpha = 0.6f) else OnboardingPalette.blush.copy(alpha = 0.18f),
                RoundedCornerShape(OnboardingRadius.card),
            )
            .padding(PleadSpacing.m + 2.dp),
        verticalArrangement = Arrangement.spacedBy(PleadSpacing.m),
    ) {
        Row(
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) { },
            horizontalArrangement = Arrangement.spacedBy(PleadSpacing.s),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                SFSymbol.icon(if (enabled) "checkmark.circle.fill" else "exclamationmark.circle.fill"),
                contentDescription = null,
                tint = if (enabled) OnboardingPalette.gold else OnboardingPalette.burgundy,
                modifier = Modifier.size(20.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (enabled) LiveActivitiesStatusCopy.on else LiveActivitiesStatusCopy.off,
                    style = TextStyle(fontSize = TextStyleKind.subheadline.defaultSize.sp, fontWeight = FontWeight.Bold),
                    color = OnboardingPalette.wine,
                )
                if (!enabled) {
                    Text(
                        LiveActivitiesStatusCopy.offHint,
                        style = TextStyle(fontSize = TextStyleKind.footnote.defaultSize.sp, fontWeight = FontWeight.Medium),
                        color = OnboardingPalette.secondaryText,
                    )
                }
            }
        }
        if (!enabled) {
            OnboardingSecondaryButton("Open Settings") { openSettings(context) }
        }
    }
}

/** Tiny phone glyphs for the two cards (the app's `PixelInk` palette). */
internal object WidgetSetupSprites {
    /** A phone showing a Home Screen: one wide burgundy widget over a grid of app tiles. */
    val homeScreen = PixelSprite(
        listOf(
            ".kkkkkkkkkkk.",
            "kkkkkkkkkkkkk",
            "kPPPPPPPPPPPk",
            "kPbbbbbPGgGPk",
            "kPbGGGbPgGgPk",
            "kPbbbbbPGgGPk",
            "kPPPPPPPPPPPk",
            "kPppPggPppPPk",
            "kPppPggPppPPk",
            "kPPPPPPPPPPPk",
            "kPggPppPggPPk",
            "kPggPppPggPPk",
            "kPPPPPPPPPPPk",
            "kPppPggPppPPk",
            "kPppPggPppPPk",
            "kPPPPPPPPPPPk",
            "kkkkkkkkkkkkk",
            ".kkkkkkkkkkk.",
        ),
    )

    /** A phone showing a Lock Screen: padlock, the clock, a widget strip. */
    val lockScreen = PixelSprite(
        listOf(
            ".kkkkkkkkkkk.",
            "kkkkkkkkkkkkk",
            "kdddddddddddk",
            "kddddgggddddk",
            "kdddgdddgdddk",
            "kdddgggggdddk",
            "kdddggkggdddk",
            "kdddgggggdddk",
            "kdddddddddddk",
            "kdPPPdPdPPPdk",
            "kdddddddddddk",
            "kdbbbbbbbbbdk",
            "kdbGGGbbGGbdk",
            "kdbbbbbbbbbdk",
            "kdddddddddddk",
            "kddddPPPddddk",
            "kkkkkkkkkkkkk",
            ".kkkkkkkkkkk.",
        ),
    )
}
