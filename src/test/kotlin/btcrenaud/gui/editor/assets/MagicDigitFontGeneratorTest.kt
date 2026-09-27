package btcrenaud.gui.editor.assets

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Magic Digit must give each character its exact advance, negative included.
 *
 * Regression guarded: the previous version generated transparent bitmaps, which the client
 * advances by 1 px whatever their width (proven in game on 2026-09-27).
 */
class MagicDigitFontGeneratorTest {

    private val providers = MagicDigitFontGenerator.generateFontJson().getAsJsonArray("providers")
    private val advances = providers[0].asJsonObject.getAsJsonObject("advances")

    private fun advanceOf(text: String): Int =
        text.codePoints().toArray().sumOf { advances.get(String(Character.toChars(it))).asInt }

    @Test
    fun `a single space provider, no texture`() {
        assertEquals(1, providers.size())
        assertEquals("space", providers[0].asJsonObject.get("type").asString)
    }

    @Test
    fun `positive and negative spaces carry their exact advance`() {
        assertEquals(40, advanceOf(MagicDigitFontGenerator.spaceChar(40)))
        assertEquals(-20, advanceOf(MagicDigitFontGenerator.spaceChar(-20)))
        assertEquals(300, advanceOf(MagicDigitFontGenerator.spaceChar(300)))
        assertEquals(-300, advanceOf(MagicDigitFontGenerator.spaceChar(-300)))
        assertEquals("", MagicDigitFontGenerator.spaceChar(0))
    }

    @Test
    fun `historical codepoints are kept`() {
        assertEquals(0xE027, MagicDigitFontGenerator.spaceChar(40).codePointAt(0))
        assertEquals(0xF013, MagicDigitFontGenerator.spaceChar(-20).codePointAt(0))
    }
}
