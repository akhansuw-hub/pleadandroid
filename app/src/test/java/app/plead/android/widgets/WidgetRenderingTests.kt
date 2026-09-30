// The rendering half of ArgueWinTests/WidgetSnapshotTests + the widget previews: every family composed by Glance to
// RemoteViews (exactly what a launcher inflates), inflated off-device with Robolectric, its text checked against the
// brief's copy and privacy rules, and written to `app/build/outputs/snapshots/widgets/*.png` for review by eye.
package app.plead.android.widgets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.plead.android.services.WidgetFamily
import app.plead.android.services.WidgetPrivacyMode
import app.plead.android.services.WidgetSnapshot
import app.plead.android.services.WidgetState
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w402dp-h874dp-xxhdpi")
class WidgetRenderingTests {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now: Instant = Instant.now()

    private fun render(name: String, snapshot: WidgetSnapshot?, family: WidgetFamily, size: DpSize): List<String> {
        val views = runBlocking { PreviewWidget(snapshot, family, now).remoteViews(context, size) }
        val density = context.resources.displayMetrics.density
        val w = (size.width.value * density).toInt()
        val h = (size.height.value * density).toInt()
        val host = FrameLayout(context)
        val view = views.apply(context, host)
        host.addView(view, FrameLayout.LayoutParams(w, h))
        host.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, w, h)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFFE9DCD3.toInt())
        host.draw(Canvas(bitmap))
        val dir = File("build/outputs/snapshots/widgets").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return texts(host)
    }

    private fun texts(v: View): List<String> = when (v) {
        is TextView -> listOf(v.text.toString())
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }

    private val small = DpSize(170.dp, 170.dp)
    private val medium = DpSize(364.dp, 170.dp)
    private val rect = DpSize(172.dp, 76.dp)
    private val circle = DpSize(76.dp, 76.dp)

    @Test fun smallShowsTitleAndStatusChip() {
        val texts = render("small-summoned", WidgetSnapshot.sample(WidgetState.summoned, now = now), WidgetFamily.systemSmall, small)
        assertTrue(texts.toString(), texts.contains("The Dinner Incident"))
        assertTrue(texts.contains("Awaiting your plea"))
        for (state in WidgetState.entries) render("small-${state.name}", WidgetSnapshot.sample(state, now = now), WidgetFamily.systemSmall, small)
    }

    @Test fun smallEmptyStateIsCourtAdjourned() {
        val texts = render("small-placeholder", null, WidgetFamily.systemSmall, small)
        assertEquals(listOf("Court adjourned", "No open cases"), texts)
    }

    @Test fun mediumShowsPartnerCaseStatusAndAction() {
        val texts = render("medium-settlement", WidgetSnapshot.sample(WidgetState.settlement, now = now), WidgetFamily.systemMedium, medium)
        assertTrue(texts.toString(), texts.containsAll(listOf("Sophie", "The Dinner Incident", "Case #021", "Settlement offer waiting", "Review offer")))
        val summoned = render("medium-summoned", WidgetSnapshot.sample(WidgetState.summoned, now = now), WidgetFamily.systemMedium, medium)
        // The countdown replaces the docket number while the plea is due.
        assertTrue(summoned.toString(), summoned.any { it.startsWith("Plea due in") })
        assertTrue(summoned.contains("Enter plea"))
        render("medium-narrow-judgementDue", WidgetSnapshot.sample(WidgetState.judgementDue, now = now), WidgetFamily.systemMedium, DpSize(321.dp, 148.dp))
        val none = render("medium-none", WidgetSnapshot.sample(WidgetState.none, now = now), WidgetFamily.systemMedium, medium)
        assertEquals(listOf("Court adjourned", "No open cases", "Open Plead"), none)
    }

    @Test fun rectangularGenericNeverLeaksTheCase() {
        val generic = render("rect-generic", WidgetSnapshot.sample(WidgetState.yourTurn, WidgetPrivacyMode.generic, now), WidgetFamily.accessoryRectangular, rect)
        assertTrue(generic.toString(), generic.contains("YOUR RESPONSE IS DUE"))
        assertFalse(generic.any { it.contains("Dinner") || it.contains("Sophie") })
        val detailed = render("rect-detailed", WidgetSnapshot.sample(WidgetState.verdictReady, WidgetPrivacyMode.detailed, now), WidgetFamily.accessoryRectangular, rect)
        assertTrue(detailed.toString(), detailed.containsAll(listOf("THE JUDGE HAS RULED", "The Dinner Incident")))
        val none = render("rect-none", WidgetSnapshot.sample(WidgetState.none, WidgetPrivacyMode.generic, now), WidgetFamily.accessoryRectangular, rect)
        assertEquals(listOf("COURT ADJOURNED", "No open cases"), none)
    }

    @Test fun circularShowsTheCountBadge() {
        assertEquals(listOf("1"), render("circular-summoned", WidgetSnapshot.sample(WidgetState.summoned, now = now), WidgetFamily.accessoryCircular, circle))
        assertEquals(emptyList<String>(), render("circular-none", WidgetSnapshot.sample(WidgetState.none, now = now), WidgetFamily.accessoryCircular, circle))
    }

    @Test fun tapOpensTheSnapshotLinkInMainActivity() {
        val snap = WidgetSnapshot.sample(WidgetState.summoned, now = now)
        val intent = PleadWidgets.tapIntent(context, snap.link)
        assertEquals("plead://case/0d1e2f30-4152-4637-8899-aabbccddeeff/plea", intent.data.toString())
        assertEquals("app.plead.android.app.MainActivity", intent.component?.className)
        assertEquals(android.content.Intent.ACTION_VIEW, intent.action)
    }

    @Test fun installRegistersEveryReceiverWithWidgetCenter() {
        PleadWidgets.install()
        PleadWidgets.install()
        val centre = app.plead.android.services.WidgetCenter.receivers
        for (r in PleadWidgets.receivers) assertEquals(1, centre.count { it == r })
    }
}
