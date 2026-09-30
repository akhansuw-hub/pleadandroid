// Port of ArgueWin/Features/ColdOpen/ColdOpenAssets.swift.
package app.plead.android.features.coldopen

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.plead.android.R
import app.plead.android.designsystem.PleadLogo
import app.plead.android.courtroom.CourtroomBackground

/**
 * Bundled cold-open artwork (Assets.xcassets/ColdOpen → res/drawable-nodpi, 1170×2532) and brand marks. Everything is
 * looked up by its iOS asset name so the feature degrades to the courtroom art / drawn placeholders if a frame is
 * missing. No network, ever.
 */
object ColdOpenAssets {
    const val courthouse = "frame1_courthouse"
    const val doorsClosed = "frame2_doors_closed"

    /** Doors open, warm light, the couple in the doorway: the interior the door layers part over. */
    const val doors = "frame2_doors"

    /** Full-canvas layers, each with one closed door half opaque. */
    const val doorLeft = "frame2_door_left"
    const val doorRight = "frame2_door_right"
    const val judge = "frame3_judge"

    /** raised, mid-swing, impact (sparks), settling, rest. */
    val gavel: List<String> = (1..5).map { "frame4_gavel_$it" }

    /** Top ~40% flat Warm Cream for the logo; courtroom glow and the couple below. */
    const val endcard = "frame5_endcard"

    /** The primary logo (heart-accent wordmark, CONTRACTS-v2 Amendment k) shown on the end card. */
    val logo: String = PleadLogo.assetName(PleadLogo.Variant.primary)

    /**
     * What the static launch screen shows (amendment l): the primary wordmark. The heart-over-scales mark is the
     * app icon only.
     */
    val launchMark: String = PleadLogo.assetName(PleadLogo.Variant.primary)

    /** Fallback art when a frame is missing. */
    const val fallbackCourtroom = "CourtroomBackground"
    const val fallbackPaywall = "PaywallCourtroom"

    /** What a mode needs decoded before the first frame. */
    fun names(mode: ColdOpenCoordinator.Mode, reduceMotion: Boolean): List<String> = when (mode) {
        ColdOpenCoordinator.Mode.none -> emptyList()
        ColdOpenCoordinator.Mode.sting -> listOf(endcard, logo)
        ColdOpenCoordinator.Mode.full -> if (reduceMotion) {
            listOf(courthouse, judge, endcard, logo)
        } else {
            listOf(courthouse, doorsClosed, doors, doorLeft, doorRight, judge) + gavel + listOf(endcard, logo)
        }
    }

    /** iOS asset name → the drawable imported by tools/android/import_assets.sh (snake case). */
    private val drawables: Map<String, Int> = mapOf(
        courthouse to R.drawable.frame1_courthouse,
        doorsClosed to R.drawable.frame2_doors_closed,
        doors to R.drawable.frame2_doors,
        doorLeft to R.drawable.frame2_door_left,
        doorRight to R.drawable.frame2_door_right,
        judge to R.drawable.frame3_judge,
        "frame4_gavel_1" to R.drawable.frame4_gavel_1,
        "frame4_gavel_2" to R.drawable.frame4_gavel_2,
        "frame4_gavel_3" to R.drawable.frame4_gavel_3,
        "frame4_gavel_4" to R.drawable.frame4_gavel_4,
        "frame4_gavel_5" to R.drawable.frame4_gavel_5,
        endcard to R.drawable.frame5_endcard,
        "PleadWordmark" to R.drawable.plead_wordmark,
        fallbackCourtroom to R.drawable.courtroom_background,
        fallbackPaywall to R.drawable.paywall_courtroom,
    )

    /** The drawable for an asset name, or null when it is not bundled (Swift `UIImage(named:) == nil`). */
    fun drawableId(name: String): Int? = drawables[name]

    fun exists(name: String): Boolean = drawableId(name) != null

    /**
     * Loads and decodes each image (call off the main thread) so playback never hitches on a PNG decode. Hardware
     * bitmaps where available: thirteen full-screen frames stay out of the Java heap (iOS `preparingForDisplay`).
     */
    fun decode(names: List<String>, resources: Resources): Map<String, ImageBitmap> {
        val out = mutableMapOf<String, ImageBitmap>()
        for (name in names) {
            val image = load(name, resources) ?: continue
            out[name] = image
        }
        return out
    }

    /** One image, decoded now (null when missing or undecodable). */
    fun load(name: String, resources: Resources): ImageBitmap? {
        val id = drawableId(name) ?: return null
        val bitmap = runCatching {
            val options = BitmapFactory.Options().apply {
                inScaled = false
                inPreferredConfig = Bitmap.Config.HARDWARE // minSdk 26
            }
            BitmapFactory.decodeResource(resources, id, options)
        }.getOrNull() ?: runCatching {
            BitmapFactory.decodeResource(resources, id, BitmapFactory.Options().apply { inScaled = false })
        }.getOrNull() ?: return null
        bitmap.prepareToDraw()
        return bitmap.asImageBitmap()
    }
}
