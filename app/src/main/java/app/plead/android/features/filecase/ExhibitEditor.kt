// Port of ArgueWin/Features/FileCase/ExhibitEditor.swift.
//
// "Add evidence" list shared by FileCase and Defence, the one-exhibit composer and the image compressor.
// PhotosPicker → the Android Photo Picker (`PickVisualMedia`, images only: the system picker has no screenshots-only
// filter, so a screenshot is chosen from all images). Images are downscaled to ≤ 1600 px and re-encoded as JPEG
// (quality 0.8) exactly as `ImageCompressor.jpeg`; filing uploads them through `StorageService` (CaseStore.stage).
package app.plead.android.features.filecase

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.core.graphics.scale
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.plead.android.designsystem.ExhibitThumbnail
import app.plead.android.designsystem.ExhibitTileCopy
import app.plead.android.designsystem.PleadColor
import app.plead.android.designsystem.PleadRadius
import app.plead.android.designsystem.PleadSpacing
import app.plead.android.designsystem.PleadType
import app.plead.android.features.casedetail.AWTextField
import app.plead.android.features.casedetail.CaseNavBar
import app.plead.android.features.casedetail.CaseSheetHost
import app.plead.android.features.casedetail.CaseType
import app.plead.android.features.casedetail.CompactDateTimePicker
import app.plead.android.features.casedetail.Hairline
import app.plead.android.features.casedetail.NavTextButton
import app.plead.android.features.casedetail.RecordLabel
import app.plead.android.features.casedetail.SegmentedPicker
import app.plead.android.features.casedetail.symbolIcon
import app.plead.android.models.ExhibitLabel
import app.plead.android.models.ExhibitType
import app.plead.android.models.Role
import app.plead.android.services.DraftExhibit
import app.plead.android.services.label
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.time.Instant
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Add evidence" list shared by FileCase and Defence. No cap: the list grows and the host
 * scroll view scrolls. Order = presentation order in court (A, B, C… Z, AA…).
 */
@Composable
fun ExhibitEditor(drafts: List<DraftExhibit>, onChange: (List<DraftExhibit>) -> Unit, ownerRole: Role?, modifier: Modifier = Modifier) {
    var composing by remember { mutableStateOf(false) }

    fun move(index: Int, offset: Int) {
        val target = index + offset
        if (index !in drafts.indices || target !in drafts.indices) return
        val list = drafts.toMutableList()
        val a = list[index]; list[index] = list[target]; list[target] = a
        onChange(list)
    }
    fun remove(id: UUID) = onChange(drafts.filter { it.id != id })

    Column(modifier.animateContentSize(tween(250)), verticalArrangement = Arrangement.spacedBy(PleadSpacing.m)) {
        Text(
            if (drafts.isEmpty()) {
                "Photos, screenshots, a quote, or a dated receipt. Add as much as you like. Evidence is optional, but judges love it."
            } else {
                "You'll show these in court one at a time, in this order."
            },
            style = PleadType.body,
            color = PleadColor.subtleText,
        )

        if (drafts.isNotEmpty()) {
            val shape = RoundedCornerShape(PleadRadius.card)
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(PleadColor.paperWhite, shape)
                    .border(1.dp, PleadColor.separator, shape)
                    .padding(horizontal = PleadSpacing.m),
            ) {
                drafts.forEachIndexed { index, draft ->
                    if (index > 0) Hairline(Modifier.padding(start = 64.dp + PleadSpacing.m))
                    DraftExhibitRow(
                        label = drafts.label(index),
                        draft = draft,
                        ownerRole = ownerRole,
                        canMoveUp = index > 0,
                        canMoveDown = index < drafts.size - 1,
                        onMove = { move(index, it) },
                        onRemove = { remove(draft.id) },
                    )
                }
            }
        }

        val tile = RoundedCornerShape(PleadRadius.tile)
        Row(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 52.dp)
                .background(PleadColor.paperWhite.copy(alpha = 0.6f), tile)
                .dashedBorder(PleadColor.burgundy.copy(alpha = 0.45f), 1.5.dp, PleadRadius.tile)
                .clip(tile)
                .clickable { composing = true },
            horizontalArrangement = Arrangement.spacedBy(PleadSpacing.s, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(symbolIcon("plus"), contentDescription = null, tint = PleadColor.burgundy, modifier = Modifier.size(20.dp))
            Text(if (drafts.isEmpty()) "Add evidence" else "Add more evidence", style = PleadType.uiButtonSecondary, color = PleadColor.burgundy)
        }
    }

    if (composing) {
        ExhibitComposer(onAdd = { onChange(drafts + it) }, onDismiss = { composing = false })
    }
}

/** A dashed rounded outline (Swift `strokeBorder(style: StrokeStyle(lineWidth:, dash: [6, 4]))`). */
internal fun Modifier.dashedBorder(color: Color, width: Dp, radius: Dp, dash: Float = 6f, gap: Float = 4f): Modifier = drawBehind {
    val w = width.toPx()
    drawRoundRect(
        color = color,
        topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
        size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
        cornerRadius = CornerRadius(radius.toPx() - w / 2),
        style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash.dp.toPx(), gap.dp.toPx()))),
    )
}

/** One row of the evidence list: thumbnail, "Exhibit A", caption, and a ⋯ menu (move / remove). */
@Composable
private fun DraftExhibitRow(
    label: ExhibitLabel,
    draft: DraftExhibit,
    ownerRole: Role?,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    val typeName = ExhibitEditorCopy.typeName(draft.type)
    val detail = ExhibitEditorCopy.detail(draft)
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .padding(vertical = PleadSpacing.s + 2.dp)
            .semantics(mergeDescendants = true) {
                customActions = listOf(
                    CustomAccessibilityAction("Move up") { if (canMoveUp) onMove(-1); canMoveUp },
                    CustomAccessibilityAction("Move down") { if (canMoveDown) onMove(1); canMoveDown },
                    CustomAccessibilityAction("Remove") { onRemove(); true },
                )
            },
        horizontalArrangement = Arrangement.spacedBy(PleadSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val thumb = RoundedCornerShape(10.dp)
        Box(Modifier.size(64.dp).clip(thumb).border(1.dp, PleadColor.walnut.copy(alpha = 0.2f), thumb)) {
            ExhibitThumbnail(type = draft.type, caption = draft.caption, body = draft.body, imageData = draft.imageData, compact = true)
            Box(
                Modifier
                    .padding(4.dp)
                    .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
                    .background(ownerRole?.let { PleadColor.role(it) } ?: PleadColor.mahogany, RoundedCornerShape(5.dp))
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label.rawValue, style = PleadType.labelCaps, color = PleadColor.cream)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Exhibit ${label.rawValue}", style = CaseType.exhibitTitle, color = PleadColor.walnut)
            Text(
                draft.caption.ifEmpty { ExhibitEditorCopy.capitalized(typeName) },
                style = PleadType.body,
                color = PleadColor.cocoa,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(detail, style = PleadType.caption, color = PleadColor.subtleText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box {
            Box(
                Modifier
                    .size(44.dp)
                    .clickable { menu = true }
                    .semantics { contentDescription = "Options for exhibit ${label.rawValue}" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(symbolIcon("ellipsis.circle"), contentDescription = null, tint = PleadColor.walnut, modifier = Modifier.size(22.dp))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = PleadColor.paperWhite) {
                DropdownMenuItem(
                    text = { Text("Move up", style = PleadType.body) },
                    leadingIcon = { Icon(symbolIcon("arrow.up"), contentDescription = null) },
                    enabled = canMoveUp,
                    onClick = { menu = false; onMove(-1) },
                    colors = MenuDefaults.itemColors(textColor = PleadColor.cocoa, leadingIconColor = PleadColor.cocoa),
                )
                DropdownMenuItem(
                    text = { Text("Move down", style = PleadType.body) },
                    leadingIcon = { Icon(symbolIcon("arrow.down"), contentDescription = null) },
                    enabled = canMoveDown,
                    onClick = { menu = false; onMove(1) },
                    colors = MenuDefaults.itemColors(textColor = PleadColor.cocoa, leadingIconColor = PleadColor.cocoa),
                )
                DropdownMenuItem(
                    text = { Text("Remove", style = PleadType.body) },
                    leadingIcon = { Icon(symbolIcon("trash"), contentDescription = null) },
                    onClick = { menu = false; onRemove() },
                    colors = MenuDefaults.itemColors(textColor = PleadColor.danger, leadingIconColor = PleadColor.danger),
                )
            }
        }
    }
}

/** The evidence row's copy (Swift `DraftExhibitRow.typeName` / `detail`). */
object ExhibitEditorCopy {
    fun typeName(type: ExhibitType): String = when (type) {
        ExhibitType.photo -> "photo"
        ExhibitType.screenshot -> "screenshot"
        ExhibitType.voice -> "voice note"
        ExhibitType.text -> "quote"
        ExhibitType.receipt -> "receipt"
    }

    /** Swift `String.capitalized`: every word's first letter upper-cased. */
    fun capitalized(s: String): String = s.split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    fun detail(draft: DraftExhibit): String {
        val typeName = typeName(draft.type)
        val d = draft.occurredAt
        if (d != null && DraftExhibit.supportsDate(draft.type)) return "${capitalized(typeName)} · ${ExhibitTileCopy.dayMonthTime(d)}"
        val b = draft.body
        if (draft.type == ExhibitType.text && !b.isNullOrEmpty()) return "“$b”"
        return capitalized(typeName)
    }

    /** The composer's segment titles. */
    fun title(t: ExhibitType): String = when (t) {
        ExhibitType.photo -> "Photo"
        ExhibitType.screenshot -> "Screenshot"
        ExhibitType.text -> "Quote"
        ExhibitType.receipt -> "Receipt"
        ExhibitType.voice -> "Voice"
    }

    /** Voice was cut from the MVP. */
    val types: List<ExhibitType> = listOf(ExhibitType.photo, ExhibitType.screenshot, ExhibitType.text, ExhibitType.receipt)

    /** The Add button's rule: a caption, and the picked image or the typed text. */
    fun canAdd(type: ExhibitType, caption: String, text: String, imageData: ByteArray?): Boolean {
        val isImage = type == ExhibitType.photo || type == ExhibitType.screenshot
        return caption.trim().isNotEmpty() && (if (isImage) imageData != null else text.trim().isNotEmpty())
    }

    /** The draft the Add button creates. */
    fun draft(
        type: ExhibitType,
        caption: String,
        text: String,
        imageData: ByteArray?,
        receiptDate: Instant,
        screenshotDated: Boolean,
        screenshotDate: Instant,
    ): DraftExhibit {
        val isImage = type == ExhibitType.photo || type == ExhibitType.screenshot
        val body: String? = when (type) {
            ExhibitType.text -> text
            ExhibitType.receipt -> DraftExhibit.receiptLine(receiptDate, text)
            else -> null
        }
        val occurredAt: Instant? = when (type) {
            ExhibitType.receipt -> receiptDate
            ExhibitType.screenshot -> if (screenshotDated) screenshotDate else null
            else -> null
        }
        return DraftExhibit(type = type, caption = caption.trim(), body = body, imageData = if (isImage) imageData else null, occurredAt = occurredAt)
    }
}

/** One exhibit: type, content, caption (a sheet over the form). */
@Composable
fun ExhibitComposer(onAdd: (DraftExhibit) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf(ExhibitType.photo) }
    var caption by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var receiptDate by remember { mutableStateOf(Instant.now()) }
    // Screenshots: optional "when was this?" date.
    var screenshotDated by remember { mutableStateOf(false) }
    var screenshotDate by remember { mutableStateOf(Instant.now()) }
    var imageData by remember { mutableStateOf<ByteArray?>(null) }
    var loadingImage by remember { mutableStateOf(false) }

    val isImage = type == ExhibitType.photo || type == ExhibitType.screenshot
    val canAdd = ExhibitEditorCopy.canAdd(type, caption, text, imageData)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        loadingImage = true
        scope.launch {
            imageData = ImageCompressor.jpeg(context, uri)
            loadingImage = false
        }
    }

    CaseSheetHost(onDismissRequest = onDismiss) {
        CaseNavBar(
            title = "New exhibit",
            leading = { NavTextButton("Cancel", onClick = onDismiss) },
            trailing = {
                NavTextButton("Add", bold = true, enabled = canAdd) {
                    onAdd(ExhibitEditorCopy.draft(type, caption, text, imageData, receiptDate, screenshotDated, screenshotDate))
                    onDismiss()
                }
            },
        )
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(PleadSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(PleadSpacing.l),
        ) {
            SegmentedPicker(
                options = ExhibitEditorCopy.types.map { ExhibitEditorCopy.title(it) },
                selected = ExhibitEditorCopy.types.indexOf(type),
                onSelect = { type = ExhibitEditorCopy.types[it] },
            )

            if (isImage) {
                ImagePickerLabel(
                    imageData = imageData,
                    loading = loadingImage,
                    isScreenshot = type == ExhibitType.screenshot,
                    modifier = Modifier
                        .semantics { contentDescription = if (imageData == null) "Choose image" else "Change image" }
                        .clickable { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                )
                if (type == ExhibitType.screenshot) {
                    Column(verticalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
                        ToggleRow("Add date and time", "When the screenshot was taken, for the record.", screenshotDated) { screenshotDated = it }
                        AnimatedVisibility(screenshotDated, enter = fadeIn(tween(200)) + expandVertically(tween(200)), exit = fadeOut(tween(200)) + shrinkVertically(tween(200))) {
                            CompactDateTimePicker(screenshotDate, max = Instant.now(), onChange = { screenshotDate = it }, label = "Taken")
                        }
                    }
                }
            } else if (type == ExhibitType.text) {
                Column(verticalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
                    RecordLabel("The quote")
                    AWTextField(text, { text = it }, "\"I'll be home by nine\"", minLines = 3, maxLines = 8)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
                    RecordLabel("When")
                    CompactDateTimePicker(receiptDate, max = Instant.now(), onChange = { receiptDate = it })
                    RecordLabel("What happened")
                    AWTextField(text, { text = it }, "said he'd be home by 9", minLines = 2, maxLines = 4)
                    if (text.isNotEmpty()) {
                        val line = DraftExhibit.receiptLine(receiptDate, text)
                        Text(
                            line,
                            style = PleadType.metadataMedium.copy(fontFamily = FontFamily.Monospace),
                            color = PleadColor.cocoa,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(PleadColor.parchment, RoundedCornerShape(PleadRadius.tile))
                                .padding(PleadSpacing.m)
                                .clearAndSetSemantics { contentDescription = "Receipt preview: $line" },
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
                RecordLabel("Caption")
                AWTextField(caption, { caption = it }, "What this proves")
            }
        }
    }
}

/** Swift `Toggle(isOn:) { title + subtitle }.tint(burgundy)`. */
@Composable
internal fun ToggleRow(title: String, subtitle: String, isOn: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!isOn) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PleadSpacing.m),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = PleadType.titleM, color = PleadColor.cocoa)
            Text(subtitle, style = PleadType.metadata, color = PleadColor.subtleText)
        }
        Switch(
            checked = isOn,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = PleadColor.burgundy,
                checkedThumbColor = Color.White,
                uncheckedTrackColor = Color(0x29787880),
                uncheckedThumbColor = Color.White,
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

/** Downscales to ≤ 1600 px and re-encodes as JPEG off the main thread. */
object ImageCompressor {
    /** Swift `jpeg(from:maxDimension:quality:)` for picked bytes. */
    suspend fun jpeg(data: ByteArray, maxDimension: Int = 1600, quality: Float = 0.8f): ByteArray? = withContext(Dispatchers.Default) {
        val bitmap = decode(data, maxDimension) ?: return@withContext null
        encode(bitmap, maxDimension, quality)
    }

    /** Reads the picked image and compresses it. */
    suspend fun jpeg(context: Context, uri: Uri, maxDimension: Int = 1600, quality: Float = 0.8f): ByteArray? {
        val raw = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        } ?: return null
        return jpeg(raw, maxDimension, quality)
    }

    /** Decodes with the photo's orientation applied (ImageDecoder, API 28+), sub-sampled near the target size. */
    private fun decode(data: ByteArray, maxDimension: Int): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(data))) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                val scale = min(1.0, maxDimension.toDouble() / max(w, h))
                if (scale < 1.0) decoder.setTargetSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDimension) sample *= 2
            BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }.getOrNull()

    private fun encode(bitmap: Bitmap, maxDimension: Int, quality: Float): ByteArray {
        val scale = min(1.0, maxDimension.toDouble() / max(bitmap.width, bitmap.height))
        val sized = if (scale < 1.0) {
            bitmap.scale((bitmap.width * scale).roundToInt().coerceAtLeast(1), (bitmap.height * scale).roundToInt().coerceAtLeast(1))
        } else {
            bitmap
        }
        val out = ByteArrayOutputStream()
        sized.compress(Bitmap.CompressFormat.JPEG, (quality * 100).roundToInt(), out)
        return out.toByteArray()
    }
}

/** The image slot of the composer: dashed paper tile, the picked image, a spinner, or "Choose a photo". */
@Composable
private fun ImagePickerLabel(imageData: ByteArray?, loading: Boolean, isScreenshot: Boolean, modifier: Modifier = Modifier) {
    val bitmap: ImageBitmap? = remember(imageData) {
        imageData?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }
    }
    val shape = RoundedCornerShape(PleadRadius.tile)
    Box(
        modifier
            .fillMaxWidth()
            .height(220.dp)
            .background(PleadColor.paperWhite, shape)
            .dashedBorder(PleadColor.separator, 1.5.dp, PleadRadius.tile),
        contentAlignment = Alignment.Center,
    ) {
        when {
            bitmap != null -> Image(
                bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(PleadSpacing.s).clip(shape),
            )
            loading -> CircularProgressIndicator(color = PleadColor.subtleText, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
            else -> Row(horizontalArrangement = Arrangement.spacedBy(PleadSpacing.s), verticalAlignment = Alignment.CenterVertically) {
                Icon(symbolIcon("photo.on.rectangle"), contentDescription = null, tint = PleadColor.cocoa, modifier = Modifier.size(20.dp))
                Text(if (isScreenshot) "Choose a screenshot" else "Choose a photo", style = PleadType.titleM, color = PleadColor.cocoa)
            }
        }
    }
}
