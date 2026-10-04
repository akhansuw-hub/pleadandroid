// Port of ArgueWin/Features/Onboarding/About/AboutOptionIcons.swift. Pixel-art option icons for "About you two"
// (amendment bd: "icons are pixel-art in the app's style (no emoji)"). Character grids drawn with `PixelGrid`
// (hard-edged cells), dark courtroom outline, the app's palette.
package app.plead.android.features.onboarding.about

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.plead.android.designsystem.Color as hex
import app.plead.android.designsystem.PixelGrid
import app.plead.android.designsystem.PleadBrandColor

enum class AboutOptionIcon {
    // Screen 2 · What do you bicker about most?
    broom, pizza, alarmClock, phone, wallet, thought,
    // Screen 5 · What would make those moments easier?
    heart, scales, document, smile;

    val rows: List<String>
        get() = when (this) {
            broom -> listOf(
                "..........oo",
                ".........oho",
                "........oho.",
                ".......oho..",
                "......oho...",
                ".....oho....",
                "...oooo.....",
                "..obbbbo....",
                ".oyYyYyyo...",
                ".oyYyYyYo...",
                "oyYyYyYyo...",
                "oyyyyyyyo...",
                ".ooooooo....",
            )
            pizza -> listOf(
                "oooooooooooo",
                "oCCCCCCCCCCo",
                "oCCCCCCCCCCo",
                ".oYYPYYYPYo.",
                ".oYPPYYYYYo.",
                "..oYYYYPYo..",
                "..oYYYPPYo..",
                "...oYPYYo...",
                "...oYYYYo...",
                "....oYYo....",
                "....oYYo....",
                ".....oo.....",
            )
            alarmClock -> listOf(
                ".oo......oo.",
                "oBBo....oBBo",
                "oBBoooooooBo",
                ".ooRRRRRRoo.",
                ".oRWWWWWWRo.",
                "oRWWWWkWWWRo",
                "oRWWWWkWWWRo",
                "oRWWWWkkkWRo",
                "oRWWWWWWWWRo",
                ".oRWWWWWWRo.",
                "..oRRRRRRo..",
                ".oo.oooo.oo.",
            )
            phone -> listOf(
                "..oooooooo..",
                "..okkkkkko..",
                "..oSSSSSSo..",
                "..oSsSSsSo..",
                "..oSSSSSSo..",
                "..oSsSSsSo..",
                "..oSSSSSSo..",
                "..oSsSSSSo..",
                "..oSSSSSSo..",
                "..okkkkkko..",
                "..okkWWkko..",
                "..oooooooo..",
            )
            wallet -> listOf(
                "....ooooooo.",
                "...oGGGGGGGo",
                "..oGGgGGGGGo",
                ".ooooooooooo",
                "oBBBBBBBBBBo",
                "obbbbbbbbbbo",
                "obbbbbbooooo",
                "obbbbbboYYYo",
                "obbbbbboYyYo",
                "obbbbbbooooo",
                "oBBBBBBBBBBo",
                "oooooooooooo",
            )
            thought -> listOf(
                "...oooooo...",
                ".ooWWWWWWoo.",
                "oWWWWWWWWWWo",
                "oWWWWWWWWWWo",
                "oWkkWkkWkkWo",
                "oWWWWWWWWWWo",
                ".oWWWWWWWWo.",
                "..ooWWWWoo..",
                "....oooo....",
                "...oWo......",
                "...oo.......",
                ".oWo........",
                ".oo.........",
            )
            heart -> listOf(
                ".oooo..oooo.",
                "occhcoocccco",
                "ochhccccccco",
                "occcccccccco",
                "occcccccccdo",
                ".occcccccdo.",
                "..occcccdo..",
                "...occcdo...",
                "....ocdo....",
                ".....oo.....",
            )
            scales -> listOf(
                ".....gg.....",
                ".ggggggggggg",
                ".g...gg...g.",
                "g.g..gg..g.g",
                "ggggggg.gggg",
                ".sss.gg.sss.",
                ".....gg.....",
                ".....gg.....",
                "...gggggg...",
                "..ssssssss..",
            )
            document -> listOf(
                "....oooo....",
                ".oooGGGGooo.",
                ".oBoGGGGoBo.",
                ".oBWWWWWWBo.",
                ".oBWkkkkWBo.",
                ".oBWWWWWWBo.",
                ".oBWkkkWWBo.",
                ".oBWWWWWWBo.",
                ".oBWkkkkWBo.",
                ".oBWWWWWRBo.",
                ".oBWRWWRWBo.",
                ".oBWWRRWWBo.",
                ".oBBBBBBBBo.",
                ".oooooooooo.",
            )
            smile -> listOf(
                "...oooooo...",
                "..oYYYYYYo..",
                ".oYYYYYYYYo.",
                "oYYkYYYYkYYo",
                "oYYkYYYYkYYo",
                "oYYYYYYYYYYo",
                "oYpYYYYYYpYo",
                "oYYkYYYYkYYo",
                ".oYYkkkkYYo.",
                "..oYYYYYYo..",
                "...oooooo...",
            )
        }

    /** Per-icon overrides where one letter means something else (the wallet's brown body, the scales' gold). */
    val palette: Map<Char, Color>
        get() {
            val p = basePalette.toMutableMap()
            when (this) {
                wallet -> {
                    p['B'] = hex(0x8A4B2A); p['b'] = hex(0xB8693A); p['Y'] = hex(0xE9B872); p['y'] = hex(0x3B2119)
                }
                scales -> {
                    p['g'] = hex(0xCA9858); p['s'] = hex(0x8A5A2E)
                }
                document -> {
                    p['B'] = hex(0xB8693A); p['G'] = hex(0xCA9858); p['W'] = hex(0xFFFDFC)
                    p['k'] = hex(0x9C8A7E); p['R'] = hex(0x3F8F5A)
                }
                heart -> p['h'] = hex(0xF7B7B9)
                smile -> p['Y'] = hex(0xF3C654)
                else -> Unit
            }
            return p
        }

    companion object {
        val basePalette: Map<Char, Color> = mapOf(
            'o' to hex(0x2A1310),
            // broom
            'h' to hex(0xC89A5E), 'b' to hex(0xB23A2E), 'y' to hex(0xF0C062), 'Y' to hex(0xC98A2E),
            // pizza
            'C' to hex(0xC97B3A), 'P' to hex(0xB23A2E),
            // clock
            'B' to hex(0xCA9858), 'R' to hex(0xC2453F), 'W' to hex(0xFFF6E8), 'k' to hex(0x3B2119),
            // phone
            'S' to hex(0x5DA9E9), 's' to hex(0xBFE0FA),
            // wallet
            'G' to hex(0x7FBF7F), 'g' to hex(0x4E9A5A),
            // heart
            'c' to PleadBrandColor.coral, 'd' to hex(0xB23A4A),
            // smile cheeks
            'p' to hex(0xF29A8E),
        )
    }
}

/** An option icon at `size` (hidden from TalkBack: the option's title says it). */
@Composable
fun AboutOptionIconView(icon: AboutOptionIcon, modifier: Modifier = Modifier, size: Dp = 28.dp) {
    PixelGrid(rows = icon.rows, colors = icon.palette, modifier = modifier.size(size))
}
