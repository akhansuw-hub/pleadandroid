// Port of ArgueWin/Courtroom/CourtEasel.swift: the exhibit card on the painted easel (native UI over the art, never
// baked in).
//   photo / screenshot  image card with EXHIBIT label, caption, optional occurred-at line
//   text quote          parchment quote card with source/context line
//   receipt             typed dated line ("12 Sept, 21:14 — said he would be home by 9.")
// Objection and ruling stamps sit on the card. Tapping opens the full exhibit.
package app.plead.android.courtroom

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.plead.android.designsystem.CourtFont
import app.plead.android.designsystem.ExhibitThumbnail
import app.plead.android.designsystem.PleadColor
import app.plead.android.designsystem.PleadRadius
import app.plead.android.designsystem.PleadSpacing
import app.plead.android.designsystem.PleadType
import app.plead.android.designsystem.SFSymbol
import app.plead.android.designsystem.TextStyleKind
import app.plead.android.designsystem.fixedSp
import app.plead.android.designsystem.pleadShadow
import app.plead.android.models.Exhibit
import app.plead.android.models.ExhibitType
import app.plead.android.models.ObjectionReason
import app.plead.android.models.ObjectionRuling
import app.plead.android.models.Role
import coil3.compose.SubcomposeAsyncImage
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun CourtEasel(
    exhibit: Exhibit,
    url: URI?,
    modifier: Modifier = Modifier,
    ownerName: String? = null,
    @Suppress("UNUSED_PARAMETER") ownerRole: Role? = null,
    objected: Boolean = false,
    objectionReason: ObjectionReason? = null,
    ruling: ObjectionRuling? = null,
    /** Compact = in-scene card; false = detail sheet. */
    compact: Boolean = true,
    /** The scene hides the card's stamp while the objection card for this exhibit is on stage. */
    showsStamp: Boolean = true,
    /**
     * Scene motion (amendment x): stamps slam in once; `labelLands` drops the EXHIBIT label in just after the card
     * rises onto the easel.
     */
    motion: CourtMotionDirector? = null,
    labelLands: Boolean = false,
) {
    // One stamp at a time (the ruling once it lands, OBJECTION while it's pending), always drawn inside the card's
    // header so it never spills onto the bubbles around the easel.
    val hasStamp = showsStamp && (ruling != null || objected)
    val shape = RoundedCornerShape(PleadRadius.tile)
    val isImage = exhibit.type == ExhibitType.photo || exhibit.type == ExhibitType.screenshot
    val surface = if (exhibit.type == ExhibitType.text) PleadColor.parchment else PleadColor.paperWhite
    val labelLanding = Modifier.courtLanding(
        pending = labelLands, fromScale = 1.25f, delay = CourtMotionTiming.exhibitLabelDelay, duration = 0.2, bounce = 0.3f,
    )
    val stamp: @Composable (Modifier) -> Unit = { m ->
        val size = if (compact) CourtStamp.Size.small else CourtStamp.Size.regular
        if (showsStamp) {
            if (ruling != null) {
                CourtStamp.ruling(ruling, modifier = m, size = size, landKey = motion?.let { "${exhibit.id}-${ruling.rawValue}" }, motion = motion)
            } else if (objected) {
                CourtStamp(text = "OBJECTION", color = PleadColor.burgundy, modifier = m, angle = -6f, size = size,
                    landKey = motion?.let { "${exhibit.id}-objection" }, motion = motion)
            }
        }
    }
    Column(
        modifier
            .then(if (compact) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
            .clearAndSetSemantics {
                contentDescription = CourtEasel.accessibilityText(exhibit, ownerName, objected, objectionReason, ruling)
            }
            .pleadShadow(Color.Black.copy(alpha = 0.35f), radius = 8.dp, y = 4.dp, shape = shape)
            .background(surface, shape)
            .border(1.5.dp, PleadColor.cocoa.copy(alpha = 0.85f), shape)
            .padding(if (compact) 6.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 10.dp),
    ) {
        if (compact && isImage) {
            // In the scene the label and stamp ride on the picture, so a short card keeps its image.
            Box(Modifier.fillMaxWidth().weight(1f)) {
                EaselContent(exhibit, url, compact, Modifier.fillMaxSize())
                Text(
                    exhibit.displayName.uppercase(),
                    style = CourtFont.legal.copy(letterSpacing = PleadType.capsTracking.sp),
                    color = PleadColor.burgundy,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .then(labelLanding)
                        .background(PleadColor.paperWhite.copy(alpha = 0.92f), RoundedCornerShape(5.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                if (hasStamp) {
                    Box(Modifier.align(Alignment.BottomEnd).padding(5.dp)) { stamp(Modifier) }
                }
            }
        } else {
            // Header
            Row(
                Modifier.fillMaxWidth().then(if (compact) Modifier.defaultMinSize(minHeight = 16.dp) else Modifier),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    exhibit.displayName.uppercase(),
                    style = (if (compact) CourtFont.legal else CourtFont.legalLarge).copy(letterSpacing = PleadType.capsTracking.sp),
                    color = PleadColor.burgundy,
                    maxLines = 1,
                    softWrap = false,
                    modifier = labelLanding,
                )
                Spacer(Modifier.width(2.dp).weight(1f))
                if (hasStamp) {
                    stamp(Modifier)
                } else if (ownerName != null) {
                    Text(ownerName, style = CourtFont.caption2, color = PleadColor.walnut, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            EaselContent(
                exhibit, url, compact,
                if (compact) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth(),
            )
        }
        if (isImage) {
            Text(
                exhibit.caption,
                style = if (compact) CourtFont.caption else CourtFont.exhibitTitle,
                color = PleadColor.cocoa,
                maxLines = if (compact) 1 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
            )
            val at = exhibit.occurredAt
            if (!compact && at != null) OccurredLine(at)
        }
    }
}

object CourtEasel {
    fun accessibilityText(ex: Exhibit, ownerName: String?, objected: Boolean, reason: ObjectionReason?, ruling: ObjectionRuling?): String {
        var s = "On the easel: ${ex.displayName}, ${typeTitle(ex.type)}"
        if (ownerName != null) s += ", from $ownerName"
        s += ". ${ex.caption}."
        val body = ex.body
        if (!body.isNullOrEmpty()) s += " $body"
        val at = ex.occurredAt
        if (at != null) {
            val f = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(Locale.getDefault())
            s += " Dated ${f.format(at.atZone(ZoneId.systemDefault()))}."
        }
        if (objected) s += " Objection${reason?.let { ", ${it.title}" } ?: ""}."
        if (ruling != null) s += " ${ruling.rawValue.swiftCapitalized()}."
        return s
    }

    fun typeTitle(t: ExhibitType): String = when (t) {
        ExhibitType.photo -> "Photo"
        ExhibitType.screenshot -> "Screenshot"
        ExhibitType.voice -> "Voice note"
        ExhibitType.text -> "Quote"
        ExhibitType.receipt -> "Receipt"
    }

    /** "12 Sept, 21:14" (`.dateTime.day().month(.abbreviated).hour().minute()`). */
    internal fun dateTimeShort(at: Instant): String {
        val zone = ZoneId.systemDefault()
        return "${CourtroomLogic.dayMonthAbbreviated(at, zone)}, ${DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()).format(at.atZone(zone))}"
    }
}

@Composable
private fun OccurredLine(at: Instant) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(SFSymbol.icon("clock"), contentDescription = null, tint = PleadColor.walnut, modifier = Modifier.size(11.dp))
        Text(CourtEasel.dateTimeShort(at), style = CourtFont.caption2, color = PleadColor.walnut, maxLines = 1)
    }
}

@Composable
private fun EaselContent(exhibit: Exhibit, url: URI?, compact: Boolean, modifier: Modifier) {
    when (exhibit.type) {
        ExhibitType.photo, ExhibitType.screenshot ->
            CourtExhibitImage(url, modifier.then(if (compact) Modifier.heightIn(min = 40.dp) else Modifier.height(240.dp)))
        ExhibitType.text -> Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ScaledText(
                exhibit.body ?: exhibit.caption,
                style = if (compact) CourtFont.footnote else CourtFont.body,
                color = PleadColor.cocoa,
                minimumScaleFactor = if (compact) 0.75f else 1f,
                maxLines = if (compact) 3 else Int.MAX_VALUE,
            )
            SpacerFill(compact)
            Text(
                "— ${exhibit.caption}",
                style = CourtFont.caption2,
                color = PleadColor.walnut,
                maxLines = if (compact) 1 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ExhibitType.receipt -> Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            DashedRule()
            val mono = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                fontSize = (if (compact) TextStyleKind.caption.defaultSize else TextStyleKind.body.defaultSize).sp,
            )
            ScaledText(
                receiptLine(exhibit),
                style = mono,
                color = PleadColor.cocoa,
                minimumScaleFactor = if (compact) 0.75f else 1f,
                maxLines = if (compact) 4 else Int.MAX_VALUE,
            )
            SpacerFill(compact)
            DashedRule()
            Text(exhibit.caption, style = CourtFont.caption2, color = PleadColor.walnut, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        ExhibitType.voice -> Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(SFSymbol.icon("waveform"), contentDescription = null, tint = PleadColor.walnut, modifier = Modifier.size(13.dp))
            Text("Voice notes arrive in a later version", style = CourtFont.caption, color = PleadColor.walnut)
        }
    }
}

@Composable
private fun ColumnScope.SpacerFill(compact: Boolean) {
    if (compact) Spacer(Modifier.weight(1f)) else Spacer(Modifier.height(0.dp))
}

private fun receiptLine(exhibit: Exhibit): String {
    val text = exhibit.body ?: exhibit.caption
    val at = exhibit.occurredAt ?: return text
    return "${CourtEasel.dateTimeShort(at)} — $text"
}

@Composable
private fun DashedRule() {
    Canvas(Modifier.fillMaxWidth().height(1.dp)) {
        drawLine(
            PleadColor.walnut.copy(alpha = 0.5f),
            start = Offset(0f, size.height / 2f),
            end = Offset(size.width, size.height / 2f),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
        )
    }
}

/**
 * The easel when the exhibit is no longer the live subject: a small framed thumbnail with the label and, once ruled,
 * a tiny stamp, all inside the frame. Tapping opens the full exhibit.
 */
@Composable
fun CourtEaselMini(
    exhibit: Exhibit,
    url: URI?,
    modifier: Modifier = Modifier,
    ownerName: String? = null,
    objected: Boolean = false,
    objectionReason: ObjectionReason? = null,
    ruling: ObjectionRuling? = null,
    motion: CourtMotionDirector? = null,
) {
    val inner = RoundedCornerShape(9.dp)
    val outer = RoundedCornerShape(11.dp)
    val s = CourtEaselMini.size
    Box(
        modifier
            .clearAndSetSemantics {
                contentDescription = CourtEasel.accessibilityText(exhibit, ownerName, objected, objectionReason, ruling)
            }
            .pleadShadow(Color.Black.copy(alpha = 0.35f), radius = 5.dp, y = 3.dp, shape = outer)
            .background(PleadColor.walnut, outer)
            .border(1.dp, PleadColor.gold.copy(alpha = 0.55f), outer)
            .padding(3.dp),
    ) {
        Box(Modifier.size(s.width.dp, s.height.dp).clip(inner)) {
            ExhibitThumbnail(
                type = exhibit.type, caption = exhibit.caption, body = exhibit.body, imageURL = url?.toString(), compact = true,
                modifier = Modifier.size(s.width.dp, s.height.dp),
            )
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .size(s.width.dp, 17.dp)
                    .background(PleadColor.parchment),
                contentAlignment = Alignment.Center,
            ) {
                ScaledText(
                    exhibit.displayName.uppercase(),
                    style = CourtFont.miniLabel.copy(letterSpacing = 0.8.sp),
                    color = PleadColor.burgundy,
                    minimumScaleFactor = 0.8f,
                    maxLines = 1,
                )
            }
        }
        val stampModifier = Modifier.align(Alignment.TopEnd).padding(top = 5.dp, end = 3.dp)
        if (ruling != null) {
            CourtStamp.ruling(ruling, modifier = stampModifier, size = CourtStamp.Size.mini,
                landKey = motion?.let { "${exhibit.id}-${ruling.rawValue}" }, motion = motion)
        } else if (objected) {
            CourtStamp(text = "OBJECTION", color = PleadColor.burgundy, modifier = stampModifier, angle = -6f, size = CourtStamp.Size.mini,
                landKey = motion?.let { "${exhibit.id}-objection" }, motion = motion)
        }
    }
}

object CourtEaselMini {
    val size = Size(96f, 72f)

    /** Outer size including the frame. */
    val outer: Size get() = Size(size.width + 6f, size.height + 6f)
}

/** Async image with composed loading / failure states (shape-matched placeholder). */
@Composable
fun CourtExhibitImage(url: URI?, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Box(modifier.clip(shape)) {
        if (url == null) {
            ImagePlaceholder("photo", "No image")
        } else {
            SubcomposeAsyncImage(
                model = url.toString(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = {
                    Box(Modifier.fillMaxSize().background(PleadColor.parchment, shape), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = PleadColor.walnut, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    }
                },
                error = { ImagePlaceholder("exclamationmark.triangle.fill", "Couldn't load exhibit") },
            )
        }
    }
}

@Composable
private fun ImagePlaceholder(icon: String?, text: String?) {
    Column(
        Modifier.fillMaxSize().background(PleadColor.parchment, RoundedCornerShape(8.dp)),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) Icon(SFSymbol.icon(icon), contentDescription = null, tint = PleadColor.walnut, modifier = Modifier.size(22.dp))
        if (text != null) Text(text, style = CourtFont.caption2, color = PleadColor.walnut)
    }
}

/** Full exhibit, opened by tapping the easel card (sheet content). */
@Composable
fun CourtExhibitDetail(state: CourtroomState, exhibit: Exhibit, onDismiss: () -> Unit) {
    val stamps = CourtroomLogic.stamps(exhibit, state.turns)
    val owner = state.profile(exhibit.ownerId)
    Column(Modifier.fillMaxWidth().background(PleadColor.cream)) {
        CourtSheetTopBar(title = exhibit.displayName, trailingTitle = "Done", onTrailing = onDismiss)
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(PleadSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(PleadSpacing.l),
        ) {
            CourtEasel(
                exhibit = exhibit, url = state.exhibitURLs[exhibit.id], ownerName = owner?.displayName,
                ownerRole = owner?.let { state.kase.role(it.id) },
                objected = stamps.objected, objectionReason = stamps.objection, ruling = stamps.ruling,
                compact = false,
            )
            val note = exhibit.objectionNote
            if (!note.isNullOrEmpty()) {
                Text(note, style = CourtFont.ruling, color = PleadColor.cocoa)
            }
        }
    }
}
