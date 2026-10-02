// Small Compose equivalents of the SwiftUI primitives the design system (and every later wave) leans on, so ports
// stay line-for-line: SF Symbol names → Material icons, `accessibilityReduceMotion`, `.spring(duration:bounce:)`,
// `.shadow(color:radius:x:y:)`, `.saturation(_:)`, `.contentTransition(.numericText())` and fixed-size fonts
// (`.system(size:)`, which does not scale with Dynamic Type).
package app.plead.android.designsystem

import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Balance
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DoNotTouch
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Handshake
import androidx.compose.material.icons.outlined.HeartBroken
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.PhoneIphone
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.plead.android.app.DemoHarness
import app.plead.android.features.settlement.SignatureGlyph
import kotlin.math.PI

// MARK: - SF Symbols

/**
 * Every SF Symbol name the iOS app draws, mapped to its nearest Material icon (icons-extended). Call sites keep
 * the Swift string: `PrimaryButton("Next", systemImage = "arrow.right")`. Unknown names return null.
 */
object SFSymbol {
    val map: Map<String, ImageVector> = mapOf(
        "checkmark" to Icons.Filled.Check,
        "arrow.right" to Icons.AutoMirrored.Filled.ArrowForward,
        "arrow.up" to Icons.Filled.ArrowUpward,
        "arrow.down" to Icons.Filled.ArrowDownward,
        "arrow.clockwise" to Icons.Filled.Refresh,
        "arrow.triangle.2.circlepath" to Icons.Filled.Sync,
        "paperplane.fill" to Icons.AutoMirrored.Filled.Send,
        "calendar" to Icons.Outlined.CalendarMonth,
        "checkmark.seal" to Icons.Outlined.Verified,
        "checkmark.seal.fill" to Icons.Filled.Verified,
        "checkmark.circle.fill" to Icons.Filled.CheckCircle,
        "questionmark" to Icons.Filled.QuestionMark,
        "questionmark.folder" to Icons.Outlined.FolderOpen,
        "chevron.left" to Icons.AutoMirrored.Filled.KeyboardArrowLeft,
        "chevron.right" to Icons.AutoMirrored.Filled.KeyboardArrowRight,
        "chevron.down" to Icons.Filled.KeyboardArrowDown,
        "building.columns" to Icons.Outlined.AccountBalance,
        "xmark" to Icons.Filled.Close,
        "text.bubble" to Icons.Outlined.ChatBubbleOutline,
        "paintbrush.pointed" to Icons.Outlined.Brush,
        "hourglass" to Icons.Outlined.HourglassEmpty,
        "heart" to Icons.Outlined.FavoriteBorder,
        "heart.fill" to Icons.Filled.Favorite,
        "heart.slash" to Icons.Outlined.HeartBroken,
        "hammer.fill" to Icons.Filled.Gavel,
        "trash" to Icons.Outlined.Delete,
        "trash.circle.fill" to Icons.Filled.DeleteForever,
        "square.and.arrow.up" to Icons.Outlined.IosShare,
        "signature" to SignatureGlyph.vector, // Plead's own glyph (30x22: draw it with SignatureGlyph(pointSize) for the symbol's box)
        "info.circle" to Icons.Outlined.Info,
        "hands.and.sparkles" to Icons.Outlined.Handshake,
        "hands.and.sparkles.fill" to Icons.Filled.Handshake,
        "gearshape" to Icons.Outlined.Settings,
        "folder" to Icons.Outlined.Folder,
        "exclamationmark.circle.fill" to Icons.Filled.Error,
        "exclamationmark.triangle.fill" to Icons.Filled.Warning,
        "exclamationmark.bubble.fill" to Icons.Filled.Feedback,
        "crown.fill" to Icons.Filled.WorkspacePremium,
        "clock" to Icons.Outlined.Schedule,
        "wifi.exclamationmark" to Icons.Filled.WifiOff,
        "waveform" to Icons.Filled.GraphicEq,
        "rectangle.portrait.and.arrow.right" to Icons.AutoMirrored.Filled.Logout,
        "plus" to Icons.Filled.Add,
        "play.rectangle" to Icons.Outlined.SmartDisplay,
        "play.rectangle.fill" to Icons.Filled.SmartDisplay,
        "photo" to Icons.Outlined.Image,
        "photo.on.rectangle" to Icons.Outlined.PhotoLibrary,
        "person.crop.circle.fill" to Icons.Filled.AccountCircle,
        "person.crop.circle.badge.exclamationmark" to Icons.Outlined.AccountCircle,
        "lock" to Icons.Outlined.Lock,
        "lock.fill" to Icons.Filled.Lock,
        "lightbulb.fill" to Icons.Filled.Lightbulb,
        "house" to Icons.Outlined.Home,
        "hand.raised" to Icons.Outlined.PanTool,
        "hand.raised.fill" to Icons.Filled.PanTool,
        "hand.raised.slash" to Icons.Outlined.DoNotTouch,
        "hand.point.up.left.fill" to Icons.Filled.TouchApp,
        "gauge.with.dots.needle.50percent" to Icons.Outlined.Speed,
        "envelope.fill" to Icons.Filled.Email,
        "ellipsis" to Icons.Filled.MoreHoriz,
        "ellipsis.circle" to Icons.Outlined.MoreHoriz,
        "doc.text.fill" to Icons.Filled.Description,
        "dice" to Icons.Outlined.Casino,
        "bubble.left.and.bubble.right.fill" to Icons.Filled.Forum,
        "bell.badge" to Icons.Outlined.NotificationsActive,
        "receipt" to Icons.Outlined.Receipt,
        "quote.opening" to Icons.Filled.FormatQuote,
        "iphone" to Icons.Outlined.PhoneIphone,
        "scalemass" to Icons.Outlined.Balance,
        "stop.circle" to Icons.Outlined.StopCircle,
    )

    /** The icon for an SF Symbol name (a neutral help glyph when the name is not mapped yet). */
    fun icon(name: String): ImageVector = map[name] ?: Icons.Outlined.HelpOutline
}

// MARK: - Reduce Motion

/** Overrides the system Reduce Motion reading for a subtree (previews, tests). null = read the system. */
val LocalReduceMotion = staticCompositionLocalOf<Boolean?> { null }

/**
 * SwiftUI `@Environment(\.accessibilityReduceMotion)` (PORT.md §2): animations off in developer / accessibility
 * settings (`ANIMATOR_DURATION_SCALE == 0`) or the demo flag `AWDemoReduceMotion YES`.
 */
@Composable
fun accessibilityReduceMotion(): Boolean {
    LocalReduceMotion.current?.let { return it }
    val context = LocalContext.current
    return remember(context) {
        val scale = runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
        scale == 0f || DemoHarness.demoReduceMotion
    }
}

// MARK: - Motion

/** SwiftUI `.spring(duration:bounce:)`: stiffness (2π / duration)², damping ratio 1 − bounce. */
fun <T> swiftSpring(duration: Float = 0.5f, bounce: Float = 0f): SpringSpec<T> {
    val omega = (2 * PI / duration).toFloat()
    return spring(dampingRatio = (1f - bounce).coerceAtLeast(0.01f), stiffness = omega * omega)
}

// MARK: - Shadow & saturation

/**
 * SwiftUI `.shadow(color:radius:x:y:)` behind [shape]: a blurred copy of the shape offset by (x, y). SwiftUI's
 * radius is ~2σ; Android's shadow-layer radius is ~σ / 0.577, hence the 0.866 factor. Drawn through the
 * framework shadow layer (hardware-accelerated from API 28; older devices draw no shadow).
 */
fun Modifier.pleadShadow(color: Color, radius: Dp, x: Dp = 0.dp, y: Dp = 0.dp, shape: Shape = RectangleShape): Modifier =
    if (color.alpha == 0f) this else drawBehind {
        val outline = shape.createOutline(size, layoutDirection, this)
        drawIntoCanvas { canvas ->
            val paint = Paint()
            val frameworkPaint = paint.asFrameworkPaint()
            frameworkPaint.color = android.graphics.Color.TRANSPARENT
            frameworkPaint.setShadowLayer((radius.toPx() * 0.866f).coerceAtLeast(0.01f), x.toPx(), y.toPx(), color.toArgb())
            canvas.drawOutline(outline, paint)
        }
    }

/** SwiftUI `.saturation(_:)`: 0 = greyscale, 1 = unchanged. */
fun Modifier.saturation(amount: Float): Modifier =
    if (amount == 1f) this else drawWithCache {
        val paint = Paint().apply { colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(amount) }) }
        onDrawWithContent {
            drawIntoCanvas { canvas ->
                canvas.saveLayer(Rect(0f, 0f, size.width, size.height), paint)
                drawContent()
                canvas.restore()
            }
        }
    }

// MARK: - Fixed-size fonts

/** A size in points that does not follow the system font scale (SwiftUI `.system(size:)` / canvas-relative text). */
@Composable
fun fixedSp(points: Float): TextUnit = (points / LocalDensity.current.fontScale).sp

// MARK: - Numeric text

/**
 * `.contentTransition(.numericText(countsDown:))`: characters that change roll vertically (new ones from below
 * when counting up, from above when [countsDown]). Under Reduce Motion they simply swap.
 */
@Composable
fun NumericText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    countsDown: Boolean = false,
) {
    val reduceMotion = accessibilityReduceMotion()
    Row(modifier) {
        text.forEachIndexed { index, char ->
            // Key from the right so units stay put when the string grows or shrinks ("10:00" → "9:59").
            key(text.length - index) {
                AnimatedContent(
                    targetState = char,
                    transitionSpec = {
                        if (reduceMotion) {
                            fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                        } else {
                            val down = countsDown || (targetState.isDigit() && initialState.isDigit() && targetState < initialState)
                            val dir = if (down) -1 else 1
                            (slideInVertically(tween(300, easing = PleadMotion.easeOut)) { h -> dir * h } + fadeIn(tween(300))) togetherWith
                                (slideOutVertically(tween(300, easing = PleadMotion.easeOut)) { h -> -dir * h } + fadeOut(tween(300)))
                        }
                    },
                    label = "numericText",
                ) { c ->
                    androidx.compose.material3.Text(c.toString(), style = style, color = color, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}
