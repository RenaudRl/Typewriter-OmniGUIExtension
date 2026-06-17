package btcrenaud.gui.editor.assets

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.typewritermc.engine.paper.logger
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * Generates the Magic Digit bitmap font for the GUI mode.
 *
 * Magic Digit is a technique that uses transparent PNG characters of specific
 * widths to achieve pixel-perfect cursor positioning in Minecraft inventory titles.
 * Each character is mapped to a Unicode codepoint in the Private Use Area (PUA).
 *
 * The font provides:
 *   - Positive space: 256 characters (1-256px advance)
 *   - Negative space: 128 characters (-1 to -128px advance)
 *
 * This allows any pixel offset to be expressed as a combination of these characters,
 * e.g. shifting right by 42px = U+E02A (42px positive space character).
 */
object MagicDigitFontGenerator {

    /** Height of all space characters in pixels (standard Minecraft font height). */
    private const val CHAR_HEIGHT = 8

    /** Ascent value for the bitmap font (pixels above baseline). */
    private const val ASCENT = 7

    /** Font namespace used in the resource pack. */
    const val FONT_NAMESPACE = "gui"

    /**
     * Generates all Magic Digit PNG textures and the font JSON definition.
     *
     * @param outputDir Directory where generated PNGs and font JSON are saved.
     */
    fun generateAll(outputDir: Path) {
        val fontDir = outputDir.resolve("font")
        Files.createDirectories(fontDir)

        // Generate positive space characters (1-256px) — skip if already on disk
        for (w in 1..BitmapChar.POSITIVE_SPACE_COUNT) {
            val codepoint = BitmapChar.POSITIVE_SPACE_START + w - 1
            val file = fontDir.resolve("space_$w.png")
            if (!Files.isRegularFile(file)) {
                val png = generateSpacePng(w)
                Files.write(file, png)
            }
        }
        logger.info("[GUI-Editor] Positive space chars ready (${BitmapChar.POSITIVE_SPACE_COUNT} total)")

        // Generate negative space characters (-1 to -128px) — skip if already on disk
        for (w in 1..BitmapChar.NEGATIVE_SPACE_COUNT) {
            val codepoint = BitmapChar.NEGATIVE_SPACE_START + w - 1
            val file = fontDir.resolve("neg_$w.png")
            if (!Files.isRegularFile(file)) {
                val png = generateSpacePng(1) // 1px PNG — negative advance is in font JSON
                Files.write(file, png)
            }
        }
        logger.info("[GUI-Editor] Negative space chars ready (${BitmapChar.NEGATIVE_SPACE_COUNT} total)")

        // Write font JSON
        val fontJson = generateFontJson()
        val fontJsonFile = fontDir.resolve("default.json")
        Files.writeString(fontJsonFile, fontJson.toString())
        logger.info("[GUI-Editor] Font JSON written to $fontJsonFile")
    }

    fun generateFontJson(): JsonObject {
        val root = JsonObject()
        val providers = JsonArray()

        // Positive space providers (one per distinct width)
        for (w in 1..BitmapChar.POSITIVE_SPACE_COUNT) {
            val codepoint = BitmapChar.POSITIVE_SPACE_START + w - 1
            val provider = JsonObject().apply {
                addProperty("type", "bitmap")
                addProperty("file", "$FONT_NAMESPACE:font/space_$w.png")
                addProperty("ascent", ASCENT)
                addProperty("height", CHAR_HEIGHT)
                add("chars", JsonArray().apply {
                    add(String(Character.toChars(codepoint)))
                })
            }
            providers.add(provider)
        }

        // Negative space providers
        for (w in 1..BitmapChar.NEGATIVE_SPACE_COUNT) {
            val codepoint = BitmapChar.NEGATIVE_SPACE_START + w - 1
            val provider = JsonObject().apply {
                addProperty("type", "bitmap")
                addProperty("file", "$FONT_NAMESPACE:font/neg_$w.png")
                addProperty("ascent", ASCENT)
                addProperty("height", CHAR_HEIGHT)
                add("chars", JsonArray().apply {
                    add(String(Character.toChars(codepoint)))
                })
            }
            providers.add(provider)
        }

        root.add("providers", providers)
        return root
    }

    /**
     * Returns the Magic Digit unicode character for a given pixel width.
     *
     * @param width Pixel width (positive = forward, negative = backward).
     * @return The Unicode string representing this width.
     */
    fun spaceChar(width: Int): String {
        return when {
            width > 0 && width <= BitmapChar.POSITIVE_SPACE_COUNT -> {
                String(Character.toChars(BitmapChar.POSITIVE_SPACE_START + width - 1))
            }
            width < 0 && -width <= BitmapChar.NEGATIVE_SPACE_COUNT -> {
                String(Character.toChars(BitmapChar.NEGATIVE_SPACE_START + (-width) - 1))
            }
            width == 0 -> ""
            else -> {
                buildString {
                    var remaining = width
                    while (remaining > 0) {
                        val chunk = minOf(remaining, BitmapChar.POSITIVE_SPACE_COUNT)
                        append(String(Character.toChars(BitmapChar.POSITIVE_SPACE_START + chunk - 1)))
                        remaining -= chunk
                    }
                    while (remaining < 0) {
                        val chunk = minOf(-remaining, BitmapChar.NEGATIVE_SPACE_COUNT)
                        append(String(Character.toChars(BitmapChar.NEGATIVE_SPACE_START + chunk - 1)))
                        remaining += chunk
                    }
                }
            }
        }
    }

    /**
     * Builds a MiniMessage-like title string using Magic Digit characters.
     * Replaces `<shift:N>` and `<image:ID>` tags with their equivalents.
     */
    fun buildStandaloneTitle(
        shifts: List<Int>,
        imageIds: List<String>,
        texts: List<String>
    ): String {
        val sb = StringBuilder()
        for (i in shifts.indices) {
            if (shifts[i] != 0) {
                sb.append(spaceChar(shifts[i]))
            }
            if (i < imageIds.size && imageIds[i].isNotEmpty()) {
                sb.append(imageIds[i]) // Unicode image char from RP
            }
            if (i < texts.size && texts[i].isNotEmpty()) {
                sb.append(texts[i])
            }
        }
        return sb.toString()
    }

    // ─── Internal ──────────────────────────────────────────────────────────

    private fun generateSpacePng(width: Int): ByteArray {
        val image = BufferedImage(maxOf(width, 1), CHAR_HEIGHT, BufferedImage.TYPE_INT_ARGB)
        ByteArrayOutputStream().use { out ->
            ImageIO.write(image, "PNG", out)
            return out.toByteArray()
        }
    }
}
