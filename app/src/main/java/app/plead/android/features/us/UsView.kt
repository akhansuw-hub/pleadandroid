// Port of ArgueWin/Features/Us/UsView.swift.
package app.plead.android.features.us

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.plead.android.app.AppModel
import app.plead.android.app.AppRouter
import app.plead.android.app.AppSheet
import app.plead.android.designsystem.Chip
import app.plead.android.designsystem.Color
import app.plead.android.designsystem.CourtTabHeader
import app.plead.android.designsystem.LegalLabel
import app.plead.android.designsystem.PixelAvatar
import app.plead.android.designsystem.PixelAvatarView
import app.plead.android.designsystem.PleadColor
import app.plead.android.designsystem.PleadCopy
import app.plead.android.designsystem.PleadFont
import app.plead.android.designsystem.PleadMotion
import app.plead.android.designsystem.PleadRadius
import app.plead.android.designsystem.PleadSpacing
import app.plead.android.designsystem.PleadType
import app.plead.android.designsystem.PrimaryButton
import app.plead.android.designsystem.ScalesMark
import app.plead.android.designsystem.TextStyleKind
import app.plead.android.designsystem.accessibilityReduceMotion
import app.plead.android.designsystem.awCard
import app.plead.android.designsystem.fixedSp
import app.plead.android.designsystem.monospacedDigit
import app.plead.android.designsystem.saturation
import app.plead.android.models.Avatar
import app.plead.android.models.JudgePersona
import app.plead.android.services.CaseStore

/** Points per art pixel of the courtroom's `JudgeSprite` (18 × 19 cells incl. outline, CourtArt.swift). */
object UsJudgeSprite {
    const val columns = 18
    const val rows = 19
}

/**
 * The Us tab (MainTabScreen mounts it for `AppTab.us`). [editAvatar] is the onboarding wave's `EditAvatarView`
 * (pushed like the iOS NavigationLink; `onDone` pops back); [judgeSprite] draws the courtroom's `JudgeSprite` for a
 * persona at a cell size (wave 3a). A null [editAvatar] hides the Edit avatar links; a null [judgeSprite] leaves the
 * judge tiles empty.
 */
@Composable
fun UsTab(
    model: AppModel,
    modifier: Modifier = Modifier,
    editAvatar: (@Composable (onDone: () -> Unit) -> Unit)? = null,
    judgeSprite: (@Composable (persona: JudgePersona, cell: Dp) -> Unit)? = null,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    if (editing && editAvatar != null) {
        BackHandler { editing = false }
        Column(modifier.fillMaxSize().background(PleadColor.background).statusBarsPadding()) {
            Box(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = PleadSpacing.s)) {
                TextButton(onClick = { editing = false }, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null, tint = PleadColor.burgundy)
                    Text("Us", style = PleadFont.body, color = PleadColor.burgundy)
                }
                Text("Edit avatar", style = PleadType.navTitle, color = PleadColor.cocoa, modifier = Modifier.align(Alignment.Center).semantics { heading() })
            }
            Box(Modifier.weight(1f).fillMaxWidth()) { editAvatar { editing = false } }
        }
        return
    }
    UsView(
        store = model.store,
        router = model.router,
        modifier = modifier,
        onEditAvatar = if (editAvatar != null) ({ editing = true }) else null,
        judgeSprite = judgeSprite,
    )
}

/**
 * The partnership (CONTRACTS-v2 amendment af): the shared portrait and "{Me} & {Partner}", the pairing line,
 * the record (decided cases only, [UsRecord]), then the presiding judge. Without a linked partner the top card
 * is the invite state (the existing link flow). TalkBack reads title → pairing → record → judges.
 */
@Composable
fun UsView(
    store: CaseStore,
    router: AppRouter,
    modifier: Modifier = Modifier,
    onEditAvatar: (() -> Unit)? = null,
    judgeSprite: (@Composable (persona: JudgePersona, cell: Dp) -> Unit)? = null,
) {
    val reduceMotion = accessibilityReduceMotion()
    val partnership = UsPartnership.make(store.partner, store.couple)
    val record = UsRecord.tally(store.cases, store.verdicts, store.me?.id, store.couple?.id)

    Column(modifier.fillMaxSize().background(PleadColor.background).statusBarsPadding()) {
        CourtTabHeader(title = "Us", modifier = Modifier.background(PleadColor.background).padding(bottom = PleadSpacing.s)) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(PleadColor.paperWhite, CircleShape)
                    .border(1.dp, PleadColor.separator, CircleShape)
                    .clickable(role = Role.Button) { router.sheet = AppSheet.settings }
                    .semantics { contentDescription = "Settings"; testTag = "us.settings" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Settings, contentDescription = null, tint = PleadColor.cocoa, modifier = Modifier.size(22.dp))   // gearshape
            }
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PleadSpacing.l)
                .padding(top = PleadSpacing.s, bottom = PleadSpacing.xl),   // breathing room above the tab bar
            verticalArrangement = Arrangement.spacedBy(PleadSpacing.l),
        ) {
            // 180–250 ms fades only (amendment af); none with Reduce Motion.
            AnimatedContent(
                targetState = partnership,
                transitionSpec = {
                    val ms = if (reduceMotion) 0 else 220
                    fadeIn(tween(ms, easing = PleadMotion.easeInOut)) togetherWith fadeOut(tween(ms, easing = PleadMotion.easeInOut))
                },
                label = "partnership",
            ) { state ->
                when (state) {
                    UsPartnership.paired -> PairCard(store, onEditAvatar)
                    UsPartnership.invite -> InviteCard(store, router, onEditAvatar)
                }
            }
            RecordCard(store, record, paired = partnership == UsPartnership.paired)
            JudgeCard(store, judgeSprite)
        }
    }
}

// MARK: - Portraits

/** A pixel portrait on a parchment tile at an exact integer scale (16 art pixels × `cell`), named for TalkBack. */
@Composable
private fun UsPortrait(avatar: Avatar, name: String, isYou: Boolean = false, cell: Dp = 5.dp) {
    val tile = usPortraitTile(cell)
    val art = cell * PixelAvatar.side
    val shape = RoundedCornerShape(PleadRadius.tile)
    Box(
        Modifier
            .size(tile)
            .semantics { contentDescription = if (isYou) "$name's portrait, you" else "$name's portrait" },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            Modifier
                .matchParentSize()
                .clip(shape)
                .background(Brush.verticalGradient(listOf(PleadColor.parchment, Color(hex = 0xEAD6C4))), shape)
                .border(1.5.dp, PleadColor.walnut.copy(alpha = 0.28f), shape),
            contentAlignment = Alignment.BottomCenter,
        ) {
            // Shoulders on the tile's bottom edge, like the courtroom podium portraits.
            PixelAvatarView(avatar, size = art, modifier = Modifier.offset(y = cell + 3.dp).clearAndSetSemantics { })
        }
        if (isYou) {
            val density = LocalDensity.current
            // `.dynamicTypeSize(...DynamicTypeSize.xxLarge)`: the badge stops growing at the largest non-accessibility size.
            val capped = kotlin.math.min(density.fontScale, 1.35f)
            Text(
                "YOU",
                style = androidx.compose.ui.text.TextStyle(
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = fixedSp(11f * capped),
                    letterSpacing = 1.sp,
                ),
                color = PleadColor.cream,
                modifier = Modifier
                    .offset(y = 8.dp)
                    .background(PleadColor.burgundy, CircleShape)
                    .border(1.5.dp, PleadColor.cream, CircleShape)
                    .padding(horizontal = 7.dp, vertical = 2.dp)
                    .clearAndSetSemantics { },
            )
        }
    }
}

private fun usPortraitTile(cell: Dp): Dp = cell * PixelAvatar.side + (cell + 3.dp) * 2

/** "Edit avatar": only ever under the user's own portrait. 44 dp tall. */
@Composable
private fun EditAvatarLink(onClick: () -> Unit) {
    Row(
        Modifier
            .heightIn(min = 44.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { testTag = "us.editAvatar" },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Brush, contentDescription = null, tint = PleadColor.burgundy, modifier = Modifier.size(15.dp))   // paintbrush.pointed
        Text("Edit avatar", style = PleadType.metadataMedium, color = PleadColor.burgundy)
    }
}

// MARK: - Pair

@Composable
private fun PairCard(store: CaseStore, onEditAvatar: (() -> Unit)?) {
    val cell = 5.dp
    val me = store.me?.displayName ?: "You"
    val partner = store.partner?.displayName ?: "Partner"
    val accessibilitySize = LocalDensity.current.fontScale >= 1.6f
    Column(
        Modifier.awCard(padding = PleadSpacing.xl).semantics { isTraversalGroup = true },
        verticalArrangement = Arrangement.spacedBy(PleadSpacing.l),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Reading order: title → pairing → portraits (Swift `accessibilitySortPriority`).
        Column(
            Modifier.fillMaxWidth().semantics { traversalIndex = 1f },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(PleadSpacing.s), verticalAlignment = Alignment.Top) {
                Person(Modifier.weight(1f), store.me?.avatar ?: Avatar.default, me, isYou = true, cell, accessibilitySize, onEditAvatar)
                Box(Modifier.height(usPortraitTile(cell)), contentAlignment = Alignment.Center) {
                    // Centred on the portraits, not on the names.
                    Icon(Icons.Filled.Favorite, contentDescription = null, tint = PleadColor.blush, modifier = Modifier.size(20.dp))
                }
                Person(Modifier.weight(1f), store.partner?.avatar ?: Avatar.default, partner, isYou = false, cell, accessibilitySize, onEditAvatar)
            }
            // Accessibility sizes: the control gets its own full-width row under the portraits.
            if (accessibilitySize && onEditAvatar != null) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { EditAvatarLink(onEditAvatar) }
            }
        }
        Column(
            Modifier.fillMaxWidth().semantics { traversalIndex = -1f },
            verticalArrangement = Arrangement.spacedBy(PleadSpacing.xs),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "$me & $partner",
                style = PleadType.displayL,
                color = PleadColor.mahogany,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading(); testTag = "us.title" },
            )
            UsPairing.line(store.couple)?.let { line ->
                Text(line, style = PleadType.metadata, color = PleadColor.subtleText, textAlign = TextAlign.Center, modifier = Modifier.semantics { testTag = "us.pairing" })
            }
        }
    }
}

@Composable
private fun Person(
    modifier: Modifier,
    avatar: Avatar,
    name: String,
    isYou: Boolean,
    cell: Dp,
    accessibilitySize: Boolean,
    onEditAvatar: (() -> Unit)?,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(PleadSpacing.xs)) {
        Box(Modifier.padding(bottom = PleadSpacing.s)) { UsPortrait(avatar, name, isYou, cell) }
        // The portrait carries the name.
        Text(
            name,
            style = PleadType.bodyStrong,
            color = PleadColor.cocoa,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clearAndSetSemantics { },
        )
        if (!accessibilitySize) {
            if (isYou) {
                if (onEditAvatar != null) EditAvatarLink(onEditAvatar) else Spacer(Modifier.height(44.dp))
            } else {
                Box(Modifier.heightIn(min = 44.dp).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
                    Text("Partner", style = PleadType.metadata, color = PleadColor.subtleText)
                }
            }
        }
    }
}

// MARK: - Invite (no partner yet)

@Composable
private fun InviteCard(store: CaseStore, router: AppRouter, onEditAvatar: (() -> Unit)?) {
    val me = store.me?.displayName ?: "You"
    Column(Modifier.awCard(padding = PleadSpacing.xl), verticalArrangement = Arrangement.spacedBy(PleadSpacing.l)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
            Text(
                "Court needs two",
                style = PleadType.displayL,
                color = PleadColor.mahogany,
                modifier = Modifier.semantics { heading(); testTag = "us.invite.title" },
            )
            Text(
                "Invite your partner to Plead. Once they join, your pairing date and record live here.",
                style = PleadType.body,
                color = PleadColor.subtleText,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(PleadSpacing.m), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.padding(bottom = PleadSpacing.xs)) { UsPortrait(store.me?.avatar ?: Avatar.default, me, isYou = true, cell = 4.dp) }
            Column {
                Text(me, style = PleadType.bodyStrong, color = PleadColor.cocoa, modifier = Modifier.clearAndSetSemantics { })
                if (onEditAvatar != null) EditAvatarLink(onEditAvatar)
            }
            Spacer(Modifier.weight(1f))
        }
        store.couple?.let { couple ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(PleadColor.parchment.copy(alpha = 0.6f), RoundedCornerShape(PleadRadius.tile))
                    .padding(horizontal = PleadSpacing.m, vertical = PleadSpacing.s)
                    .clearAndSetSemantics { contentDescription = "Your invite code: ${couple.inviteCode.toList().joinToString(" ")}" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LegalLabel("Your invite code", color = PleadColor.walnut, size = 11f)
                Spacer(Modifier.weight(1f))
                SelectionContainer {
                    Text(
                        couple.inviteCode,
                        style = PleadType.ui(17f, FontWeight.ExtraBold, TextStyleKind.headline).copy(fontFamily = FontFamily.Monospace, letterSpacing = 2.sp),
                        color = PleadColor.cocoa,
                    )
                }
            }
        }
        PrimaryButton(
            "Invite your partner",
            systemImage = "paperplane.fill",
            modifier = Modifier.semantics {
                stateDescription = "Share your invite link or enter your partner's code"
                testTag = "us.invite"
            },
        ) { router.sheet = AppSheet.invite }
    }
}

// MARK: - Record

@Composable
private fun RecordCard(store: CaseStore, record: UsRecord, paired: Boolean) {
    Column(Modifier.awCard(), verticalArrangement = Arrangement.spacedBy(PleadSpacing.m)) {
        Text(record.lead, style = PleadType.titleL, color = PleadColor.cocoa, modifier = Modifier.semantics { testTag = "us.record.lead" })
        if (paired && record.heard > 0) Tally(store, record)
        Text(
            PleadCopy.principle,
            style = PleadType.display(15f, FontWeight.Normal, italic = true, relativeTo = TextStyleKind.subheadline),
            color = PleadColor.subtleText,
        )
    }
}

@Composable
private fun Tally(store: CaseStore, record: UsRecord) {
    val me = store.me?.displayName ?: "You"
    val partner = store.partner?.displayName ?: "Partner"
    Box(
        Modifier.clearAndSetSemantics {
            contentDescription = "$me ${record.mine}, ties ${record.ties}, $partner ${record.partners}"
            testTag = "us.record.tally"
        },
    ) {
        ViewThatFitsHorizontally(
            first = {
                Row(horizontalArrangement = Arrangement.spacedBy(PleadSpacing.s), verticalAlignment = Alignment.CenterVertically) {
                    TallyEntry(store.me?.avatar, "$me ${record.mine}")
                    Dot()
                    TallyEntry(null, "Ties ${record.ties}")
                    Dot()
                    TallyEntry(store.partner?.avatar, "$partner ${record.partners}")
                }
            },
            second = {
                Column(verticalArrangement = Arrangement.spacedBy(PleadSpacing.xs)) {
                    TallyEntry(store.me?.avatar, "$me ${record.mine}")
                    TallyEntry(null, "Ties ${record.ties}")
                    TallyEntry(store.partner?.avatar, "$partner ${record.partners}")
                }
            },
        )
    }
}

@Composable
private fun Dot() {
    Text("·", style = PleadType.bodyMedium, color = PleadColor.subtleText)
}

@Composable
private fun TallyEntry(avatar: Avatar?, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (avatar != null) {
            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                PixelAvatarView(avatar, size = (PixelAvatar.side * 2).dp)   // cell 2
            }
        } else {
            Box(Modifier.width(24.dp).height(32.dp), contentAlignment = Alignment.Center) {
                ScalesMark(size = 18.dp, color = PleadColor.walnut)
            }
        }
        Text(text, style = PleadType.bodyMedium.monospacedDigit(), color = PleadColor.cocoa, softWrap = false)
    }
}

/** SwiftUI `ViewThatFits(in: .horizontal)` with two candidates: [first] if its natural width fits, else [second]. */
@Composable
private fun ViewThatFitsHorizontally(first: @Composable () -> Unit, second: @Composable () -> Unit) {
    SubcomposeLayout { constraints ->
        val loose = constraints.copy(minWidth = 0)
        val probe = subcompose("probe", first).map { it.measure(Constraints(maxHeight = loose.maxHeight)) }
        val fits = probe.maxOfOrNull { it.width }?.let { it <= loose.maxWidth } ?: true
        val placeables = subcompose(if (fits) "first" else "second", if (fits) first else second).map { it.measure(loose) }
        val w = placeables.maxOfOrNull { it.width } ?: 0
        val h = placeables.maxOfOrNull { it.height } ?: 0
        layout(w, h) { placeables.forEach { it.place(0, 0) } }
    }
}

// MARK: - Presiding judge

@Composable
private fun JudgeCard(store: CaseStore, judgeSprite: (@Composable (persona: JudgePersona, cell: Dp) -> Unit)?) {
    val presiding = UsJudges.presiding(store.couple)
    val list = UsJudges.ordered(presiding)
    Column(Modifier.awCard(), verticalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
        Text("Presiding judge", style = PleadType.titleM, color = PleadColor.cocoa, modifier = Modifier.semantics { heading() })
        Column {
            list.forEachIndexed { i, persona ->
                JudgeRow(persona, selected = persona == presiding, available = UsJudges.isAvailable(persona), judgeSprite)
                if (i != list.lastIndex) HorizontalDivider(thickness = 0.5.dp, color = PleadColor.separator)
            }
        }
    }
}

@Composable
private fun JudgeRow(
    persona: JudgePersona,
    selected: Boolean,
    available: Boolean,
    judgeSprite: (@Composable (persona: JudgePersona, cell: Dp) -> Unit)?,
) {
    val value = listOfNotNull(persona.blurb, if (selected) "Presiding" else null, if (available) null else "Coming soon").joinToString(", ")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(vertical = PleadSpacing.s + 2.dp)
            .clearAndSetSemantics {
                contentDescription = persona.displayName
                stateDescription = value
                this.selected = selected
                testTag = "us.judge.${persona.rawValue}"
            },
        horizontalArrangement = Arrangement.spacedBy(PleadSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        JudgeTile(persona, dimmed = !available, judgeSprite)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(persona.displayName, style = PleadType.titleM, color = if (available) PleadColor.cocoa else PleadColor.subtleText)
            Text(persona.blurb, style = PleadType.metadata, color = PleadColor.subtleText)
            if (!available) {
                Chip("Coming soon", foreground = PleadColor.walnut, background = PleadColor.parchment, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (selected) {
            // Icon and text: the selected state is never colour-only.
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = PleadColor.burgundy, modifier = Modifier.size(22.dp))
                Text("Presiding", style = PleadType.caption, color = PleadColor.burgundy)
            }
        }
    }
}

/** The courtroom's real pixel judge (`JudgeSprite`, persona-tinted) at cell 3, framed like the portraits. */
@Composable
private fun JudgeTile(persona: JudgePersona, dimmed: Boolean, judgeSprite: (@Composable (persona: JudgePersona, cell: Dp) -> Unit)?) {
    val cell = 3.dp
    val side = cell * UsJudgeSprite.columns + cell * 2
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .size(side)
            .saturation(if (dimmed) 0.25f else 1f)
            .alpha(if (dimmed) 0.6f else 1f)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(PleadColor.parchment, Color(hex = 0xEAD6C4))), shape)
            .border(1.dp, PleadColor.walnut.copy(alpha = 0.28f), shape)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.BottomCenter,
    ) {
        if (judgeSprite != null) {
            // Shoulders on the tile's bottom edge.
            Box(Modifier.offset(y = cell * 2)) { judgeSprite(persona, cell) }
        }
    }
}

val JudgePersona.blurb: String
    get() = when (this) {
        JudgePersona.wigsworth -> "Pompous, dry, fond of \"the court notes\"."
        JudgePersona.blunt -> "Short sentences. Cuts through nonsense."
        JudgePersona.sunny -> "Warm mediator. Still makes a clear ruling."
        JudgePersona.chaos -> "Unpredictable, but bound by the evidence."
    }
