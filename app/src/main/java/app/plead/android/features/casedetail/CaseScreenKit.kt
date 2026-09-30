// Android-only bridge for the case-side screens of wave 3d (Home, Cases, Case detail, File a case, Defence, Summons,
// Scheduling): the SwiftUI system pieces those Swift files lean on, drawn once in Plead's tokens.
//
//   NavigationStack inline bar + toolbar items    → CaseNavBar / NavTextButton
//   .sheet / interactiveDismissDisabled           → CaseSheetHost (ModalBottomSheet that refuses to close while blocked)
//   Picker(.segmented)                            → SegmentedPicker
//   .alert / .confirmationDialog                  → CaseDialog (title, message, actions)
//   TextField(axis: .vertical).lineLimit(a...b)   → AWTextField (inside `.awInput()`)
//   DatePicker(.graphical, date + time, in: range)→ TrialTimePicker
//   DatePicker(compact, in: ...Date.now)          → CompactDateTimePicker
//   ContentUnavailableView                        → ContentUnavailable
//   ShareLink                                     → shareText
//   SF Symbols not in SFSymbol.map                → symbolIcon
package app.plead.android.features.casedetail

import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowRightAlt
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerColors
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.plead.android.designsystem.PleadColor
import app.plead.android.designsystem.PleadRadius
import app.plead.android.designsystem.PleadSpacing
import app.plead.android.designsystem.PleadType
import app.plead.android.designsystem.SFSymbol
import app.plead.android.designsystem.TextStyleKind
import app.plead.android.designsystem.awInput
import app.plead.android.designsystem.pleadShadow
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.filter

// MARK: - Symbols

/** SF Symbols the case screens draw that the shared [SFSymbol] map does not carry (nearest Material glyph). */
private val caseSymbols: Map<String, ImageVector> = mapOf(
    "sparkles" to Icons.Filled.AutoAwesome,
    "pencil" to Icons.Filled.Edit,
    "equal" to Icons.Filled.DragHandle,
    "hand.point.right.fill" to Icons.AutoMirrored.Filled.ArrowRightAlt,
)

/** [SFSymbol.icon] with the case screens' extra symbols. */
fun symbolIcon(name: String): ImageVector = SFSymbol.map[name] ?: caseSymbols[name] ?: SFSymbol.icon(name)

// MARK: - Navigation bar

/** iOS inline navigation bar: 44 dp, leading / trailing toolbar items, a centred `navTitle`. */
@Composable
fun CaseNavBar(
    title: String?,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit = {},
    trailing: @Composable () -> Unit = {},
) {
    Box(modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = PleadSpacing.s)) {
        Box(Modifier.align(Alignment.CenterStart)) { leading() }
        if (title != null) {
            Text(
                title,
                style = PleadType.navTitle,
                color = PleadColor.cocoa,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 96.dp).semantics { heading() },
            )
        }
        Box(Modifier.align(Alignment.CenterEnd)) { trailing() }
    }
}

/** A toolbar button ("Cancel", "Add", "Close"): burgundy, bold for the confirmation action, 45 % when disabled. */
@Composable
fun NavTextButton(
    text: String,
    modifier: Modifier = Modifier,
    bold: Boolean = false,
    enabled: Boolean = true,
    color: Color = PleadColor.burgundy,
    icon: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = PleadSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (icon != null) Icon(symbolIcon(icon), contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        Text(text, style = PleadType.ui(17f, if (bold) FontWeight.Bold else FontWeight.Normal, relativeTo = TextStyleKind.body), color = color)
    }
}

// MARK: - Sheets

/** Swift `interactiveDismissDisabled`: content reports whether the sheet may be swiped / backed away. */
@Stable
class SheetDismissGuard {
    var blocked: Boolean by mutableStateOf(false)
}

val LocalSheetDismissGuard = staticCompositionLocalOf<SheetDismissGuard?> { null }

/** Report `interactiveDismissDisabled(blocked)` to the enclosing [CaseSheetHost]. */
@Composable
fun InteractiveDismissDisabled(blocked: Boolean) {
    val guard = LocalSheetDismissGuard.current ?: return
    LaunchedEffect(blocked) { guard.blocked = blocked }
}

/**
 * A root-level sheet (Swift `.sheet`): full height by default, [partial] for `.presentationDetents([.medium, .large])`.
 * While the content reports [InteractiveDismissDisabled] the swipe / back / scrim dismissal is refused; the
 * content's own Cancel still calls [onDismissRequest].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaseSheetHost(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    partial: Boolean = false,
    expand: Boolean = false,
    containerColor: Color = PleadColor.background,
    content: @Composable ColumnScope.() -> Unit,
) {
    val guard = remember { SheetDismissGuard() }
    val state = rememberModalBottomSheetState(
        skipPartiallyExpanded = !partial,
        confirmValueChange = { it != SheetValue.Hidden || !guard.blocked },
    )
    // `.presentationDetents(countering ? [.large] : [.medium, .large])`.
    LaunchedEffect(expand) { if (expand && partial) state.expand() }
    val dismiss by rememberUpdatedState(onDismissRequest)
    ModalBottomSheet(
        onDismissRequest = { if (!guard.blocked) dismiss() },
        sheetState = state,
        containerColor = containerColor,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = PleadRadius.card, topEnd = PleadRadius.card),
        modifier = modifier.statusBarsPadding(),
    ) {
        CompositionLocalProvider(LocalSheetDismissGuard provides guard) {
            Column(if (partial) Modifier else Modifier.fillMaxHeight()) { content() }
        }
    }
}

// MARK: - Segmented picker

/** Swift `Picker(...).pickerStyle(.segmented)`: a grey track with the selected segment raised in white. */
@Composable
fun SegmentedPicker(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(9.dp)
    Row(
        modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(Color(0x1F767680), shape)
            .padding(2.dp),
    ) {
        options.forEachIndexed { i, option ->
            val isSelected = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .then(
                        if (isSelected) {
                            Modifier
                                .pleadShadow(Color.Black.copy(alpha = 0.12f), radius = 4.dp, y = 2.dp, shape = RoundedCornerShape(7.dp))
                                .background(Color.White, RoundedCornerShape(7.dp))
                        } else {
                            Modifier
                        },
                    )
                    .semantics { this.selected = isSelected }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Tab,
                        onClick = { onSelect(i) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    option,
                    style = TextStyle(fontSize = PleadType.metadataMedium.fontSize, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium),
                    color = PleadColor.cocoa,
                    maxLines = 1,
                )
            }
        }
    }
}

// MARK: - Dialogs

/** One button of a [CaseDialog] (Swift `Button(title, role:)`). */
data class DialogAction(val title: String, val destructive: Boolean = false, val cancel: Boolean = false, val perform: () -> Unit = {})

/**
 * Swift `.alert` / `.confirmationDialog(titleVisibility: .visible)`: title, optional message, the actions in order.
 * A dialog with no cancel action gets the system "Cancel" (as iOS adds one to a confirmation dialog).
 */
@Composable
fun CaseDialog(title: String, message: String?, actions: List<DialogAction>, onDismiss: () -> Unit, addsCancel: Boolean = true) {
    val cancel = actions.firstOrNull { it.cancel } ?: if (addsCancel) DialogAction("Cancel", cancel = true) else null
    val others = actions.filter { !it.cancel }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PleadColor.paperWhite,
        title = { Text(title, style = PleadType.titleM, color = PleadColor.cocoa) },
        text = message?.let { { Text(it, style = PleadType.body, color = PleadColor.subtleText) } },
        confirmButton = {
            Row {
                others.forEach { a ->
                    TextButton(onClick = { onDismiss(); a.perform() }) {
                        Text(a.title, style = PleadType.uiButtonSecondary, color = if (a.destructive) PleadColor.danger else PleadColor.burgundy)
                    }
                }
            }
        },
        dismissButton = cancel?.let { c ->
            {
                TextButton(onClick = { onDismiss(); c.perform() }) {
                    Text(c.title, style = PleadType.uiButtonSecondary.copy(fontWeight = FontWeight.Bold), color = PleadColor.cocoa)
                }
            }
        },
    )
}

// MARK: - Text fields

/**
 * Swift `TextField(placeholder, text:, axis: .vertical).lineLimit(min...max).awInput()`: a paper-white input that grows
 * from [minLines] to [maxLines] and then scrolls; placeholder in the placeholder grey.
 */
@Composable
fun AWTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    minLines: Int = 1,
    maxLines: Int = 1,
    enabled: Boolean = true,
) {
    val style = PleadType.body.copy(color = PleadColor.cocoa)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        textStyle = style,
        singleLine = maxLines == 1,
        minLines = minLines,
        maxLines = maxLines,
        cursorBrush = SolidColor(PleadColor.burgundy),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = modifier.fillMaxWidth().awInput(),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(placeholder, style = style, color = Color(0x4D3C3C43).compositeOver(PleadColor.paperWhite))
                inner()
            }
        },
    )
}

private fun Color.compositeOver(background: Color): Color {
    val a = alpha
    return Color(red * a + background.red * (1 - a), green * a + background.green * (1 - a), blue * a + background.blue * (1 - a), 1f)
}

// MARK: - Dates

private fun Instant.localDate(zone: ZoneId): LocalDate = atZone(zone).toLocalDate()
private fun LocalDate.utcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
private fun utcMillisToLocalDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()

/** Keep [date] inside [range] (the Swift `DatePicker(in:)` clamp). */
fun clamp(date: Instant, range: ClosedRange<Instant>): Instant = when {
    date.isBefore(range.start) -> range.start
    date.isAfter(range.endInclusive) -> range.endInclusive
    else -> date
}

/** "20:00" / "8:00 PM" in the device's clock format. */
@Composable
private fun timeLabel(date: Instant, zone: ZoneId): String {
    val context = LocalContext.current
    val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
    return DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).format(date.atZone(zone))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun pickerColors(): DatePickerColors =
    DatePickerDefaults.colors(
        containerColor = PleadColor.paperWhite,
        selectedDayContainerColor = PleadColor.burgundy,
        selectedDayContentColor = PleadColor.cream,
        todayDateBorderColor = PleadColor.burgundy,
        todayContentColor = PleadColor.burgundy,
        selectedYearContainerColor = PleadColor.burgundy,
        dayContentColor = PleadColor.cocoa,
        titleContentColor = PleadColor.cocoa,
        headlineContentColor = PleadColor.cocoa,
        weekdayContentColor = PleadColor.subtleText,
        navigationContentColor = PleadColor.burgundy,
    )

/**
 * Swift `DatePicker(selection:, in: range, displayedComponents: [.date, .hourAndMinute]).datePickerStyle(.graphical)`:
 * the month calendar limited to the range's days, then the "Time" row (a clock dialog). The result is clamped to [range].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrialTimePicker(value: Instant, range: ClosedRange<Instant>, onChange: (Instant) -> Unit, modifier: Modifier = Modifier) {
    val zone = remember { ZoneId.systemDefault() }
    val first = range.start.localDate(zone)
    val last = range.endInclusive.localDate(zone)
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val state = rememberDatePickerState(
        initialSelectedDateMillis = value.localDate(zone).utcMillis(),
        yearRange = first.year..last.year,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                val d = utcMillisToLocalDate(utcTimeMillis)
                return !d.isBefore(first) && !d.isAfter(last)
            }

            override fun isSelectableYear(year: Int): Boolean = year in first.year..last.year
        },
    )
    LaunchedEffect(state) {
        snapshotFlow { state.selectedDateMillis }.filter { it != null }.collect { ms ->
            val day = utcMillisToLocalDate(ms!!)
            val time = current.atZone(zone).toLocalTime()
            val picked = ZonedDateTime.of(day, time, zone).toInstant()
            if (picked != current) change(clamp(picked, range))
        }
    }
    var editingTime by remember { mutableStateOf(false) }
    Column(modifier) {
        DatePicker(
            state = state,
            title = null,
            headline = null,
            showModeToggle = false,
            colors = pickerColors(),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = PleadSpacing.m, vertical = PleadSpacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Time", style = PleadType.body, color = PleadColor.cocoa, modifier = Modifier.weight(1f))
            PickerChip(timeLabel(value, zone)) { editingTime = true }
        }
    }
    if (editingTime) {
        TimeDialog(value, zone, onDismiss = { editingTime = false }) { t ->
            val day = value.atZone(zone).toLocalDate()
            change(clamp(ZonedDateTime.of(day, t, zone).toInstant(), range))
        }
    }
}

/**
 * Swift compact `DatePicker(label, selection:, in: ...max)`: the label (unless hidden), then a date chip and a time
 * chip that open the system pickers. The result never passes [max].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactDateTimePicker(value: Instant, max: Instant, onChange: (Instant) -> Unit, modifier: Modifier = Modifier, label: String? = null) {
    val zone = remember { ZoneId.systemDefault() }
    var editingDate by remember { mutableStateOf(false) }
    var editingTime by remember { mutableStateOf(false) }
    val clampMax: (Instant) -> Instant = { if (it.isAfter(max)) max else it }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
        if (label != null) {
            Text(label, style = PleadType.body, color = PleadColor.cocoa)
            Spacer(Modifier.weight(1f))
        }
        PickerChip(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(Locale.getDefault(), "dMMMy"), Locale.getDefault()).format(value.atZone(zone))) {
            editingDate = true
        }
        PickerChip(timeLabel(value, zone)) { editingTime = true }
    }
    if (editingDate) {
        val maxDay = max.localDate(zone)
        val state = rememberDatePickerState(
            initialSelectedDateMillis = value.localDate(zone).utcMillis(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = !utcMillisToLocalDate(utcTimeMillis).isAfter(maxDay)
                override fun isSelectableYear(year: Int): Boolean = year <= maxDay.year
            },
        )
        DatePickerDialog(
            onDismissRequest = { editingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    editingDate = false
                    state.selectedDateMillis?.let { ms ->
                        val day = utcMillisToLocalDate(ms)
                        onChange(clampMax(ZonedDateTime.of(day, value.atZone(zone).toLocalTime(), zone).toInstant()))
                    }
                }) { Text("Done", color = PleadColor.burgundy, style = PleadType.uiButton) }
            },
            colors = pickerColors(),
        ) {
            DatePicker(state = state, showModeToggle = false, colors = pickerColors())
        }
    }
    if (editingTime) {
        TimeDialog(value, zone, onDismiss = { editingTime = false }) { t ->
            onChange(clampMax(ZonedDateTime.of(value.atZone(zone).toLocalDate(), t, zone).toInstant()))
        }
    }
}

@Composable
private fun PickerChip(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = PleadType.body.copy(fontWeight = FontWeight.Medium),
        color = PleadColor.burgundy,
        modifier = Modifier
            .background(Color(0x1F767680), RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(value: Instant, zone: ZoneId, onDismiss: () -> Unit, onPicked: (LocalTime) -> Unit) {
    val context = LocalContext.current
    val t = value.atZone(zone)
    val state = rememberTimePickerState(initialHour = t.hour, initialMinute = t.minute, is24Hour = DateFormat.is24HourFormat(context))
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PleadColor.paperWhite,
        confirmButton = {
            TextButton(onClick = { onDismiss(); onPicked(LocalTime.of(state.hour, state.minute)) }) {
                Text("Done", color = PleadColor.burgundy, style = PleadType.uiButton)
            }
        },
        text = {
            TimePicker(
                state = state,
                colors = TimePickerDefaults.colors(
                    clockDialSelectedContentColor = PleadColor.cream,
                    selectorColor = PleadColor.burgundy,
                    timeSelectorSelectedContainerColor = PleadColor.parchment,
                    timeSelectorSelectedContentColor = PleadColor.cocoa,
                    periodSelectorSelectedContainerColor = PleadColor.parchment,
                ),
            )
        },
    )
}

// MARK: - Empty states

/** Swift `ContentUnavailableView(title, systemImage:, description:)`. */
@Composable
fun ContentUnavailable(
    title: String,
    systemImage: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    color: Color = PleadColor.subtleText,
    titleColor: Color = PleadColor.cocoa,
) {
    Column(
        modifier.fillMaxSize().padding(PleadSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(PleadSpacing.s, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(symbolIcon(systemImage), contentDescription = null, tint = color, modifier = Modifier.size(48.dp))
        Text(title, style = PleadType.ui(22f, FontWeight.Bold, relativeTo = TextStyleKind.title2), color = titleColor, textAlign = TextAlign.Center)
        if (description != null) Text(description, style = PleadType.body, color = color, textAlign = TextAlign.Center)
    }
}

// MARK: - Share

/** Swift `ShareLink(item: url, message: Text(message))`: the system share sheet with the message and the link. */
fun shareText(context: Context, message: String, url: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "$message\n$url")
    }
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** A hairline rule (Swift `Divider().overlay(color)`). */
@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = PleadColor.separator) {
    Box(modifier.fillMaxWidth().height(0.5.dp).background(color))
}

// MARK: - Scroll anchors (Swift `ScrollViewReader.scrollTo(id, anchor:)`, the demo harness `AWScroll`)

/** Where each anchored child sits inside a scrolled column (its top and height, in px). */
@Stable
class ScrollAnchors {
    internal val frames = mutableMapOf<String, Pair<Int, Int>>()

    /**
     * Scroll [scroll] so the anchor [id] sits at the top ([center] false) or the centre of a viewport [viewport] px
     * tall. Returns false when the anchor is not laid out.
     */
    suspend fun scrollTo(id: String, scroll: androidx.compose.foundation.ScrollState, viewport: Int, center: Boolean): Boolean {
        val (top, height) = frames[id] ?: return false
        val target = if (center) top + height / 2 - viewport / 2 else top
        scroll.animateScrollTo(target.coerceIn(0, scroll.maxValue))
        return true
    }
}

/** Swift `.id(anchor)`: records this child's frame in its scrolled parent. */
fun Modifier.scrollAnchor(anchors: ScrollAnchors, id: String): Modifier = onGloballyPositioned {
    anchors.frames[id] = it.positionInParent().y.toInt() to it.size.height
}

// MARK: - Date formats (Swift `Date.formatted(date:time:)`)

object CaseDates {
    private fun fmt(pattern: String, date: Instant, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, pattern), locale).format(date.atZone(zone))

    /** `.formatted(date: .abbreviated, time: .omitted)`: "24 Sept 2026". */
    fun abbreviatedDate(date: Instant): String = fmt("dMMMy", date)

    /** `.formatted(date: .abbreviated, time: .shortened)`: "24 Sept 2026, 20:00". */
    fun abbreviatedDateTime(date: Instant): String = fmt("dMMMyjmm", date)

    /** `.formatted(date: .complete, time: .shortened)`: "Thursday, 24 September 2026, 20:00". */
    fun completeDateTime(date: Instant): String = fmt("EEEEdMMMMyjmm", date)

    /** `.formatted(.dateTime.weekday(.wide).day().month(.wide))`: "Thursday 24 September". */
    fun weekdayDayMonth(date: Instant): String = fmt("EEEEdMMMM", date)

    /** `.formatted(date: .omitted, time: .shortened)`: "20:00" / "8:00 PM". */
    fun shortTime(date: Instant): String = fmt("jmm", date)
}

// MARK: - Chip with the case symbols

/** The design system's `Chip` (same look) for symbols only [symbolIcon] knows ("sparkles", "pencil", "equal", …). */
@Composable
fun CaseChip(text: String, foreground: Color, systemImage: String?, modifier: Modifier = Modifier) {
    if (systemImage == null || SFSymbol.map.containsKey(systemImage)) {
        app.plead.android.designsystem.Chip(text, modifier = modifier, foreground = foreground, systemImage = systemImage)
        return
    }
    Row(
        modifier
            .background(foreground.copy(alpha = 0.1f), androidx.compose.foundation.shape.CircleShape)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(symbolIcon(systemImage), contentDescription = null, tint = foreground, modifier = Modifier.size(11.dp))
        Text(
            text.uppercase(),
            style = PleadType.labelCaps.copy(letterSpacing = androidx.compose.ui.unit.TextUnit(0.6f, androidx.compose.ui.unit.TextUnitType.Sp)),
            color = foreground,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// MARK: - Disabled primary button

/**
 * `PrimaryButton(...).disabled(!enabled)`: the design system's [app.plead.android.designsystem.PrimaryButton] look,
 * with the SwiftUI `.disabled` state (45 %, no taps) that its Kotlin signature does not take.
 */
@Composable
fun FormPrimaryButton(
    title: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    systemImage: String? = null,
    kind: app.plead.android.designsystem.AWButtonKind = app.plead.android.designsystem.AWButtonKind.primary,
    isLoading: Boolean = false,
    action: () -> Unit,
) {
    val style = app.plead.android.designsystem.AWButtonStyle.aw(kind)
    app.plead.android.designsystem.AWButton(
        onClick = action,
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = title },
        style = style,
        enabled = enabled && !isLoading,
    ) {
        if (isLoading) {
            androidx.compose.material3.CircularProgressIndicator(
                color = if (kind == app.plead.android.designsystem.AWButtonKind.primary) PleadColor.cream else PleadColor.burgundy,
                strokeWidth = 2.dp,
                modifier = Modifier.size(20.dp),
            )
        } else {
            Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.clearAndSetSemantics { })
            if (systemImage != null) Icon(symbolIcon(systemImage), contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}
