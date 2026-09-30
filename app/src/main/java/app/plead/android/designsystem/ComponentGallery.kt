// Debug-only screen listing every design-system component, for side-by-side screenshots with iOS.
//
// Open it with `adb shell am start -S -n app.plead.android/.app.MainActivity --es AWSheet gallery` once the app
// shell shows `ComponentGallery()` when `DemoHarness.showsComponentGallery` is true (e.g. at the top of RootScreen:
// `if (DemoHarness.showsComponentGallery) { ComponentGallery(); return }`). Release builds never read the flag.
// Each section is also a `@Preview` in its own file.
package app.plead.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.plead.android.app.DemoHarness
import app.plead.android.app.LaunchArguments
import app.plead.android.models.Avatar
import app.plead.android.models.ExhibitLabel
import app.plead.android.models.ExhibitType
import app.plead.android.models.ObjectionRuling
import app.plead.android.models.Role
import app.plead.android.models.TrialPhase
import java.time.Instant

/** `AWSheet gallery` (debug builds): show [ComponentGallery] instead of the app. */
val DemoHarness.showsComponentGallery: Boolean
    get() = LaunchArguments.string("AWSheet") == "gallery"

@Composable
fun ComponentGallery(modifier: Modifier = Modifier) {
    LazyColumn(
        modifier.fillMaxSize().awBackground().statusBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item { GallerySection("Components") { ComponentsPreviewContent() } }
        item { GallerySection("Labels, chips, forms") { GalleryLabelsAndForms() } }
        item { GallerySection("Pixel avatars") { GalleryAvatars() } }
        item { GallerySection("Avatar badges") { GalleryAvatarBadges() } }
        item { GallerySection("Logo") { GalleryLogos() } }
        item { GallerySection("Case files") { CaseFilesPreviewContent() } }
    }
}

/** The gallery's sections, for snapshot tests. */
object ComponentGallerySections {
    val avatars: @Composable () -> Unit = { GalleryAvatars() }
    val badges: @Composable () -> Unit = { GalleryAvatarBadges() }
    val logos: @Composable () -> Unit = { GalleryLogos() }
    val labelsAndForms: @Composable () -> Unit = { GalleryLabelsAndForms() }
}

@Composable
private fun GallerySection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(title, modifier = Modifier.padding(horizontal = 16.dp))
        content()
    }
}

@Composable
private fun GalleryLabelsAndForms() {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LegalLabel("Exhibit A")
        LegalLabel("The court is deliberating", color = PleadColor.mahogany, size = 13f)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Needs you", systemImage = "exclamationmark.circle.fill")
            PhaseChip(TrialPhase.plaintiffOpening)
            PhaseChip(TrialPhase.crossExamination)
            PhaseChip("Custom", tint = PleadColor.burgundy)
            WeightDots(2)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(ExhibitType.photo, ExhibitType.screenshot, ExhibitType.text, ExhibitType.receipt).forEach { type ->
                Box(Modifier.size(64.dp).background(PleadColor.parchment, RoundedCornerShape(PleadRadius.tile))) {
                    ExhibitThumbnail(type = type, caption = "Caption", compact = true)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExhibitTile(
                label = ExhibitLabel("C"), type = ExhibitType.photo, caption = "", ruling = ObjectionRuling.sustained,
                occurredAt = Instant.parse("2026-09-12T20:14:00Z"), ownerRole = Role.plaintiff, modifier = Modifier.weight(1f),
            )
            ExhibitTile(
                label = ExhibitLabel("D"), type = ExhibitType.screenshot, caption = "Group chat", ruling = ObjectionRuling.overruled,
                ownerRole = Role.defendant, modifier = Modifier.weight(1f),
            )
        }
        Column(Modifier.awCourtFile()) { Text("awCourtFile: a parchment case file", style = PleadType.body, color = PleadColor.cocoa) }
        AWCard { Text("AWCard: a paper-white card", style = PleadType.body, color = PleadColor.cocoa) }
        Text("awInput", style = AWInputStyle.textStyle, modifier = Modifier.fillMaxWidth().awInput())
        var error by remember { mutableStateOf<String?>("That code has expired.") }
        InlineError(error)
        PrimaryButton("Toggle error", kind = AWButtonKind.plain) { error = if (error == null) "That code has expired." else null }
        PrimaryButton("Continue", systemImage = "arrow.right", kind = AWButtonKind.secondary, fullWidth = false) {}
        Box(Modifier.background(PleadColor.courtBackdrop, RoundedCornerShape(PleadRadius.card)).padding(16.dp)) {
            PrimaryButton("On dark", kind = AWButtonKind.onDark) {}
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            SummonsSeal(size = 96.dp, stamped = false)
            ClosedStamp(text = "Settled", color = PleadColor.walnut)
            Countdown(target = remember { Instant.now().plusSeconds(521) })
        }
        Box(Modifier.awBottomBar()) { PrimaryButton("awBottomBar") {} }
    }
}

@Composable
private fun GalleryAvatars() {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Avatar.Outfit.entries.forEachIndexed { o, outfit ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Avatar.Hairstyle.entries.forEachIndexed { h, hairstyle ->
                    PixelAvatarView(Avatar(skin = (h + o) % 6, hair = h, hairstyle = hairstyle, top = (h + 2 * o) % 8, outfit = outfit), size = 48.dp)
                }
            }
        }
        Row(Modifier.background(PleadColor.courtBackdrop).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PixelAvatarView(Avatar.default, size = 64.dp)
            PixelAvatarView(Avatar.default, size = 64.dp, outlined = false)
            PixelAvatarView(Avatar.default, size = 20.dp)
        }
    }
}

@Composable
private fun GalleryAvatarBadges() {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            AvatarBadge(Avatar.default, size = AvatarSize.s)
            AvatarBadge(Avatar.default, size = AvatarSize.m, isYou = true)
            AvatarBadge(Avatar(skin = 4, hair = 0, hairstyle = Avatar.Hairstyle.curly, top = 5, outfit = Avatar.Outfit.hoodie), size = AvatarSize.l)
            AvatarBadge(Avatar.default, size = AvatarSize.l, dimmed = true)
            AvatarPlaceholder(size = AvatarSize.l)
        }
        AvatarBadge(Avatar(skin = 1, hair = 3, hairstyle = Avatar.Hairstyle.ponytail, top = 3, outfit = Avatar.Outfit.dress), size = AvatarSize.xl, isYou = true)
        AvatarPair(me = Avatar.default, partner = null, size = AvatarSize.m)
        AvatarPair(me = Avatar.default, partner = Avatar(skin = 4, hair = 0, hairstyle = Avatar.Hairstyle.curly, top = 5, outfit = Avatar.Outfit.hoodie), markYou = true)
        RecordLine(mine = 3, partners = 4, ties = 1, me = Avatar.default, partner = Avatar(skin = 5, hair = 4, hairstyle = Avatar.Hairstyle.bun, top = 6, outfit = Avatar.Outfit.suit))
    }
}

@Composable
private fun GalleryLogos() {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
        PleadLogo(strapline = true, width = PleadLogoPlacement.width(402f).dp)
        PleadLogo(variant = PleadLogo.Variant.wordmarkOnly, width = 140.dp)
        PleadLogo(
            modifier = Modifier.background(PleadBrandColor.courtBurgundy, RoundedCornerShape(20.dp)).padding(24.dp),
            variant = PleadLogo.Variant.reversed,
            strapline = true,
            width = 200.dp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(0.0, 0.5, 1.0, 1.15).forEach { PleadLogo(width = 80.dp, heartScale = it) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            PixelGrid(
                rows = PleadPixelArt.heart,
                colors = mapOf('c' to PleadBrandColor.coral, 'h' to Color(hex = 0xF4B2B6), 'd' to Color(hex = 0xC95463)),
                modifier = Modifier.size(70.dp, 60.dp),
            )
            PixelGrid(
                rows = PleadPixelArt.scales,
                colors = mapOf('g' to PleadBrandColor.gold, 'l' to Color(hex = 0xF3C76A), 's' to Color(hex = 0x8F6236)),
                modifier = Modifier.size(90.dp, 60.dp),
            )
        }
        CourtTabHeader(title = null, showsWordmark = true) { Box(Modifier.width(1.dp)) }
    }
}

@Preview(name = "Component gallery", widthDp = 402, heightDp = 2400)
@Composable
private fun ComponentGalleryPreview() {
    ComponentGallery()
}
