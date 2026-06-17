package btcrenaud.gui.editor.assets

import com.typewritermc.engine.paper.logger
import java.awt.Font
import java.awt.FontMetrics
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.max

/**
 * Generates bitmap glyph textures using Java AWT.
 *
 * Used as a fallback when no resource pack plugin is installed.
 * Supports loading custom TTF/OTF fonts from the assets/fonts/ directory
 * and generates PNG textures + glyph width maps for the web editor.
 */
class FontGenerator {

    private data class GlyphKey(val fontName: String, val char: Char, val size: Int)

    /** Maximum number of cached glyph textures before LRU eviction. */
    private val maxCacheSize = 500
    private val cache = object : LinkedHashMap<GlyphKey, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<GlyphKey, ByteArray>?): Boolean {
            return size > maxCacheSize
        }
    }

    /** Characters to pre-generate for the built-in ASCII set */
    private val defaultCharset = (' '..'~').toList() + listOf('Ç', 'ç', 'é', 'è', 'ê', 'ë', 'à', 'â', 'ä', 'ù', 'û', 'ü', 'î', 'ï', 'ô', 'ö')

    /**
     * Generates a PNG texture for a single character using the specified font.
     */
    fun generateGlyphTexture(char: Char, fontName: String = "Monospaced", size: Int = 16): ByteArray? {
        val key = GlyphKey(fontName, char, size)
        cache[key]?.let { return it }

        return runCatching {
            val font = loadFont(fontName, size) ?: Font("Monospaced", Font.PLAIN, size)
            val img = renderGlyph(char, font)
            val bytes = toPngBytes(img)
            cache[key] = bytes
            bytes
        }.onFailure {
            logger.warning("[GUI-Editor] Failed to generate glyph for '$char': ${it.message}")
        }.getOrNull()
    }

    /**
     * Generates a map of codepoint -> pixel width for the default ASCII range.
     */
    fun generateDefaultGlyphWidths(fontName: String = "Monospaced", size: Int = 16): Map<Int, Int> {
        val font = loadFont(fontName, size) ?: Font("Monospaced", Font.PLAIN, size)
        val metrics = createFontMetrics(font)

        return defaultCharset.associate { char ->
            char.code to metrics.charWidth(char)
        }
    }

    /**
     * Pre-generates PNGs for all default characters into [outputDir].
     * Returns the number of files written.
     */
    fun preGenerateAll(outputDir: Path, fontName: String = "Monospaced", size: Int = 16): Int {
        if (!Files.isDirectory(outputDir)) {
            Files.createDirectories(outputDir)
        }

        val font = loadFont(fontName, size) ?: Font("Monospaced", Font.PLAIN, size)
        var count = 0

        for (char in defaultCharset) {
            val img = renderGlyph(char, font)
            val file = outputDir.resolve("${char.code}.png")
            ImageIO.write(img, "PNG", file.toFile())
            count++
        }

        logger.info("[GUI-Editor] Pre-generated $count glyph textures in $outputDir")
        return count
    }

    // ─── Internal ──────────────────────────────────────────────────────────

    private fun renderGlyph(char: Char, font: Font): BufferedImage {
        val metrics = createFontMetrics(font)
        val width = max(metrics.charWidth(char), 1)
        val height = metrics.height

        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF)
        g.font = font
        g.color = java.awt.Color.WHITE
        g.drawString(char.toString(), 0, metrics.ascent)
        g.dispose()

        return image
    }

    private fun toPngBytes(image: BufferedImage): ByteArray {
        ByteArrayOutputStream().use { out ->
            ImageIO.write(image, "PNG", out)
            return out.toByteArray()
        }
    }

    private fun createFontMetrics(font: Font): FontMetrics {
        val dummy = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        return dummy.createGraphics().getFontMetrics(font)
    }

    /**
     * Tries to load a font by name from:
     * 1. Fonts directory on disk
     * 2. System-installed fonts
     * 3. Returns null if not found (logs warning, caller should handle fallback)
     */
    private fun loadFont(fontName: String, size: Int): Font? {
        val fontsDir = Path.of("plugins/Typewriter/gui/assets/fonts")
        if (Files.isDirectory(fontsDir)) {
            Files.list(fontsDir).use { stream ->
                stream
                    .filter { it.fileName.toString().let { n -> n.endsWith(".ttf") || n.endsWith(".otf") } }
                    .forEach { path ->
                        runCatching {
                            val loaded = Font.createFont(Font.TRUETYPE_FONT, path.toFile())
                            val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
                            ge.registerFont(loaded)
                        }
                    }
            }

            val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
            ge.allFonts.find { it.name.equals(fontName, ignoreCase = true) }
                ?.deriveFont(Font.PLAIN, size.toFloat())
                ?.let { return it }
        }

        val systemFont = Font(fontName, Font.PLAIN, size)
        return if (systemFont.family.equals(fontName, ignoreCase = true)) {
            systemFont
        } else {
            logger.warning("[GUI-Editor] Font '$fontName' not found, falling back to default")
            null
        }
    }
}
