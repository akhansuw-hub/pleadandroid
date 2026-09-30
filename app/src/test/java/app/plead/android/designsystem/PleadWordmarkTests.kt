// PleadWordmark.swift subjects: OnboardingTests.welcomeLogoMatchesTheEndCardPlacement, plus the logo geometry
// the layered paywall opening depends on (asset aspect, heart mask).
package app.plead.android.designsystem

import androidx.compose.ui.geometry.Size
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PleadWordmarkTests {
    // OnboardingTests.swift
    @Test fun welcomeLogoMatchesTheEndCardPlacement() {
        // One shared placement: same width and top gap for the same safe area, capped on wide screens.
        assertTrue(abs(PleadLogoPlacement.width(402f) - 233.16f) < 0.01f)
        assertEquals(250f, PleadLogoPlacement.width(1024f))
        assertTrue(PleadLogoPlacement.top(778f) in 10f..28f)
        assertEquals(10f, PleadLogoPlacement.top(100f))
    }

    @Test fun assetsMatchTheMarkAspect() {
        for (variant in PleadLogo.Variant.entries) {
            val name = when (variant) {
                PleadLogo.Variant.primary -> "plead_wordmark"
                PleadLogo.Variant.wordmarkOnly -> "plead_wordmark_only"
                PleadLogo.Variant.reversed -> "plead_wordmark_reversed"
            }
            // PNG IHDR: width and height are the big-endian ints at bytes 16 and 20.
            val header = ByteBuffer.wrap(File("src/main/res/drawable-nodpi/$name.png").readBytes(), 16, 8)
            val width = header.int
            val height = header.int
            assertEquals(660, width)
            assertEquals(PleadLogo.markAspect(variant), height / width.toFloat(), 0.001f)
            assertEquals(3, PleadAssets.scale(name))
        }
    }

    @Test fun lockupHeight() {
        assertEquals(110f, PleadLogo.height(strapline = false, width = 220f), 0.001f)
        // mark − bottom margin + gap + strapline line box
        assertEquals(110f - 7.4f + 9.9f + 11f * 1.2f, PleadLogo.height(strapline = true, width = 220f), 0.001f)
        assertEquals(PleadLogo.height(strapline = true, width = 233.16f), PleadLogoPlacement.height(233.16f), 0.0001f)
        assertEquals(10f, PleadLogo.straplineSize(100f))
        assertEquals(41f, PleadLogo.clearSpace(220f), 0.0001f)
    }

    @Test fun heartMaskCoversEachRowOfTheHeart() {
        val mask = PleadLogo.heartMask(Size(220f, 110f))
        val bounds = mask.getBounds()
        // Padded by 1 pt: the heart's 35 × 30 frame at (94.67, 7.33) grows to 37 × 32.
        assertEquals(93.67f, bounds.left, 0.01f)
        assertEquals(6.33f, bounds.top, 0.01f)
        assertEquals(37f, bounds.width, 0.01f)
        assertEquals(32f, bounds.height, 0.01f)
    }

    @Test fun pixelArtGrids() {
        assertEquals(6, PleadPixelArt.heart.size)
        assertTrue(PleadPixelArt.heart.all { it.length == 7 })
        assertEquals(10, PleadPixelArt.scales.size)
        assertTrue(PleadPixelArt.scales.all { it.length == 15 })
        assertEquals("A Happier Kind Of Debate", capitalized(PleadLogo.straplineText))
        assertEquals("PleadWordmark", PleadLogo.assetName(PleadLogo.Variant.primary))
    }
}
