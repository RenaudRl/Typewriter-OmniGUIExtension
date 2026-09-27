package btcrenaud.gui.editor.assets

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import net.kyori.adventure.text.Component

/**
 * Magic Digit: spacing characters that move a title's text cursor by an exact number of pixels.
 *
 * A single `space` provider in the font [GuiFonts.SPACE] gives each character its advance,
 * negative included:
 *   - positive spaces: U+E000 + (N-1) advances by N px, N from 1 to 256;
 *   - negative spaces: U+F000 + (N-1) moves back by N px, N from 1 to 128.
 *
 * Why not bitmaps: the client derives a bitmap glyph's advance from its last non-transparent column
 * (`BitmapProvider$Definition.getActualGlyphWidth`, read in the 26.3 client). A transparent image
 * therefore advances by 1 px whatever its width, and none moves back. That was the previous
 * implementation: every `<shift:N>` was worth +1 px (proven in game on 2026-09-27).
 */
object MagicDigitFontGenerator {

    /** Definition of the font [GuiFonts.SPACE]: one provider, no texture. */
    fun generateFontJson(): JsonObject {
        val advances = JsonObject()
        for (width in 1..BitmapChar.POSITIVE_SPACE_COUNT) {
            advances.addProperty(codepointString(BitmapChar.POSITIVE_SPACE_START + width - 1), width)
        }
        for (width in 1..BitmapChar.NEGATIVE_SPACE_COUNT) {
            advances.addProperty(codepointString(BitmapChar.NEGATIVE_SPACE_START + width - 1), -width)
        }
        val provider = JsonObject().apply {
            addProperty("type", "space")
            add("advances", advances)
        }
        return JsonObject().apply { add("providers", JsonArray().apply { add(provider) }) }
    }

    /**
     * Characters moving the cursor by [width] pixels, split into chunks when [width] exceeds the
     * largest available advance. Empty for 0.
     */
    fun spaceChar(width: Int): String = buildString {
        var remaining = width
        while (remaining > 0) {
            val chunk = minOf(remaining, BitmapChar.POSITIVE_SPACE_COUNT)
            append(codepointString(BitmapChar.POSITIVE_SPACE_START + chunk - 1))
            remaining -= chunk
        }
        while (remaining < 0) {
            val chunk = minOf(-remaining, BitmapChar.NEGATIVE_SPACE_COUNT)
            append(codepointString(BitmapChar.NEGATIVE_SPACE_START + chunk - 1))
            remaining += chunk
        }
    }

    /** Component moving the cursor by [width] pixels, in the font [GuiFonts.SPACE]. */
    fun spaceComponent(width: Int): Component = Component.text(spaceChar(width)).font(GuiFonts.SPACE)

    private fun codepointString(codepoint: Int): String = String(Character.toChars(codepoint))
}
