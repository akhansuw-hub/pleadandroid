// Port of ArgueWin/Features/Settings/SettingsView.swift (SettingsView, AccountDeletion, DeleteAccountSheet,
// HoldToConfirmButton, OnboardingPreviewCover).
//
// Android differences (listed in docs/android-port/STATUS.md): "Manage subscription" opens Google Play's subscriptions
// page (`AppConfig.manageSubscriptionsURL`) where iOS shows `manageSubscriptionsSheet`; store names in copy read
// "Google Play" / "Google account" where iOS says "App Store" / "Apple ID"; Edit avatar and the onboarding preview are
// slots filled by the onboarding wave (3b) views (`EditAvatarView`, the onboarding story screens).
package app.plead.android.features.settings

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.HeartBroken
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.VolunteerActivism
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.plead.android.BuildConfig
import app.plead.android.app.AppModel
import app.plead.android.app.DemoHarness
import app.plead.android.designsystem.AvatarBadge
import app.plead.android.designsystem.PleadColor
import app.plead.android.designsystem.PleadCopy
import app.plead.android.designsystem.PleadFont
import app.plead.android.designsystem.PleadLogo
import app.plead.android.designsystem.PleadMotion
import app.plead.android.designsystem.PleadRadius
import app.plead.android.designsystem.PleadSpacing
import app.plead.android.features.settlement.ConfirmationDialog
import app.plead.android.features.settlement.DialogAction
import app.plead.android.features.settlement.Haptics
import app.plead.android.features.settlement.MessageAlert
import app.plead.android.features.settlement.SheetScaffold
import app.plead.android.models.EdgeError
import app.plead.android.services.Analytics
import app.plead.android.services.AppConfig
import app.plead.android.services.UserDefaults
import app.plead.android.services.uuidString
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import app.plead.android.features.paywall.PaywallCopy

/** Legal pages: Swift `PaywallCopy.termsURL` / `privacyURL` (one definition, in the paywall). */
object SettingsLinks {
    const val termsURL = PaywallCopy.termsURL
    const val privacyURL = PaywallCopy.privacyURL
}

/**
 * Account settings for the paid app: notifications, avatar, subscription, onboarding preview,
 * legal, sign out, account deletion, version.
 *
 * [onDismiss] closes the sheet. [editAvatar] is the onboarding wave's `EditAvatarView` (pushed like a NavigationLink;
 * its `onDone` pops back); [onboardingPreview] is the `OnboardingPreviewCover` body (the onboarding story screens; its
 * `onClose` dismisses the cover). A null slot hides its row until the integrator passes it.
 */
@Composable
fun SettingsView(
    model: AppModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    editAvatar: (@Composable (onDone: () -> Unit) -> Unit)? = null,
    onboardingPreview: (@Composable (onClose: () -> Unit) -> Unit)? = null,
) {
    val store = model.store
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmUnlink by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showOnboardingPreview by remember { mutableStateOf(false) }
    var showEditAvatar by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()

    LaunchedEffect(Unit) {
        model.push.refreshStatus()
        if (BuildConfig.DEBUG) {
            when (DemoHarness.settings) {
                "delete" -> showDelete = true
                "preview" -> if (onboardingPreview != null) showOnboardingPreview = true
                "bottom" -> scroll.animateScrollTo(scroll.maxValue)
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.push.refreshStatus() }

    fun restore() {
        scope.launch {
            working = true
            val ok = runCatching { model.purchases.restore() }.getOrDefault(false)
            if (ok) store.awaitPremium()
            message = if (ok) "Purchases restored." else "No active subscription found for this Google account."
            working = false
        }
    }

    fun unlink() {
        working = true
        scope.launch {
            try {
                store.leaveCouple(); onDismiss()
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                message = (e as? EdgeError)?.message ?: "Couldn't unlink. Try again."
            }
            working = false
        }
    }

    fun open(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    if (showEditAvatar && editAvatar != null) {
        BackHandler { showEditAvatar = false }
        SheetScaffold(cancelTitle = null, onCancel = {}, modifier = modifier, title = "Edit avatar") {
            Box(Modifier.fillMaxWidth()) {
                TextButton(onClick = { showEditAvatar = false }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null, tint = PleadColor.burgundy)
                    Text("Settings", style = PleadFont.body, color = PleadColor.burgundy)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) { editAvatar { showEditAvatar = false } }
        }
        return
    }

    SheetScaffold(
        cancelTitle = null,
        onCancel = onDismiss,
        modifier = modifier,
        title = "Settings",
        confirmTitle = "Done",
        onConfirm = onDismiss,
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .alpha(if (working) 0.6f else 1f)
                    .padding(bottom = PleadSpacing.xl),
            ) {
                NotificationSettingsSections(model.notifications, model.notificationPrefs)

                if (editAvatar != null) {
                    SettingsSection(header = "Profile") {
                        SettingsRowContainer(Modifier.clickable(enabled = !working, role = Role.Button) { showEditAvatar = true }) {
                            val me = store.me
                            if (me != null) {
                                AvatarBadge(me.avatar, size = 30.dp)
                            } else {
                                Icon(Icons.Filled.AccountCircle, contentDescription = null, tint = PleadColor.walnut, modifier = Modifier.size(30.dp))
                            }
                            Text("Edit avatar", style = PleadFont.body, color = PleadColor.cocoa, modifier = Modifier.weight(1f))
                            me?.displayName?.let { Text(it, style = PleadFont.body, color = PleadColor.subtleText, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            Chevron()
                        }
                    }
                }

                SettingsSection(header = "Subscription", footer = "One subscription covers both of you. Only one of you pays.") {
                    SettingsRow("Manage subscription", Icons.Filled.WorkspacePremium, iconTint = PleadColor.gold, subtitle = premiumStatus(model), chevron = true, enabled = !working) {
                        open(AppConfig.manageSubscriptionsURL)
                    }
                    SettingsDivider()
                    SettingsRow("Restore purchases", Icons.Filled.Refresh, enabled = !working) { restore() }
                }

                SettingsSection(header = "About Plead") {
                    if (onboardingPreview != null) {
                        SettingsRow("Preview onboarding", Icons.Filled.SmartDisplay, chevron = true, enabled = !working) {
                            Analytics.track("onboarding_preview_opened")
                            showOnboardingPreview = true
                        }
                        SettingsDivider()
                    }
                    SettingsRow("Terms of Use", Icons.Filled.Description, external = true, enabled = !working) { open(SettingsLinks.termsURL) }
                    SettingsDivider()
                    SettingsRow("Privacy Policy", Icons.Filled.PanTool, external = true, enabled = !working) { open(SettingsLinks.privacyURL) }
                }

                SettingsSection(
                    header = "Account",
                    footerContent = {
                        Column(
                            Modifier.fillMaxWidth().padding(top = PleadSpacing.xl),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(PleadSpacing.s),
                        ) {
                            // About: the primary logo (heart-accent wordmark), clear space kept by the spacing.
                            PleadLogo(width = 128.dp, modifier = Modifier.padding(bottom = PleadSpacing.xs))
                            Text("Version ${AppConfig.appVersion}", style = PleadFont.caption, color = PleadColor.subtleText)
                            Text(
                                "${PleadCopy.tagline}\n${PleadCopy.principle}",
                                style = PleadFont.ui(13f, FontWeight.Normal),
                                color = PleadColor.subtleText,
                                textAlign = TextAlign.Center,
                            )
                        }
                    },
                ) {
                    if (store.couple != null) {
                        SettingsRow("Unlink couple", Icons.Outlined.HeartBroken, destructive = true, enabled = !working) { confirmUnlink = true }
                        SettingsDivider()
                    }
                    SettingsRow("Sign out", Icons.AutoMirrored.Filled.Logout, enabled = !working) {
                        scope.launch { model.signOut(); onDismiss() }
                    }
                    SettingsDivider()
                    SettingsRow("Delete account", Icons.Outlined.Delete, destructive = true, enabled = !working) { showDelete = true }
                }
            }
            if (working) CircularProgressIndicator(color = PleadColor.burgundy, modifier = Modifier.align(Alignment.Center))
        }
    }

    MessageAlert(message) { message = null }
    ConfirmationDialog(
        visible = confirmUnlink,
        title = "Unlink your couple?",
        message = "Cases still in progress end as a mistrial. Cases with a verdict are closed and keep it. Your case history stays readable.",
        actions = listOf(DialogAction("Unlink couple", destructive = true) { unlink() }),
        onDismiss = { confirmUnlink = false },
    )
    if (showDelete) {
        DeleteAccountSheet(model, onClose = { showDelete = false }, onDeleted = onDismiss)
    }
    if (showOnboardingPreview && onboardingPreview != null) {
        OnboardingPreviewCover(onClose = { showOnboardingPreview = false }, content = onboardingPreview)
    }
}

private fun premiumStatus(model: AppModel): String? {
    val couple = model.store.couple ?: return null
    if (!couple.isPremium) return null
    return couple.premiumUntil?.let {
        "Plead subscription · until ${DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(it.atZone(ZoneId.systemDefault()))}"
    } ?: "Plead subscription · active"
}

@Composable
private fun Chevron(external: Boolean = false) {
    Icon(
        if (external) Icons.Filled.NorthEast else Icons.AutoMirrored.Filled.KeyboardArrowRight,   // arrow.up.right / chevron.right
        contentDescription = null,
        tint = PleadColor.subtleText.copy(alpha = 0.6f),
        modifier = Modifier.size(if (external) 15.dp else 20.dp),
    )
}

/** Swift `row(_:systemImage:value:subtitle:chevron:external:destructive:)` as a tappable list row. */
@Composable
private fun SettingsRow(
    title: String,
    icon: ImageVector,
    iconTint: Color? = null,
    value: String? = null,
    subtitle: String? = null,
    chevron: Boolean = false,
    external: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    SettingsRowContainer(Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)) {
        Icon(icon, contentDescription = null, tint = if (destructive) PleadColor.danger else iconTint ?: PleadColor.walnut, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = PleadFont.body, color = if (destructive) PleadColor.danger else PleadColor.cocoa)
            if (subtitle != null) Text(subtitle, style = PleadFont.caption, color = PleadColor.subtleText)
        }
        if (value != null) Text(value, style = PleadFont.body, color = PleadColor.subtleText, maxLines = 1)
        if (chevron || external) Chevron(external)
    }
}

// MARK: - Account deletion

/**
 * Local side of account deletion (CONTRACTS-v2 amendment i): after the server deletes the account,
 * forget everything this device kept for the user so the app starts again at onboarding.
 */
object AccountDeletion {
    const val sheetCopy = "Your account and sign-in are deleted. Your evidence files are removed. Your partner keeps the shared case history with your name shown as ‘Former partner’. If you pay for Plead, manage the subscription in Google Play; deleting the account does not cancel it."

    /** Launch / cold-open state keys (owned by the cold open; matched by prefix, case-insensitive). */
    val launchKeyPrefixes: List<String> = listOf("applaunch", "coldopen", "launchstate", "launch.")

    /**
     * Keys to remove: this user's onboarding scope, the device (pre-auth) onboarding scope, the
     * link-step flag, anything else keyed by the user id, and the launch/cold-open flags.
     */
    fun keysToClear(userId: UUID?, keys: Iterable<String>): List<String> {
        val uid = userId?.uuidString
        return keys.filter { key ->
            val lower = key.lowercase()
            when {
                key.startsWith("onboarding.device.") -> true
                uid != null && key.contains(uid) -> true
                else -> launchKeyPrefixes.any { lower.startsWith(it) }
            }
        }
    }

    fun clearLocalState(userId: UUID?, defaults: UserDefaults = UserDefaults.standard) {
        for (key in keysToClear(userId, defaults.keys)) defaults.removeObject(key)
    }

    /** Friendly copy for a failed `delete_account` call. */
    fun failureMessage(error: Throwable): String {
        val edge = error as? EdgeError ?: return "Couldn't delete your account. Check your connection and try again."
        return when (edge.code) {
            "not_found", "not_implemented" -> "Account deletion isn't available right now. Please try again later."
            else -> edge.message
        }
    }
}

/**
 * Confirmation sheet: plain statement of consequences, hold-to-confirm (1.2 s ring), progress,
 * inline error with retry. On success: clears local flags and signs out (→ onboarding).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeleteAccountSheet(model: AppModel, onClose: () -> Unit, onDeleted: () -> Unit) {
    var phase by remember { mutableStateOf<DeletePhase>(DeletePhase.confirm) }
    val deleting by rememberUpdatedState(phase == DeletePhase.deleting)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden || !deleting })
    val scope = rememberCoroutineScope()

    fun performDelete() {
        if (phase == DeletePhase.deleting) return
        val uid = model.auth.userId
        phase = DeletePhase.deleting
        Analytics.track("account_delete_started")
        scope.launch {
            // `register_push {token: null}` while the session still exists.
            model.push.unregister()
            try {
                model.store.deleteAccount()
                Analytics.track("account_delete_completed")
                AccountDeletion.clearLocalState(uid)
                val demo = model.store.backend == null
                model.auth.signOut()
                if (demo) {
                    // No auth event in demo mode: reset what the auth listener would have.
                    model.store.reset()
                    model.onboarding.userChanged(null, profile = null)
                }
                onClose()
                onDeleted()
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                model.push.resume()
                val code = (e as? EdgeError)?.code ?: "network"
                Analytics.track("account_delete_failed", mapOf("code" to code))
                phase = DeletePhase.failed(AccountDeletion.failureMessage(e))
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (phase != DeletePhase.deleting) onClose() },
        sheetState = sheetState,
        containerColor = PleadColor.cream,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = PleadSpacing.xl)
                    .padding(top = PleadSpacing.s, bottom = PleadSpacing.l),
                verticalArrangement = Arrangement.spacedBy(PleadSpacing.l),
            ) {
                Box(Modifier.size(44.dp).background(PleadColor.danger.copy(alpha = 0.12f), RoundedCornerShape(22.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.DeleteForever, contentDescription = null, tint = PleadColor.danger, modifier = Modifier.size(28.dp))
                }
                Text("Delete your account?", style = PleadFont.title, color = PleadColor.cocoa, modifier = Modifier.semantics { heading() })
                Column(verticalArrangement = Arrangement.spacedBy(PleadSpacing.m)) {
                    Bullet(Icons.Outlined.PersonRemove, "Your account and sign-in are deleted.")
                    Bullet(Icons.Outlined.PhotoLibrary, "Your evidence files are removed.")
                    Bullet(Icons.Outlined.VolunteerActivism, "Your partner keeps the shared case history with your name shown as ‘Former partner’.")
                    Bullet(Icons.Outlined.CreditCard, "If you pay for Plead, manage the subscription in Google Play; deleting the account does not cancel it.")
                }
                Text("This can't be undone.", style = PleadFont.caption, color = PleadColor.subtleText)
                val failed = phase as? DeletePhase.failed
                if (failed != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(PleadColor.danger.copy(alpha = 0.08f), RoundedCornerShape(PleadRadius.tile))
                            .padding(PleadSpacing.m)
                            .semantics(mergeDescendants = true) { },
                        horizontalArrangement = Arrangement.spacedBy(PleadSpacing.s),
                    ) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = PleadColor.danger, modifier = Modifier.size(18.dp))
                        Text(failed.text, style = PleadFont.ui(15f, FontWeight.Medium), color = PleadColor.cocoa)
                    }
                }
            }
            Column(
                Modifier.padding(horizontal = PleadSpacing.xl).padding(bottom = PleadSpacing.s),
                verticalArrangement = Arrangement.spacedBy(PleadSpacing.s),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val shape = RoundedCornerShape(PleadRadius.button)
                when (phase) {
                    DeletePhase.deleting -> Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .background(PleadColor.danger.copy(alpha = 0.85f), shape)
                            .semantics(mergeDescendants = true) { },
                        horizontalArrangement = Arrangement.spacedBy(PleadSpacing.m, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(color = PleadColor.paperWhite, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                        Text("Deleting your account…", style = PleadFont.headline, color = PleadColor.paperWhite)
                    }
                    is DeletePhase.failed -> Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .background(PleadColor.danger, shape)
                            .clickable(role = Role.Button) { performDelete() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Try again", style = PleadFont.headline, color = PleadColor.paperWhite)
                    }
                    DeletePhase.confirm -> {
                        HoldToConfirmButton("Delete my account", duration = 1.2) { performDelete() }
                        Text("Press and hold to confirm", style = PleadFont.caption, color = PleadColor.subtleText, modifier = Modifier.clearAndSetSemantics { })
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .alpha(if (phase == DeletePhase.deleting) 0.45f else 1f)
                        .clickable(enabled = phase != DeletePhase.deleting, role = Role.Button) { onClose() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Cancel", style = PleadFont.headline, color = PleadColor.cocoa)
                }
            }
        }
    }
}

/** The sheet's phase (Swift `Phase`). */
sealed class DeletePhase {
    data object confirm : DeletePhase()
    data object deleting : DeletePhase()
    data class failed(val text: String) : DeletePhase()
}

@Composable
private fun Bullet(icon: ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(PleadSpacing.m), verticalAlignment = Alignment.Top) {
        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = PleadColor.walnut, modifier = Modifier.size(20.dp))
        }
        Text(text, style = PleadFont.body, color = PleadColor.cocoa)
    }
}

/**
 * Destructive button that fires only after being held for [duration] seconds; a ring fills while held
 * and springs back on early release. TalkBack's double-tap confirms directly.
 */
@Composable
fun HoldToConfirmButton(title: String, modifier: Modifier = Modifier, duration: Double = 1.2, action: () -> Unit) {
    val progress = remember { Animatable(0f) }
    var pressing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val currentAction by rememberUpdatedState(action)
    val scale by animateFloatAsState(if (pressing) 0.97f else 1f, tween(120, easing = PleadMotion.easeOut), label = "holdPress")
    val shape = RoundedCornerShape(PleadRadius.button)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .scale(scale)
            .background(PleadColor.danger, shape)
            .clearAndSetSemantics {
                contentDescription = title
                stateDescription = "Permanently deletes your account."
                role = Role.Button
                onClick { currentAction(); true }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    pressing = true
                    var fill: Job? = null
                    fill = scope.launch {
                        progress.animateTo(1f, tween((duration * 1000).toInt(), easing = LinearEasing))
                        Haptics.warning(view)
                        currentAction()
                    }
                    waitForUpOrCancellation()
                    pressing = false
                    if (progress.value < 1f) {
                        fill.cancel()
                        scope.launch { progress.animateTo(0f, tween(200, easing = PleadMotion.easeOut)) }
                    }
                }
            },
        horizontalArrangement = Arrangement.spacedBy(PleadSpacing.m, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(22.dp)) {
            val lw = 3.dp.toPx()
            drawCircle(PleadColor.paperWhite.copy(alpha = 0.3f), radius = size.minDimension / 2 - lw / 2, style = Stroke(lw))
            drawArc(
                PleadColor.paperWhite,
                startAngle = -90f,
                sweepAngle = 360f * progress.value,
                useCenter = false,
                topLeft = Offset(lw / 2, lw / 2),
                size = Size(size.width - lw, size.height - lw),
                style = Stroke(lw, cap = StrokeCap.Round),
            )
        }
        Text(title, style = PleadFont.headline, color = PleadColor.paperWhite)
    }
}

// MARK: - Onboarding preview

/**
 * "Preview onboarding" (brief p.13: onboarding never reappears unless previewed from Settings), as a full-screen
 * cover. The story screens (and their throwaway onboarding model on an in-memory defaults suite, so the user's
 * persisted onboarding state is never touched) are the onboarding wave's; [content] draws them and calls `onClose`
 * when the preview ends.
 */
@Composable
fun OnboardingPreviewCover(onClose: () -> Unit, content: @Composable (onClose: () -> Unit) -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(PleadColor.cream)) { content(onClose) }
    }
}
