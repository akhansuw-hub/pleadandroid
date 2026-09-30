// Port of the DEBUG `WidgetPreviewHarness` in ArgueWin/Services/WidgetSnapshotStore.swift:
// `AWWidgetPreview lock|states|tinted|small|medium|activity|live` (`YES` = lock; works without AWDemo) overlays an
// in-app page rendering the widget families with sample snapshots, for screenshots without placing widgets by hand;
// `AWWidgetPreviewType large|xl|xxl|ax1|ax3|ax5` forces a text size on the page. `live` renders the snapshot derived
// from the running store.
//
// The page renders the real widget: each frame is the Glance widget composed to RemoteViews
// (`GlanceAppWidget.compose`) and inflated, exactly what a launcher shows. `activity` renders the court-session
// notification's content view. Release builds never read the flag (DemoHarness is DEBUG-only).
//
// Integrator: show `WidgetPreviewOverlay()` above RootScreen (it draws nothing unless the flag is set).
package app.plead.android.widgets

import android.content.Context
import android.content.res.Configuration
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.glance.GlanceId
import androidx.glance.ExperimentalGlanceApi
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.compose
import androidx.glance.appwidget.provideContent
import app.plead.android.app.DemoHarness
import app.plead.android.push.CourtSessionNotification
import app.plead.android.services.CourtSessionPhase
import app.plead.android.services.PleadActivityCopy
import app.plead.android.services.PleadCaseActivityAttributes
import app.plead.android.services.WidgetFamily
import app.plead.android.services.WidgetPrivacyMode
import app.plead.android.services.WidgetSnapshot
import app.plead.android.services.WidgetSnapshotStore
import app.plead.android.services.WidgetState
import java.time.Instant

object WidgetPreviewHarness {
    /** The page `AWWidgetPreview` asks for (`YES` → lock, `NO` → none), or null. */
    val requestedPage: String?
        get() {
            if (!app.plead.android.app.LaunchArguments.isEnabled) return null
            val raw = DemoHarness.widgetPreview ?: return null
            return page(raw)
        }

    fun page(raw: String): String? = when (raw.lowercase()) {
        "yes", "true", "1" -> "lock"
        "no", "false", "0" -> null
        else -> raw.lowercase()
    }

    /** `AWWidgetPreviewType`: the forced font scale (Dynamic Type size → Android font scale), or null. */
    fun forcedFontScale(raw: String? = DemoHarness.widgetPreviewType): Float? = when (raw?.lowercase()) {
        "large", "l" -> 1.0f
        "xl", "xlarge" -> 19f / 17f
        "xxl" -> 21f / 17f
        "ax1" -> 1.6f
        "ax3" -> 2.0f
        "ax5" -> 2.6f
        else -> null
    }

    val allStates = listOf(
        WidgetState.summoned, WidgetState.yourTurn, WidgetState.settlement, WidgetState.deliberating,
        WidgetState.verdictReady, WidgetState.judgementDue, WidgetState.agreementDue, WidgetState.none,
    )

    /** A context whose font scale is `scale` (the page's forced Dynamic Type size). */
    fun scaled(context: Context, scale: Float?): Context {
        scale ?: return context
        val config = Configuration(context.resources.configuration).apply { fontScale = scale }
        return context.createConfigurationContext(config)
    }
}

/** The harness page over the app when `AWWidgetPreview` is set; nothing otherwise. */
@Composable
fun WidgetPreviewOverlay() {
    val page = remember { WidgetPreviewHarness.requestedPage } ?: return
    WidgetPreviewScreen(page)
}

/** A Glance widget that renders one fixed snapshot in one family (the harness frames and the snapshot tests). */
class PreviewWidget(
    private val snapshot: WidgetSnapshot?,
    private val family: WidgetFamily,
    private val now: Instant,
) : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val link = snapshot?.link ?: WidgetSnapshot.homeLink
            PleadWidgetContainer(family = family, link = link) {
                if (snapshot != null) PleadWidgetContent(snapshot, family, now) else PleadWidgetPlaceholderView(family)
            }
        }
    }

    /** The RemoteViews a launcher would show at `size`. */
    @OptIn(ExperimentalGlanceApi::class)
    suspend fun remoteViews(context: Context, size: DpSize): RemoteViews = compose(context, size = size)
}

@Composable
private fun WidgetPreviewScreen(page: String) {
    val base = LocalContext.current
    val context = remember(base) { WidgetPreviewHarness.scaled(base, WidgetPreviewHarness.forcedFontScale()) }
    val now = remember { Instant.now() }
    val dark = page in setOf("lock", "states", "tinted", "activity")
    val backdrop = if (dark) {
        Modifier.background(Brush.verticalGradient(listOf(pleadRGB(0x6B2A3A), pleadRGB(0x3A1520))))
    } else {
        Modifier.background(pleadRGB(0xE9DCD3))
    }
    Box(
        modifier = Modifier.fillMaxSize().then(backdrop)
            // Swallow taps: the page sits above the app like the iOS overlay window.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Column(
            modifier = Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (page) {
                "small" -> SmallPage(context, now)
                "medium" -> MediumPage(context, now)
                "states" -> StatesPage(context, now)
                "tinted" -> TintedPage(context, now)
                "activity" -> ActivityPage(context, now)
                "live" -> LivePage(context, now)
                else -> LockPage(context, now)
            }
        }
    }
}

// MARK: Pages

@Composable
private fun LockPage(context: Context, now: Instant) {
    Caption("Lock Screen · rectangular · generic (default)", light = true)
    Rect(context, WidgetSnapshot.sample(WidgetState.summoned, WidgetPrivacyMode.generic, now), now)
    Rect(context, WidgetSnapshot.sample(WidgetState.yourTurn, WidgetPrivacyMode.generic, now), now)
    Caption("Lock Screen · rectangular · detailed (opt-in)", light = true)
    Rect(context, WidgetSnapshot.sample(WidgetState.summoned, WidgetPrivacyMode.detailed, now), now)
    Rect(context, WidgetSnapshot.sample(WidgetState.verdictReady, WidgetPrivacyMode.detailed, now), now)
    Caption("Lock Screen · circular", light = true)
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Circular(context, WidgetSnapshot.sample(WidgetState.summoned, now = now), now)
        Circular(context, WidgetSnapshot.sample(WidgetState.yourTurn, now = now), now)
        Circular(context, WidgetSnapshot.sample(WidgetState.none, now = now), now)
    }
    Caption("Home Screen · small / medium", light = true)
    Home(context, WidgetSnapshot.sample(WidgetState.summoned, now = now), WidgetFamily.systemSmall, now)
    Home(context, WidgetSnapshot.sample(WidgetState.summoned, now = now), WidgetFamily.systemMedium, now)
}

/** Every state on the rectangular accessory, the same at an accessibility size (headline only). */
@Composable
private fun StatesPage(context: Context, now: Instant) {
    Caption("Rectangular · every state", light = true)
    for (pair in WidgetPreviewHarness.allStates.chunked(2)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (state in pair) Rect(context, WidgetSnapshot.sample(state, WidgetPrivacyMode.generic, now), now)
        }
    }
    Caption("Rectangular · AX1 (headline only)", light = true)
    val ax1 = remember(context) { WidgetPreviewHarness.scaled(context, 1.6f) }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Rect(ax1, WidgetSnapshot.sample(WidgetState.summoned, WidgetPrivacyMode.generic, now), now)
        Rect(ax1, WidgetSnapshot.sample(WidgetState.verdictReady, WidgetPrivacyMode.detailed, now), now)
    }
    Caption("Placeholder (no snapshot written yet)", light = true)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Frame(context, null, WidgetFamily.accessoryRectangular, DpSize(172.dp, 76.dp), now)
        Frame(context, null, WidgetFamily.systemSmall, DpSize(170.dp, 170.dp), now)
    }
}

/** Android has no tinted / clear Home Screen: the widgets always render in full colour. */
@Composable
private fun TintedPage(context: Context, now: Instant) {
    Caption("Home Screen · tinted: not on Android (full colour)", light = true)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Home(context, WidgetSnapshot.sample(WidgetState.summoned, now = now), WidgetFamily.systemSmall, now)
        Home(context, WidgetSnapshot.sample(WidgetState.verdictReady, now = now), WidgetFamily.systemSmall, now)
    }
    Home(context, WidgetSnapshot.sample(WidgetState.summoned, now = now), WidgetFamily.systemMedium, now)
    Caption("Glance cell", light = true)
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Circular(context, WidgetSnapshot.sample(WidgetState.summoned, now = now), now)
        Circular(context, WidgetSnapshot.sample(WidgetState.yourTurn, now = now), now)
        Circular(context, WidgetSnapshot.sample(WidgetState.none, now = now), now)
    }
}

@Composable
private fun SmallPage(context: Context, now: Instant) {
    Caption("Home Screen · small")
    for (pair in WidgetPreviewHarness.allStates.chunked(2)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            for (state in pair) Home(context, WidgetSnapshot.sample(state, now = now), WidgetFamily.systemSmall, now)
        }
    }
}

@Composable
private fun MediumPage(context: Context, now: Instant) {
    Caption("Narrow · iPhone SE (321 × 148)")
    Home(context, WidgetSnapshot.sample(WidgetState.summoned, now = now), WidgetFamily.systemMedium, now, DpSize(321.dp, 148.dp))
    Caption("Narrow · iPhone 16e (338 × 158)")
    for (state in listOf(WidgetState.summoned, WidgetState.judgementDue, WidgetState.none)) {
        Home(context, WidgetSnapshot.sample(state, now = now), WidgetFamily.systemMedium, now, DpSize(338.dp, 158.dp))
    }
    Caption("Home Screen · medium · 6.3\" (364 × 170)")
    for (state in listOf(WidgetState.settlement, WidgetState.deliberating)) {
        Home(context, WidgetSnapshot.sample(state, now = now), WidgetFamily.systemMedium, now)
    }
}

/** The court-session notification (Live Activity banner) at L / XL / AX1 and the other phases. */
@Composable
private fun ActivityPage(context: Context, now: Instant) {
    val id = WidgetSnapshot.sampleCaseId
    val summons = PleadCaseActivityAttributes(id, 21, PleadCaseActivityAttributes.Kind.summons)
    val verdict = PleadCaseActivityAttributes(id, 21, PleadCaseActivityAttributes.Kind.verdict)
    val longest = PleadActivityCopy.state(CourtSessionPhase.summoned, now.plusSeconds(23 * 3600 + 59 * 60 + 7))
    for ((scale, name) in listOf(1.0f to "L", 19f / 17f to "XL", 1.6f to "AX1")) {
        Caption("Court session · notification · $name", light = true)
        Banner(WidgetPreviewHarness.scaled(context, scale), summons, longest, now)
    }
    Caption("Deliberating · Verdict ready · Plea entered (default size)", light = true)
    Banner(context, verdict, PleadActivityCopy.state(CourtSessionPhase.deliberating, now.plusSeconds(42 * 60), "The Dinner Incident", detailed = true), now)
    Banner(context, verdict, PleadActivityCopy.state(CourtSessionPhase.verdictReady, null), now)
    Banner(context, summons, PleadActivityCopy.state(CourtSessionPhase.pleaEntered, null), now)
}

@Composable
private fun LivePage(context: Context, now: Instant) {
    val snap = WidgetSnapshotStore.shared.currentSnapshot(now) ?: WidgetSnapshot()
    Caption("Live snapshot · ${snap.state.name} · ${snap.privacyMode.name} · ${snap.activeCaseCount} open")
    Caption(snap.link)
    Rect(context, snap, now)
    Circular(context, snap, now)
    Home(context, snap, WidgetFamily.systemSmall, now)
    Home(context, snap, WidgetFamily.systemMedium, now)
}

// MARK: Frames (the iOS 6.1"/6.3" family sizes, in dp)

@Composable
private fun Caption(text: String, light: Boolean = false) {
    Text(
        text = text,
        color = if (light) Color.White.copy(alpha = 0.85f) else PleadWidgetPalette.darkCocoa,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun Rect(context: Context, s: WidgetSnapshot, now: Instant) =
    Frame(context, s, WidgetFamily.accessoryRectangular, DpSize(172.dp, 76.dp), now)

@Composable
private fun Circular(context: Context, s: WidgetSnapshot, now: Instant) =
    Frame(context, s, WidgetFamily.accessoryCircular, DpSize(76.dp, 76.dp), now)

@Composable
private fun Home(context: Context, s: WidgetSnapshot, family: WidgetFamily, now: Instant, size: DpSize? = null) {
    val frame = size ?: DpSize(if (family == WidgetFamily.systemMedium) 364.dp else 170.dp, 170.dp)
    Frame(context, s, family, frame, now)
}

/** The Glance widget composed at `size` and inflated (what the launcher would draw). */
@Composable
private fun Frame(context: Context, s: WidgetSnapshot?, family: WidgetFamily, size: DpSize, now: Instant) {
    val views by produceState<RemoteViews?>(null, context, s, family, size) {
        value = runCatching { PreviewWidget(s, family, now).remoteViews(context, size) }.getOrNull()
    }
    RemoteViewsHost(context, views, Modifier.size(size.width, size.height))
}

@Composable
private fun Banner(context: Context, attributes: PleadCaseActivityAttributes, state: app.plead.android.services.CourtSessionState, now: Instant) {
    val views = remember(context, attributes, state) { CourtSessionNotification.contentView(context, attributes, state, now) }
    RemoteViewsHost(context, views, Modifier.fillMaxWidth().widthIn(max = 370.dp).clip(RoundedCornerShape(22.dp)))
}

@Composable
private fun RemoteViewsHost(context: Context, views: RemoteViews?, modifier: Modifier) {
    AndroidView(
        factory = { FrameLayout(it) },
        update = { frame ->
            frame.removeAllViews()
            views?.let { rv -> runCatching { rv.apply(context, frame) }.getOrNull()?.let(frame::addView) }
        },
        modifier = modifier,
    )
}
