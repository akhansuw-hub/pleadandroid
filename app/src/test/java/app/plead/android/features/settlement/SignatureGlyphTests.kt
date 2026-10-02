// The settlement signature mark (SignatureGlyph.kt), Android's own drawing for SF Symbol `signature`.
package app.plead.android.features.settlement

import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignatureGlyphTests {
    @Test fun sizedLikeTheSymbolAtItsPointSize() {
        // Entry button: `.font(.system(size: 15, weight: .semibold))`.
        val (w, h) = SignatureGlyph.size(15.dp)
        assertEquals(15f * 30f / 22f, w.value, 0.01f)
        assertEquals(15f, h.value, 0.01f)
        // Seal: 0.34 × size without a caption, 0.24 × with one.
        assertEquals(56f * 0.34f, SignatureGlyph.size(56.dp * 0.34f).second.value, 0.01f)
    }

    @Test fun vectorIsStrokedWithRoundCapsInItsViewport() {
        val v = SignatureGlyph.vector
        assertEquals(SignatureGlyph.viewportWidth, v.viewportWidth, 0f)
        assertEquals(SignatureGlyph.viewportHeight, v.viewportHeight, 0f)
        assertEquals(v.viewportWidth / v.viewportHeight, v.defaultWidth.value / v.defaultHeight.value, 0.001f)
        val paths = (0 until v.root.size).map { v.root[it] }.filterIsInstance<VectorPath>()
        assertEquals("x mark, signing line, signature", 3, paths.size)
        assertTrue(paths.all { it.stroke != null && it.fill == null && it.strokeLineWidth == SignatureGlyph.strokeWidth })
        assertTrue(v.root !is VectorPath && v.root is VectorGroup)
    }
}
