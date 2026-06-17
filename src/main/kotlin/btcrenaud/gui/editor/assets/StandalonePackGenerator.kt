package btcrenaud.gui.editor.assets

import btcrenaud.gui.editor.assets.BitmapChar
import btcrenaud.gui.resourcepack.adapters.StandaloneRPProvider
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.typewritermc.engine.paper.logger
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

/**
 * Generates the resource pack ZIP from on-disk assets.
 *
 * Instead of regenerating PNG textures every time, this class reuses
 * assets already written to disk by [FontGenerator] and [MagicDigitFontGenerator].
 * Only the pack.mcmeta and font provider JSON are generated fresh each time.
 *
 * ZIP structure follows Minecraft resource pack conventions:
 * ```
 * pack.mcmeta
 * pack.png (optional)
 * assets/minecraft/font/default.json   <- font provider definitions
 * gui/font/space_1.png ...            <- space character textures
 * gui/font/neg_1.png ...              <- negative space textures
 * assets/minecraft/textures/gui/...    <- generated UI textures
 * ```
 */
object StandalonePackGenerator {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    /** File extensions allowed in the resource pack (filters out .txt, .bak, .DS_Store, etc.) */
    private val ALLOWED_EXTENSIONS = setOf(".png", ".json", ".mcmeta")

    /**
     * Generates the resource pack ZIP from existing disk assets.
     *
     * @param assetsDir    Root assets directory (containing font/ and generated/ subdirs).
     * @param outputZip    Target ZIP file path.
     * @return true if the ZIP was generated successfully.
     */
    fun generatePack(assetsDir: Path, outputZip: Path): Boolean {
        return runCatching {
            // Ensure the default asset directory exists with minimum required files
            ensureDefaultAssets(assetsDir)

            // Ensure Magic Digit fonts are on disk first (skips existing)
            MagicDigitFontGenerator.generateAll(assetsDir)

            ZipOutputStream(Files.newOutputStream(outputZip).buffered()).use { zip ->
                // pack.mcmeta
                writePackMcmeta(zip)
                // pack.png (optional icon, if present)
                writeOptionalFile(zip, assetsDir.resolve("pack.png"), "pack.png")
                // Font provider JSON (assets/minecraft/font/default.json)
                writeFontProviderJson(zip, assetsDir)
                // Font texture assets (space/neg PNGs) -> gui/font/ in the ZIP
                writeDiskAssets(zip, assetsDir.resolve("font"), "gui/font/")
                // Generated textures from disk -> assets/minecraft/textures/gui/
                writeDiskAssets(zip, assetsDir.resolve("generated"), "assets/minecraft/textures/gui/")
                // Custom textures from the custom/ directory -> assets/minecraft/textures/gui/custom/
                writeDiskAssets(zip, assetsDir.resolve("custom"), "assets/minecraft/textures/gui/custom/")
            }

            logger.info("[GUI-Editor] Resource pack generated: $outputZip")
            true
        }.onFailure {
            logger.warning("[GUI-Editor] Failed to generate resource pack: ${it.message}")
        }.getOrDefault(false)
    }

    /**
     * Ensures the default asset directory contains the minimum required files
     * for a functional GUI resource pack. Creates placeholder PNG textures for
     * standard GUI elements (button backgrounds, panel borders, etc.) if missing.
     */
    fun ensureDefaultAssets(assetsDir: Path) {
        val generatedDir = assetsDir.resolve("generated")
        Files.createDirectories(generatedDir)
        val fontDir = assetsDir.resolve("font")
        Files.createDirectories(fontDir)
        val customDir = assetsDir.resolve("custom")
        Files.createDirectories(customDir)

        // Generate default GUI button textures if not present
        val defaultTextures = mapOf(
            "button_default" to createDefaultButtonPng(176, 18),
            "button_hover" to createHoverButtonPng(176, 18),
            "panel_background" to createPanelBackgroundPng(176, 96),
            "slot_highlight" to createSlotHighlightPng(18, 18),
            "scroll_up" to createScrollArrowPng(18, 18, up = true),
            "scroll_down" to createScrollArrowPng(18, 18, up = false),
            "tilemap_grid" to createTilemapGridPng(176, 90),
            "padding_1" to createSpacePng(1, 8),
            "padding_8" to createSpacePng(8, 8),
        )

        for ((name, pngBytes) in defaultTextures) {
            val file = generatedDir.resolve("$name.png")
            if (!Files.isRegularFile(file)) {
                Files.write(file, pngBytes)
                logger.info("[GUI-Editor] Generated default texture: $name.png")
            }
        }

        // Generate glyph_widths.json if missing
        val widthsFile = assetsDir.resolve("glyph_widths.json")
        if (!Files.isRegularFile(widthsFile)) {
            val fontGenerator = FontGenerator()
            val widths = fontGenerator.generateDefaultGlyphWidths()
            val widthsJson = gson.toJson(widths.mapKeys { it.key.toString() })
            Files.writeString(widthsFile, widthsJson)
            logger.info("[GUI-Editor] Generated default glyph_widths.json")
        }
    }

    private fun writePackMcmeta(zip: ZipOutputStream) {
        val mcmeta = JsonObject().apply {
            val pack = JsonObject().apply {
                addProperty("pack_format", StandaloneRPProvider.PACK_FORMAT)
                addProperty("description", "BORNTOCRAFT GUI Resource Pack")
            }
            add("pack", pack)
        }
        zip.putNextEntry(ZipEntry("pack.mcmeta"))
        zip.write(gson.toJson(mcmeta).toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    /**
     * Writes the font provider JSON that Minecraft uses to map Unicode codepoints
     * to texture files. This is essential — without it, the resource pack is unusable.
     *
     * The JSON is placed at `assets/minecraft/font/default.json` in the ZIP,
     * which is the standard Minecraft font override location.
     */
    private fun writeFontProviderJson(zip: ZipOutputStream, assetsDir: Path) {
        // Merge Magic Digit font providers with any user-defined providers
        val magicDigitJson = MagicDigitFontGenerator.generateFontJson()
        val providers = magicDigitJson.getAsJsonArray("providers") ?: JsonArray()

        // Add any custom font providers from disk
        val customFontDir = assetsDir.resolve("font")
        if (Files.isDirectory(customFontDir)) {
            Files.list(customFontDir).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".json") && it.fileName.toString() != "default.json" }
                    .forEach { file ->
                        runCatching {
                            val customProvider = gson.fromJson(Files.newBufferedReader(file), JsonObject::class.java)
                            if (customProvider.has("providers")) {
                                customProvider.getAsJsonArray("providers").forEach { providers.add(it) }
                            } else {
                                providers.add(customProvider)
                            }
                        }.onFailure {
                            logger.warning("[GUI-Editor] Failed to read custom font provider ${file.fileName}: ${it.message}")
                        }
                    }
            }
        }

        val fontJson = JsonObject().apply {
            add("providers", providers)
        }

        zip.putNextEntry(ZipEntry("assets/minecraft/font/default.json"))
        zip.write(gson.toJson(fontJson).toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    /**
     * Writes all files from a disk directory into the ZIP at the given zip prefix.
     * Recurses into subdirectories. Only includes files with allowed extensions.
     */
    private fun writeDiskAssets(
        zip: ZipOutputStream,
        dir: Path,
        zipPrefix: String = ""
    ) {
        if (!Files.isDirectory(dir)) return

        Files.walk(dir).use { stream ->
            stream.filter { Files.isRegularFile(it) && isAllowedFile(it) }.forEach { file ->
                val relativePath = dir.relativize(file).toString().replace('\\', '/')
                val entryPath = if (zipPrefix.isNotEmpty()) "$zipPrefix$relativePath" else relativePath
                zip.putNextEntry(ZipEntry(entryPath))
                Files.copy(file, zip)
                zip.closeEntry()
            }
        }
    }

    /** Checks if a file has an allowed extension for the resource pack. */
    private fun isAllowedFile(path: Path): Boolean {
        val name = path.fileName.toString().lowercase()
        return ALLOWED_EXTENSIONS.any { name.endsWith(it) }
    }

    /**
     * Writes a single optional file from disk into the ZIP.
     */
    private fun writeOptionalFile(
        zip: ZipOutputStream,
        source: Path,
        zipPath: String
    ) {
        if (Files.isRegularFile(source)) {
            zip.putNextEntry(ZipEntry(zipPath))
            Files.copy(source, zip)
            zip.closeEntry()
        }
    }

    // ─── Default Texture Generators ─────────────────────────────────────────

    /** Creates a default GUI button: dark gray background with subtle border. */
    private fun createDefaultButtonPng(width: Int, height: Int): ByteArray {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        // Fill background
        g.color = java.awt.Color(80, 80, 80, 200)
        g.fillRect(0, 0, width, height)
        // Draw border
        g.color = java.awt.Color(60, 60, 60, 255)
        g.drawRect(0, 0, width - 1, height - 1)
        // Top highlight
        g.color = java.awt.Color(120, 120, 120, 255)
        g.drawLine(0, 0, width - 1, 0)
        g.dispose()
        return toPngBytes(img)
    }

    /** Creates a hover-state button: lighter background. */
    private fun createHoverButtonPng(width: Int, height: Int): ByteArray {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = java.awt.Color(100, 100, 100, 220)
        g.fillRect(0, 0, width, height)
        g.color = java.awt.Color(140, 140, 140, 255)
        g.drawRect(0, 0, width - 1, height - 1)
        g.dispose()
        return toPngBytes(img)
    }

    /** Creates a panel background: dark semi-transparent. */
    private fun createPanelBackgroundPng(width: Int, height: Int): ByteArray {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = java.awt.Color(20, 20, 25, 230)
        g.fillRect(0, 0, width, height)
        g.color = java.awt.Color(50, 50, 55, 255)
        g.drawRect(0, 0, width - 1, height - 1)
        g.dispose()
        return toPngBytes(img)
    }

    /** Creates a slot highlight: bright outline. */
    private fun createSlotHighlightPng(width: Int, height: Int): ByteArray {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = java.awt.Color(79, 195, 247, 180)
        g.drawRect(0, 0, width - 1, height - 1)
        g.dispose()
        return toPngBytes(img)
    }

    /** Creates a scroll arrow indicator. */
    private fun createScrollArrowPng(width: Int, height: Int, up: Boolean): ByteArray {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = java.awt.Color(200, 200, 200, 220)
        val cx = width / 2
        val cy = height / 2
        if (up) {
            // Up arrow
            g.fillPolygon(intArrayOf(cx, cx - 5, cx + 5), intArrayOf(cy - 4, cy + 4, cy + 4), 3)
        } else {
            // Down arrow
            g.fillPolygon(intArrayOf(cx, cx - 5, cx + 5), intArrayOf(cy + 4, cy - 4, cy - 4), 3)
        }
        g.dispose()
        return toPngBytes(img)
    }

    /** Creates a tilemap grid background. */
    private fun createTilemapGridPng(width: Int, height: Int): ByteArray {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = java.awt.Color(30, 30, 35, 255)
        g.fillRect(0, 0, width, height)
        // Draw 18px grid lines
        g.color = java.awt.Color(60, 60, 65, 255)
        for (x in 0..width step 18) {
            g.drawLine(x, 0, x, height)
        }
        for (y in 0..height step 18) {
            g.drawLine(0, y, width, y)
        }
        g.dispose()
        return toPngBytes(img)
    }

    /** Creates a transparent space character PNG. */
    private fun createSpacePng(width: Int, height: Int): ByteArray {
        val img = BufferedImage(maxOf(width, 1), height, BufferedImage.TYPE_INT_ARGB)
        return toPngBytes(img)
    }

    private fun toPngBytes(image: BufferedImage): ByteArray {
        ByteArrayOutputStream().use { out ->
            ImageIO.write(image, "PNG", out)
            return out.toByteArray()
        }
    }
}
