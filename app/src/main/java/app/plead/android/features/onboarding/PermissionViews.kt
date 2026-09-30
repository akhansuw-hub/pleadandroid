// Port of ArgueWin/Features/Onboarding/PermissionViews.swift. Onboarding redesign 2.0 (CONTRACTS-v2 amendment ak):
// Court Notices and Privacy. The system prompts fire only from the primary CTA (through the services), never on
// appear; NOT NOW never touches them, and when the OS has already decided the screen shows that state with a plain
// CONTINUE instead.
//
// Android (amendment az): POST_NOTIFICATIONS is the notification prompt (`NotificationPermissionService.request()`,
// through the prompt MainActivity installs). There is no App Tracking Transparency, so the Privacy screen
// (`.tracking`) is not an active onboarding step (`OnboardingStep.skipsTracking`); it is ported whole for parity
// (its copy is tested) and, if shown, reads the always-`authorized` `TrackingPermissionService`.
package app.plead.android.features.onboarding

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.plead.android.R
import app.plead.android.app.AppModel
import app.plead.android.designsystem.PleadSpacing
import app.plead.android.designsystem.PleadType
import app.plead.android.designsystem.SFSymbol
import app.plead.android.designsystem.TextStyleKind
import app.plead.android.designsystem.pleadShadow
import app.plead.android.services.Analytics
import app.plead.android.services.NotificationPermissionService
import app.plead.android.services.NotificationStatus
import app.plead.android.services.PermissionCTA
import app.plead.android.services.TrackingPermissionService
import app.plead.android.services.TrackingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// MARK: - Tokens

/** Centralised sizes for the permission screens (Court Notices, Widgets & Live Activities, Privacy). */
object PermissionScreenTokens {
    /** Notification banner: app icon tile and corner radii (the iOS banner proportions). */
    val noticeIcon: Dp = 38.dp
    val noticeIconRadius: Dp = 9.dp
    val noticeRadius: Dp = 22.dp
    val noticeSpacing: Dp = PleadSpacing.s + 2.dp
    /** Judge Wigsworth peeking over the first notice: glyph size, inset from the card's edge, how far he shows. */
    val judgePeekSize: Dp = 46.dp
    val judgePeekInset: Dp = PleadSpacing.xl
    val judgePeekVisible: Dp = 30.dp
    /** Assurance / reassurance card icon tile. */
    val assuranceIcon: Dp = 36.dp
    /** Privacy hero: padlock body and shackle. */
    val lockBody = DpSize(92.dp, 72.dp)
    val lockShackle = DpSize(52.dp, 40.dp)
    val lockStroke: Dp = 5.dp
    val lockHeart: Dp = 34.dp
    /** Widgets hero: the device mockup, the overlapping Home Screen widget, and the whole composition. */
    val heroWidth: Dp = 320.dp
    val phoneWidth: Dp = 244.dp
    val phoneHeight: Dp = 296.dp
    val heroHeight: Dp = 318.dp
    val phoneRadius: Dp = 36.dp
    val phoneBezel: Dp = 5.dp
    val homeWidgetTop: Dp = 196.dp
    const val homeWidgetScale: Float = 0.70f
    val pillVertical: Dp = 7.dp
    /** Entrance extras (amendment ak motion: 6–12 pt rises), in points. */
    val widgetFloat = Offset(10f, 10f)
    const val judgeRise: Float = 12f
}

// MARK: - Rules (pure, unit-tested)

/** What a permission screen may do, and when. The system prompts are only ever asked for from a CTA tap. */
object OnboardingPermissionRules {
    enum class Trigger { appear, primaryTap }

    /** Court Notices: the notification prompt only after the CTA, and only while the OS hasn't decided. */
    fun requestsNotifications(on: Trigger, status: NotificationStatus): Boolean =
        on == Trigger.primaryTap && NotificationPermissionService.cta(status) == PermissionCTA.requestNative

    /**
     * Privacy & tracking (amendment at): Apple's ATT prompt only after the CTA, and only while iOS hasn't decided.
     * Whatever the answer (or when already decided) the step then advances: tracking never blocks onboarding.
     * Android: the status is always determined (`authorized`), so this is always false outside tests.
     */
    fun requestsTracking(on: Trigger, status: TrackingStatus): Boolean =
        on == Trigger.primaryTap && TrackingPermissionService.cta(status) == PermissionCTA.requestNative
}

// MARK: - Shared header

/** Eyebrow (tracked caps) + editorial serif headline + supporting line, centred. Headline, then copy. */
@Composable
fun PermissionScreenHeader(
    eyebrow: String,
    headline: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    /** Calm screens (Privacy) fade without rising. */
    calm: Boolean = false,
) {
    val still = if (calm) Offset.Zero else null
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PleadSpacing.s), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            eyebrow.uppercase(),
            style = PleadType.labelCapsTracked,
            color = OnboardingPalette.burgundy,
            textAlign = TextAlign.Center,
            modifier = Modifier.pleadReveal(PleadRevealKind.headline, offset = still),
        )
        Text(
            headline,
            style = PleadType.displayXL,
            color = OnboardingPalette.wine,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { heading() }
                .testTag("onboarding.title")
                .pleadReveal(PleadRevealKind.headline, offset = still),
        )
        Text(
            subtitle,
            style = PleadType.body,
            color = OnboardingPalette.secondaryText,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = PleadSpacing.xs)
                .pleadReveal(PleadRevealKind.body, offset = still),
        )
    }
}

// MARK: 8 · Court Notices

object CourtNoticesCopy {
    const val eyebrow = "Court notices"
    const val headline = "Don't miss your summons."
    const val subtitle = "We'll only notify you when something in court needs your attention."
    const val spamTitle = "No notification spam"
    const val spamBody = "Summons, your turn, verdicts and judgements only. Choose which ones in Settings."
    const val enable = "Enable court notices"
}

/**
 * A realistic Plead notification, banner style (app icon, PLEAD, time, title, body). Reusable: onboarding,
 * empty states, marketing. The wording is the real lock-screen-safe push copy (`_shared/copy.ts` PUSH_COPY,
 * generic lines: no case names, no partner names, no emojis).
 */
object SummonsNotificationPreview {
    enum class Moment(val rawValue: String) {
        summoned("summoned"), verdictReady("verdictReady"), judgementDue("judgementDue");

        /** The court moment, for TalkBack ("Summoned", "Verdict ready", "Judgement due"). Swift `name`. */
        val displayName: String
            get() = when (this) {
                summoned -> "Summoned"
                verdictReady -> "Verdict ready"
                judgementDue -> "Judgement due"
            }

        /** `summons`, `verdict_ready`, `judgement_reminder` titles. */
        val title: String
            get() = when (this) {
                summoned -> "You've been summoned"
                verdictReady -> "All rise"
                judgementDue -> "Judgement due soon"
            }

        val body: String
            get() = when (this) {
                summoned -> "Your partner has filed a case. The court awaits your plea."
                verdictReady -> "The judge has ruled. Your verdict is ready."
                judgementDue -> "The court's judgement is still outstanding."
            }

        /** Banner timestamps, newest first. */
        val time: String
            get() = when (this) {
                summoned -> "now"
                verdictReady -> "2h ago"
                judgementDue -> "1d ago"
            }
    }
}

@Composable
fun SummonsNotificationPreview(moment: SummonsNotificationPreview.Moment, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(PermissionScreenTokens.noticeRadius)
    Row(
        modifier
            .fillMaxWidth()
            .pleadShadow(OnboardingPalette.cocoa.copy(alpha = 0.10f), radius = 12.dp, y = 5.dp, shape = shape)
            .background(OnboardingPalette.paper, shape)
            .border(1.dp, OnboardingPalette.border, shape)
            .padding(PleadSpacing.m)
            .clearAndSetSemantics { contentDescription = "Example notification, ${moment.displayName}. ${moment.title}. ${moment.body}" },
        horizontalArrangement = Arrangement.spacedBy(PleadSpacing.m),
        verticalAlignment = Alignment.Top,
    ) {
        PleadAppIconTile(size = PermissionScreenTokens.noticeIcon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("PLEAD", style = PleadType.labelCapsTracked, color = OnboardingPalette.cocoa.copy(alpha = 0.62f))
                Spacer(Modifier.weight(1f).padding(start = PleadSpacing.s))
                Text(moment.time, style = PleadType.caption, color = OnboardingPalette.cocoa.copy(alpha = 0.55f))
            }
            Text(moment.title, style = PleadType.titleM, color = OnboardingPalette.wine)
            Text(moment.body, style = PleadType.metadataMedium, color = OnboardingPalette.cocoa.copy(alpha = 0.85f))
        }
    }
}

/** The Plead app icon (pixel heart over the scales on cream), as it appears on a notification. */
@Composable
fun PleadAppIconTile(modifier: Modifier = Modifier, size: Dp = PermissionScreenTokens.noticeIcon) {
    val shape = RoundedCornerShape(size * (PermissionScreenTokens.noticeIconRadius / PermissionScreenTokens.noticeIcon))
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(OnboardingPalette.cream, shape)
            .border(0.5.dp, OnboardingPalette.border, shape)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = ImageBitmap.imageResource(R.drawable.plead_heart_scales),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.None,
            modifier = Modifier.fillMaxSize().padding(size * 0.14f),
        )
    }
}

/**
 * Motion (amendment ak): header, then the three notices drop in one by one like banners, Judge Wigsworth
 * peeks over the first once it has landed, the NO NOTIFICATION SPAM card follows, the CTA last. Nothing loops.
 * ENABLE goes straight to the real system prompt; a denial just moves on. One light haptic when notices end up on.
 */
@Composable
fun NotificationPermissionView(app: AppModel, modifier: Modifier = Modifier) {
    val service = app.notifications
    val requesting = remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val context = LocalContext.current
    val cta = NotificationPermissionService.cta(service.status)
    val moments = SummonsNotificationPreview.Moment.entries
    OnboardingShell(
        modifier = modifier,
        hero = {
            PermissionScreenHeader(eyebrow = CourtNoticesCopy.eyebrow, headline = CourtNoticesCopy.headline, subtitle = CourtNoticesCopy.subtitle)
        },
        content = {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(top = PermissionScreenTokens.judgePeekVisible),
                    verticalArrangement = Arrangement.spacedBy(PermissionScreenTokens.noticeSpacing),
                ) {
                    moments.forEachIndexed { index, moment ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .zIndex(-index.toFloat())
                                .pleadReveal(
                                    PleadRevealKind.card,
                                    index = if (index == 0) 0 else index + 1,
                                    offset = Offset(0f, -OnboardingMotionTokens.notificationDrop),
                                    scale = 1f,
                                ),
                        ) {
                            // `.background(alignment: .topTrailing) { JudgePeek() }`: drawn behind the first notice.
                            if (index == 0) JudgePeek(Modifier.align(Alignment.TopEnd))
                            SummonsNotificationPreview(moment)
                        }
                    }
                }
            }
            item {
                NoSpamCard(Modifier.pleadReveal(PleadRevealKind.card, index = moments.size + 1, scale = 1f, spring = false))
            }
            if (cta == PermissionCTA.continueOnly) {
                item {
                    // Never hidden behind motion: the system state is plain text from the first frame.
                    PermissionStateRow(
                        allowed = service.status.isAllowed,
                        onText = "Court notices are on.",
                        offText = "Court notices are off. You can turn them on in Settings.",
                    )
                }
            }
        },
        cta = {
            when (cta) {
                PermissionCTA.requestNative -> {
                    CourtPrimaryButton(
                        title = CourtNoticesCopy.enable,
                        identifier = "onboarding.notices.enable",
                        enabled = !requesting.value,
                    ) { allow(app, scope, view, requesting) }
                    OnboardingSecondaryButton("Not now") {
                        Analytics.track("notification_preprompt_skip")
                        app.onboarding.advance()
                    }
                }
                PermissionCTA.continueOnly -> {
                    CourtPrimaryButton(title = "Continue", identifier = "onboarding.notices.continue") { app.onboarding.advance() }
                    if (!service.status.isAllowed) {
                        OnboardingSecondaryButton("Open Settings") { openSettings(context) }
                    }
                }
            }
        },
    )
}

/** Judge Wigsworth, head and shoulders, peeking over the first notice from behind it. Rises once (Reduce Motion: fades). */
@Composable
private fun JudgePeek(modifier: Modifier = Modifier) {
    Box(
        modifier
            .offset(x = -PermissionScreenTokens.judgePeekInset, y = -PermissionScreenTokens.judgePeekVisible)
            .size(PermissionScreenTokens.judgePeekSize)
            .pleadReveal(PleadRevealKind.card, index = 1, offset = Offset(0f, PermissionScreenTokens.judgeRise), scale = 1f)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        PixelJudgeGlyph(PixelJudgeGlyph.Kind.judge, size = PermissionScreenTokens.judgePeekSize)
    }
}

@Composable
private fun NoSpamCard(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(OnboardingRadius.card)
    Row(
        modifier
            .fillMaxWidth()
            .background(OnboardingPalette.parchment, shape)
            .border(1.dp, OnboardingPalette.border, shape)
            .padding(PleadSpacing.m)
            .semantics(mergeDescendants = true) { },
        horizontalArrangement = Arrangement.spacedBy(PleadSpacing.m),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .size(PermissionScreenTokens.assuranceIcon)
                .background(OnboardingPalette.paper, RoundedCornerShape(OnboardingRadius.marker))
                .clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) {
            Icon(SFSymbol.icon("bell.badge"), contentDescription = null, tint = OnboardingPalette.burgundy, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PleadSpacing.xs)) {
            Text(CourtNoticesCopy.spamTitle.uppercase(), style = PleadType.labelCapsTracked, color = OnboardingPalette.burgundy)
            Text(CourtNoticesCopy.spamBody, style = PleadType.metadataMedium, color = OnboardingPalette.cocoa)
        }
    }
}

// MARK: 10 · Privacy & tracking (the `.tracking` step: shown on iOS; not an active step on Android, amendment az)

/**
 * Plain-language assurances that match what Plead actually does (privacy policy §6 and security section:
 * access limited to the linked couple, private evidence storage; AppsFlyer attribution only with the user's ATT
 * permission, amendment at). No claims about encryption, retention or AI training; no incentive to allow tracking.
 */
object PrivacyCopy {
    const val eyebrow = "Privacy"
    const val headline = "Your arguments stay between you."
    const val subtitle = "Cases can hold personal conversations, photos and screenshots. Plead treats them as private."
    const val cta = "Continue"

    data class Assurance(val title: String, val body: String, val symbol: String)

    val assurances: List<Assurance> = listOf(
        Assurance(
            title = "Private cases",
            body = "Your cases aren't public. Only you and your linked partner can open them.",
            symbol = "person.2.fill",
        ),
        Assurance(
            title = "Your evidence",
            body = "Screenshots and photos you add are kept in private storage, attached to your case.",
            symbol = "photo.on.rectangle.angled",
        ),
        Assurance(
            title = "Ad measurement",
            body = "If you allow tracking, it helps us understand which ads bring people to Plead. Your cases and evidence are never used for advertising.",
            symbol = "hand.raised.fill",
        ),
    )

    /** Above the CTA while iOS hasn't decided: says what happens next, neutrally (no incentive, no pressure). */
    const val promptNote = "Apple will ask next. Plead works the same whichever you choose."
    /** When the OS has already decided (no second ask). */
    const val trackingAllowed = "Tracking is allowed."
    const val trackingOff = "Tracking is off. You can change this in Settings."
}

/**
 * Motion (amendment ak): intentionally calm. Fades only: the lock, the headline, the copy, then the three
 * assurances one after another, the CTA last. No rises, springs, stamps or haptics.
 * CONTINUE (amendment at) asks for App Tracking Transparency while iOS hasn't decided, then advances whatever the
 * answer; when already decided it shows the state and just advances (always the case on Android).
 */
@Composable
fun PrivacyScreenView(app: AppModel, modifier: Modifier = Modifier) {
    val service = app.tracking
    val requesting = remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    OnboardingShell(
        modifier = modifier,
        hero = {
            Column(verticalArrangement = Arrangement.spacedBy(PleadSpacing.xl), horizontalAlignment = Alignment.CenterHorizontally) {
                PrivacyLockHero(
                    Modifier.pleadReveal(PleadRevealKind.card, offset = Offset.Zero, scale = 1f, spring = false, delay = -OnboardingMotionTokens.cardDelay),
                )
                PermissionScreenHeader(eyebrow = PrivacyCopy.eyebrow, headline = PrivacyCopy.headline, subtitle = PrivacyCopy.subtitle, calm = true)
            }
        },
        content = {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(PleadSpacing.m)) {
                    PrivacyCopy.assurances.forEachIndexed { index, item ->
                        PrivacyAssuranceRow(
                            item,
                            Modifier.pleadReveal(PleadRevealKind.card, index = index, offset = Offset.Zero, scale = 1f, spring = false),
                        )
                    }
                    if (TrackingPermissionService.cta(service.status) == PermissionCTA.continueOnly) {
                        PermissionStateRow(
                            allowed = service.status == TrackingStatus.authorized,
                            onText = PrivacyCopy.trackingAllowed,
                            offText = PrivacyCopy.trackingOff,
                        )
                    }
                }
            }
        },
        cta = {
            Column(verticalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
                // Pinned with the CTA so it is always read in full before the prompt (never under the button).
                if (TrackingPermissionService.cta(service.status) == PermissionCTA.requestNative) {
                    Text(
                        PrivacyCopy.promptNote,
                        style = PleadType.metadataMedium,
                        color = OnboardingPalette.cocoa,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().testTag("onboarding.privacy.note"),
                    )
                }
                CourtPrimaryButton(
                    title = PrivacyCopy.cta,
                    identifier = "onboarding.privacy.continue",
                    enabled = !requesting.value,
                ) { proceed(app, scope, requesting) }
            }
        },
    )
}

/** A simple padlock with a pixel heart on its body (hero of the Privacy screen). */
@Composable
fun PrivacyLockHero(modifier: Modifier = Modifier) {
    val t = PermissionScreenTokens
    val bodyShape = RoundedCornerShape(OnboardingRadius.card)
    Column(
        modifier.padding(top = PleadSpacing.s).clearAndSetSemantics { },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The padlock's shackle: an upside-down U.
        Canvas(Modifier.size(t.lockShackle)) {
            val stroke = t.lockStroke.toPx()
            val r = size.width / 2
            val p = Path().apply {
                moveTo(0f, size.height)
                lineTo(0f, r)
                arcTo(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, 2 * r), 180f, 180f, false)
                lineTo(size.width, size.height)
            }
            drawPath(p, OnboardingPalette.burgundy, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Box(
            Modifier
                .offset(y = -t.lockStroke)
                .size(t.lockBody)
                .background(OnboardingPalette.paper, bodyShape)
                .border(t.lockStroke, OnboardingPalette.burgundy, bodyShape),
            contentAlignment = Alignment.Center,
        ) {
            PixelJudgeGlyph(PixelJudgeGlyph.Kind.heart, size = t.lockHeart)
        }
    }
}

/** SF Symbols of this file that `SFSymbol` has no mapping for, as the closest Material icons. */
internal fun permissionSymbol(name: String): ImageVector = when (name) {
    "person.2.fill" -> Icons.Filled.People
    "photo.on.rectangle.angled" -> Icons.Filled.PhotoLibrary
    "bell.slash.fill" -> Icons.Filled.NotificationsOff
    "flashlight.off.fill" -> Icons.Filled.FlashlightOff
    "camera.fill" -> Icons.Filled.CameraAlt
    else -> SFSymbol.icon(name)
}

@Composable
private fun PrivacyAssuranceRow(assurance: PrivacyCopy.Assurance, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(OnboardingRadius.card)
    Row(
        modifier
            .fillMaxWidth()
            .pleadShadow(OnboardingPalette.cocoa.copy(alpha = 0.06f), radius = 8.dp, y = 3.dp, shape = shape)
            .background(OnboardingPalette.paper, shape)
            .border(1.dp, OnboardingPalette.border, shape)
            .padding(PleadSpacing.l)
            .semantics(mergeDescendants = true) { },
        horizontalArrangement = Arrangement.spacedBy(PleadSpacing.m),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .size(PermissionScreenTokens.assuranceIcon)
                .background(OnboardingPalette.blush.copy(alpha = 0.28f), RoundedCornerShape(OnboardingRadius.marker))
                .clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) {
            Icon(permissionSymbol(assurance.symbol), contentDescription = null, tint = OnboardingPalette.burgundy, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PleadSpacing.xs)) {
            Text(assurance.title.uppercase(), style = PleadType.labelCapsTracked, color = OnboardingPalette.wine)
            Text(assurance.body, style = PleadType.metadataMedium, color = OnboardingPalette.cocoa)
        }
    }
}

// MARK: - Pieces

/** The OS already decided: say so in words and a symbol (never colour alone). */
@Composable
fun PermissionStateRow(allowed: Boolean, onText: String, offText: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(OnboardingRadius.card)
    Row(
        modifier
            .fillMaxWidth()
            .background(OnboardingPalette.paper, shape)
            .border(1.dp, OnboardingPalette.border, shape)
            .padding(PleadSpacing.m + 2.dp)
            .semantics(mergeDescendants = true) { },
        horizontalArrangement = Arrangement.spacedBy(PleadSpacing.s),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            if (allowed) SFSymbol.icon("checkmark.circle.fill") else permissionSymbol("bell.slash.fill"),
            contentDescription = null,
            tint = if (allowed) OnboardingPalette.gold else OnboardingPalette.mahogany,
            modifier = Modifier.size(20.dp),
        )
        Text(
            if (allowed) onText else offText,
            style = PleadType.ui(TextStyleKind.subheadline.defaultSize, FontWeight.SemiBold, relativeTo = TextStyleKind.subheadline),
            color = OnboardingPalette.cocoa,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Swift `openSettings()`: this app's notification settings (fallback: the app's details page). */
fun openSettings(context: Context) {
    val notifications = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(notifications) }.onFailure {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

// MARK: - CTA handlers (the only places the prompts are requested; a source test guards this)

/** Court Notices' ENABLE: the system prompt only while undecided, then advance whatever the answer. */
private fun allow(app: AppModel, scope: CoroutineScope, view: View, requesting: MutableState<Boolean>) {
    if (requesting.value) return
    val service = app.notifications
    if (!OnboardingPermissionRules.requestsNotifications(OnboardingPermissionRules.Trigger.primaryTap, service.status)) {
        app.onboarding.advance()
        return
    }
    requesting.value = true
    Analytics.track("notification_preprompt_accept")
    scope.launch {
        val status = service.request()
        Analytics.track("notification_permission_result", mapOf("status" to status.rawValue))
        if (status.isAllowed) OnboardingHaptics.fire(view, OnboardingHaptics.Moment.notificationsEnabled)
        requesting.value = false
        app.onboarding.advance()
    }
}

/** Privacy's CONTINUE: ATT while undecided (never on Android), then advance whatever the answer. */
private fun proceed(app: AppModel, scope: CoroutineScope, requesting: MutableState<Boolean>) {
    if (requesting.value) return
    val service = app.tracking
    if (!OnboardingPermissionRules.requestsTracking(OnboardingPermissionRules.Trigger.primaryTap, service.status)) {
        app.onboarding.advance()
        return
    }
    requesting.value = true
    scope.launch {
        val status = service.request()
        Analytics.track("att_permission_result", mapOf("status" to status.rawValue))
        requesting.value = false
        app.onboarding.advance()
    }
}
