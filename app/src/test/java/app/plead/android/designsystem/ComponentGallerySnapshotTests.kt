// Renders the design-system previews off-device (Robolectric native graphics) to
// `app/build/outputs/snapshots/designsystem/*.png`, so the port can be compared with the iOS previews and
// docs/screenshots/v2 without the emulator. Asserts only that each renders; review the PNGs by eye.
package app.plead.android.designsystem

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w402dp-h2200dp-xxhdpi")
class ComponentGallerySnapshotTests {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun snap(name: String, content: @Composable () -> Unit) {
        rule.setContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                PleadTheme { Box(Modifier.width(402.dp)) { content() } }
            }
        }
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        val root = rule.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        // The composed content's own size (the Box wraps its content height inside the tall window).
        val content = (root as ViewGroup).getChildAt(0) ?: root
        val w = content.width.coerceAtLeast(1)
        val h = content.height.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        content.draw(Canvas(bitmap))
        val dir = File("build/outputs/snapshots/designsystem").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(bitmap.width > 0 && bitmap.height > 0)
    }

    @Test fun components() = snap("components") { ComponentsPreviewContent() }

    @Test fun avatarsAndBadges() = snap("avatars") {
        androidx.compose.foundation.layout.Column {
            ComponentGallerySections.avatars()
            ComponentGallerySections.badges()
        }
    }

    @Test fun logos() = snap("logos") { ComponentGallerySections.logos() }

    @Test fun labelsAndForms() = snap("labels-forms") { ComponentGallerySections.labelsAndForms() }

    @Test fun caseFiles() = snap("case-files") { CaseFilesPreviewContent() }
}
