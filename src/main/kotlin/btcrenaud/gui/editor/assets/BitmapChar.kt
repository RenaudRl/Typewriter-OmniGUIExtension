package btcrenaud.gui.editor.assets

/**
 * A single bitmap font character for Minecraft's bitmap font provider.
 *
 * Each character maps to a PNG texture with a specific width and Unicode codepoint.
 * Negative widths are used for back-spacing (negative space), enabling pixel-perfect
 * text positioning without external tag resolvers.
 */
data class BitmapChar(
    /** Unicode codepoint assigned to this character (in the PUA U+E000-U+F8FF range). */
    val codepoint: Int,
    /** Width in pixels. Positive = advance forward, negative = backspace. */
    val width: Int,
    /** Display name for debugging. */
    val label: String
) {
    /** The Unicode character for this codepoint. */
    val char: Char get() = codepoint.toChar()

    /** Whether this is a negative-space character (moves cursor backwards). */
    val isNegative: Boolean get() = width < 0

    companion object {
        /** Start of the Private Use Area for positive space characters (1-256px). */
        const val POSITIVE_SPACE_START = 0xE000

        /** Start of the Private Use Area for negative space characters (-1 to -128px). */
        const val NEGATIVE_SPACE_START = 0xF000

        /** Total positive space characters generated (1-256px). */
        const val POSITIVE_SPACE_COUNT = 256

        /** Total negative space characters generated (-1 to -128px). */
        const val NEGATIVE_SPACE_COUNT = 128
    }
}
