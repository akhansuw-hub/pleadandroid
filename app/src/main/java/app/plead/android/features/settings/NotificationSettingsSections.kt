// Port of ArgueWin/Features/Settings/NotificationSettingsSections.swift.
//
// Android: "Open Settings" deep-links to the app's system notification settings (Settings.ACTION_APP_NOTIFICATION_SETTINGS)
// where iOS opens its Settings app. "Show case details on Lock Screen" writes the same `lockscreen_details` pref; the
// notification builder maps it to the notification's lock-screen visibility (private / public) as iOS maps it to generic
// vs detailed copy, and the widgets read it from the App Group mirror as on iOS.
package app.plead.android.features.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.plead.android.designsystem.PleadColor
import app.plead.android.designsystem.PleadFont
import app.plead.android.designsystem.PleadSpacing
import app.plead.android.features.settlement.MessageAlert
import app.plead.android.services.NotificationPermissionService
import app.plead.android.services.NotificationPrefs
import app.plead.android.services.NotificationPrefsModel
import app.plead.android.services.NotificationStatus
import kotlinx.coroutines.launch

/**
 * Settings → Notifications (brief §6; CONTRACTS-v2 amendment o): the system permission row, the four
 * notification preferences, and the Lock Screen privacy toggle. Every toggle writes
 * `profiles.notification_prefs`; "Show case details on Lock Screen" also drives the widgets (App Group).
 */
@Composable
fun NotificationSettingsSections(permission: NotificationPermissionService, prefs: NotificationPrefsModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { permission.refresh() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { permission.refresh() }

    SettingsSection(
        header = "Notifications",
        footer = NotificationSettingsSections.quietHoursFooter,
    ) {
        PermissionRow(permission) {
            if (permission.status == NotificationStatus.notDetermined) {
                scope.launch { permission.request() }
            } else {
                NotificationSettingsSections.openNotificationSettings(context)
            }
        }
        NotificationSettingsSections.pushToggles.forEach { toggle ->
            SettingsDivider()
            ToggleRow(toggle, prefs) { value -> scope.launch { prefs.set(toggle, value) } }
        }
    }

    SettingsSection(header = "Lock Screen privacy", footer = NotificationSettingsSections.privacyFooter) {
        ToggleRow(NotificationPrefs.Toggle.lockscreenDetails, prefs) { value -> scope.launch { prefs.set(NotificationPrefs.Toggle.lockscreenDetails, value) } }
    }
    MessageAlert(prefs.errorMessage) { prefs.errorMessage = null }
}

object NotificationSettingsSections {
    val pushToggles: List<NotificationPrefs.Toggle> = listOf(
        NotificationPrefs.Toggle.summons, NotificationPrefs.Toggle.verdict, NotificationPrefs.Toggle.settlement, NotificationPrefs.Toggle.reminders,
    )
    const val quietHoursFooter = "Your scheduled verdict notification arrives at the time you chose, even during quiet hours (22:30–08:30)."
    const val privacyFooter = "Off keeps the Lock Screen, widgets and notification previews generic, like “You've been summoned”, with no case titles or names."

    /** iOS `statusText`, with the platform named for Android. */
    fun statusText(status: NotificationStatus): String = when (status) {
        NotificationStatus.authorized -> "On"
        NotificationStatus.provisional, NotificationStatus.ephemeral -> "On, delivered quietly"
        NotificationStatus.denied -> "Off in Android Settings. Court notices can't reach you."
        NotificationStatus.notDetermined -> "Not set yet"
    }

    /** `UIApplication.openNotificationSettingsURLString` → this app's notification settings page. */
    fun openNotificationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            // Fallback: the app's details page.
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    /** The SF Symbol each toggle names on iOS, as a Material icon. */
    fun icon(toggle: NotificationPrefs.Toggle): ImageVector = when (toggle) {
        NotificationPrefs.Toggle.summons -> Icons.Filled.MarkEmailUnread            // envelope.badge.fill
        NotificationPrefs.Toggle.verdict -> Icons.Filled.AccountBalance             // building.columns.fill
        NotificationPrefs.Toggle.settlement -> Icons.Filled.PanTool                 // hand.raised.fingers.spread.fill
        NotificationPrefs.Toggle.reminders -> Icons.Filled.Alarm                    // alarm.fill
        NotificationPrefs.Toggle.lockscreenDetails -> Icons.Filled.ScreenLockPortrait // lock.rectangle.on.rectangle.fill
    }
}

// MARK: Rows

@Composable
private fun PermissionRow(permission: NotificationPermissionService, onAction: () -> Unit) {
    val status = permission.status
    SettingsRowContainer {
        Icon(
            if (status.isAllowed) Icons.Filled.NotificationsActive else Icons.Filled.NotificationsOff,   // bell.badge.fill / bell.slash.fill
            contentDescription = null,
            tint = PleadColor.walnut,
            modifier = Modifier.size(22.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Allow notifications", style = PleadFont.body, color = PleadColor.cocoa)
            Text(
                NotificationSettingsSections.statusText(status),
                style = PleadFont.caption,
                color = if (status == NotificationStatus.denied) PleadColor.burgundy else PleadColor.subtleText,
            )
        }
        Spacer(Modifier.width(PleadSpacing.s))
        Text(
            if (status == NotificationStatus.notDetermined) "Allow" else "Open Settings",
            style = PleadFont.ui(14f, FontWeight.Bold),
            color = if (status.isAllowed) PleadColor.burgundy else PleadColor.paperWhite,
            modifier = Modifier
                .background(if (status.isAllowed) PleadColor.burgundy.copy(alpha = 0.1f) else PleadColor.burgundy, CircleShape)
                .clickable(role = Role.Button, onClickLabel = if (status == NotificationStatus.notDetermined) {
                    "Asks Android to allow notifications"
                } else {
                    "Opens Plead's notification settings in Android Settings"
                }, onClick = onAction)
                .padding(horizontal = PleadSpacing.m, vertical = 7.dp),
        )
    }
}

@Composable
private fun ToggleRow(toggle: NotificationPrefs.Toggle, prefs: NotificationPrefsModel, onChange: (Boolean) -> Unit) {
    val on = prefs.prefs[toggle]
    SettingsRowContainer(
        modifier = Modifier
            .semantics(mergeDescendants = true) { stateDescription = if (on) "On" else "Off" }
            .clickable(role = Role.Switch) { onChange(!on) },
    ) {
        Icon(NotificationSettingsSections.icon(toggle), contentDescription = null, tint = PleadColor.walnut, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(toggle.title, style = PleadFont.body, color = PleadColor.cocoa)
            Text(toggle.subtitle, style = PleadFont.caption, color = PleadColor.subtleText)
        }
        Switch(
            checked = on,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedTrackColor = PleadColor.burgundy,
                checkedThumbColor = PleadColor.paperWhite,
                checkedBorderColor = PleadColor.burgundy,
                uncheckedTrackColor = PleadColor.separator,
                uncheckedThumbColor = PleadColor.paperWhite,
                uncheckedBorderColor = PleadColor.separator,
            ),
        )
    }
}

// MARK: - Inset grouped list (SwiftUI `List` + `Section`)

/** One `Section`: an uppercased header, the rows on a rounded paper-white card, an optional footer. */
@Composable
fun SettingsSection(
    header: String?,
    modifier: Modifier = Modifier,
    footer: String? = null,
    footerContent: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = PleadSpacing.l)) {
        if (header != null) {
            Text(
                header.uppercase(),
                style = PleadFont.ui(13f, FontWeight.Normal),
                color = PleadColor.subtleText,
                modifier = Modifier.padding(start = PleadSpacing.l, bottom = 6.dp, top = PleadSpacing.l).semantics { heading() },
            )
        } else {
            Spacer(Modifier.heightIn(min = PleadSpacing.l))
        }
        Column(Modifier.fillMaxWidth().background(PleadColor.paperWhite, RoundedCornerShape(10.dp)), content = content)
        if (footer != null) {
            Text(
                footer,
                style = PleadFont.ui(13f, FontWeight.Normal),
                color = PleadColor.subtleText,
                modifier = Modifier.padding(start = PleadSpacing.l, end = PleadSpacing.l, top = 6.dp),
            )
        }
        footerContent?.invoke()
    }
}

/** A list row: ≥ 44 dp, 16 dp insets, icon column, content. */
@Composable
fun SettingsRowContainer(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = PleadSpacing.l, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(PleadSpacing.m + 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** The inset hairline between rows (aligned with the titles, as iOS insets it past the icon). */
@Composable
fun SettingsDivider() {
    HorizontalDivider(Modifier.padding(start = 54.dp), thickness = 0.5.dp, color = PleadColor.separator)
}
