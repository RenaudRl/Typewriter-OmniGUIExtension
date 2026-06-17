package btcrenaud.gui.resourcepack.adapters

import btcrenaud.gui.editor.assets.FontGenerator
import btcrenaud.gui.resourcepack.AssetInfo
import btcrenaud.gui.resourcepack.GlyphInfo
import btcrenaud.gui.resourcepack.ResourcePackProvider
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.typewritermc.engine.paper.logger
import java.nio.file.Files
import java.nio.file.Path

/**
 * Fallback provider that reads assets from the local filesystem.
 *
 * Scans [assetsDir] for PNG textures and a glyph_widths.json cache.
 * When no PNGs are found, delegates to [FontGenerator] to create
 * basic Minecraft-font glyphs at runtime.
 *
 * Expected directory layout:
 * ```
 * plugins/Typewriter/gui/assets/
 *   fonts/          <- TTF/OTF files for FontGenerator
 *   generated/      <- auto-generated PNG textures
 *   glyph_widths.json
 * ```
 */
class StandaloneRPProvider(
    private val assetsDir: Path
) : ResourcePackProvider {

    override val providerId = "standalone"
    override val displayName = "Standalone (Built-in)"
    override val priority = 10

    private val gson = Gson()
    private val fontGenerator = FontGenerator()
    private var cachedAssets: List<AssetInfo>? = null
    private var cachedGlyphWidths: Map<Int, Int>? = null
    private var lastAssetScanTime = 0L
    private var lastGlyphWidthsScanTime = 0L

    companion object {
        /** Centralized pack_format constant — delegates to [GuiConstants.PACK_FORMAT]. */
        const val PACK_FORMAT = btcrenaud.gui.GuiConstants.PACK_FORMAT
    }

    /** Base Unicode codepoint for sequential glyph assignment (Private Use Area). */
    private val GLYPH_UNICODE_BASE = 0xE000

    override fun isAvailable(): Boolean = true

    override fun getGlyphs(): Collection<GlyphInfo> {
        val generated = assetsDir.resolve("generated")
        if (!Files.isDirectory(generated)) return emptyList()

        return Files.list(generated).use { stream ->
            stream
                .filter { it.fileName.toString().endsWith(".png") }
                .toList()
                .sortedBy { it.fileName.toString() }
                .mapIndexed { index, path ->
                    val name = path.fileName.toString().removeSuffix(".png")
                    val unicode = (GLYPH_UNICODE_BASE + index).toChar()
                    GlyphInfo(
                        id = "standalone:$name",
                        name = name,
                        path = path.toString(),
                        unicode = unicode,
                        width = 16,
                        height = 16
                    )
                }
        }
    }

    override fun getGlyphTexture(glyphId: String): ByteArray? {
        val rawName = glyphId.substringAfter(":")
        val sanitized = rawName.let { name ->
            val withExt = if (name.endsWith(".png", ignoreCase = true)) name else "$name.png"
            withExt.map { if (it in " <>:\"/\\|?*") '_' else it }.joinToString("")
        }
        val file = assetsDir.resolve("generated").resolve(sanitized)
        return if (Files.isRegularFile(file)) Files.readAllBytes(file) else null
    }

    override fun getGlyphWidths(): Map<Int, Int> {
        val now = System.currentTimeMillis()
        if (cachedGlyphWidths != null && (now - lastGlyphWidthsScanTime) < 30_000) {
            return cachedGlyphWidths!!
        }
        lastGlyphWidthsScanTime = now

        val file = assetsDir.resolve("glyph_widths.json")
        if (Files.isRegularFile(file)) {
            cachedGlyphWidths = runCatching {
                val type = object : TypeToken<Map<String, Int>>() {}.type
                val raw: Map<String, Int> = gson.fromJson(Files.newBufferedReader(file), type)
                raw.mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to v } }.toMap()
            }.getOrDefault(emptyMap())
        }
        if (cachedGlyphWidths.isNullOrEmpty()) {
            cachedGlyphWidths = fontGenerator.generateDefaultGlyphWidths()
        }
        return cachedGlyphWidths!!
    }

    override fun getAssets(): Collection<AssetInfo> {
        val now = System.currentTimeMillis()
        if (cachedAssets != null && (now - lastAssetScanTime) < 30_000) {
            return cachedAssets!!
        }
        lastAssetScanTime = now

        val generated = assetsDir.resolve("generated")
        if (!Files.isDirectory(generated)) {
            cachedAssets = emptyList()
            return cachedAssets!!
        }

        cachedAssets = Files.list(generated).use { stream ->
            stream
                .filter { it.fileName.toString().endsWith(".png") }
                .toList()
                .sortedBy { it.fileName.toString() }
                .mapIndexed { index, path ->
                    val name = path.fileName.toString().removeSuffix(".png")
                    val unicode = "\\u%04X".format(GLYPH_UNICODE_BASE + index)
                    AssetInfo(
                        id = "standalone:$name",
                        name = name,
                        path = path.toString(),
                        unicode = unicode
                    )
                }
        }

        logger.info("[GUI-Editor] Loaded ${cachedAssets!!.size} standalone assets from $assetsDir")
        return cachedAssets!!
    }
}
