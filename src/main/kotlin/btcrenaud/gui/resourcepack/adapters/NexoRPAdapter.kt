package btcrenaud.gui.resourcepack.adapters

import btcrenaud.gui.resourcepack.AssetInfo
import btcrenaud.gui.resourcepack.GlyphInfo
import btcrenaud.gui.resourcepack.ResourcePackProvider
import com.typewritermc.engine.paper.logger
import org.bukkit.Bukkit

/**
 * Bridges Nexo's font/glyph system to the GUI editor.
 *
 * All access is via reflection. This adapter covers both Nexo
 * and its predecessor Oraxen (same package structure).
 *
 * The [detectedPlugin] field tracks which plugin was actually found
 * so that the correct class paths are used in data methods.
 */
class NexoRPAdapter : ResourcePackProvider {

    override val providerId = "nexo"
    override val displayName = "Nexo"
    override val priority = 50

    private var cachedAssets: List<AssetInfo>? = null
    private var lastScanTime = 0L

    /** Which plugin was actually detected: "Nexo", "Oraxen", or null. */
    private var detectedPlugin: String? = null

    /** Base Unicode codepoint for sequential glyph assignment (Private Use Area). */
    private val GLYPH_UNICODE_BASE = 0xE000

    override fun isAvailable(): Boolean {
        if (Bukkit.getPluginManager().getPlugin("Nexo") != null) {
            detectedPlugin = "Nexo"
            return true
        }
        if (Bukkit.getPluginManager().getPlugin("Oraxen") != null) {
            detectedPlugin = "Oraxen"
            return true
        }
        detectedPlugin = null
        return false
    }

    /**
     * Returns the class path candidates appropriate for the detected plugin.
     * Nexo uses com.nexomc.nexo.*, Oraxen uses io.th0rgal.oraxen.*
     */
    private fun getFontManagerCandidates(): List<String> = when (detectedPlugin) {
        "Nexo" -> listOf("com.nexomc.nexo.fonts.FontManager")
        "Oraxen" -> listOf("io.th0rgal.oraxen.font.FontManager")
        else -> listOf("com.nexomc.nexo.fonts.FontManager", "io.th0rgal.oraxen.font.FontManager")
    }

    private fun getItemsClassCandidates(): List<String> = when (detectedPlugin) {
        "Nexo" -> listOf("com.nexomc.nexo.items.NexoItems")
        "Oraxen" -> listOf("io.th0rgal.oraxen.items.OraxenItems")
        else -> listOf("com.nexomc.nexo.items.NexoItems", "io.th0rgal.oraxen.items.OraxenItems")
    }

    override fun getGlyphs(): Collection<GlyphInfo> {
        return runCatching {
            val fontManagerClass = resolveClass(*getFontManagerCandidates().toTypedArray())
                ?: return emptyList()

            val getInstanceMethod = try {
                fontManagerClass.getMethod("getInstance")
            } catch (_: NoSuchMethodException) {
                fontManagerClass.getMethod("get")
            }
            val instance = getInstanceMethod.invoke(null)

            val getGlyphsMethod = try {
                fontManagerClass.getMethod("getGlyphs")
            } catch (_: NoSuchMethodException) {
                try {
                    fontManagerClass.getMethod("getFonts")
                } catch (_: NoSuchMethodException) {
                    fontManagerClass.getMethod("getAll")
                }
            }

            @Suppress("UNCHECKED_CAST")
            val glyphs = getGlyphsMethod.invoke(instance) as? Collection<*> ?: return emptyList()

            glyphs.mapNotNull { glyph ->
                runCatching {
                    val glyphClass = glyph!!.javaClass
                    val id = glyphClass.getMethod("getName").invoke(glyph) as String
                    val name = id

                    val unicode = try {
                        val getCharMethod = glyphClass.getMethod("getCharacter")
                        getCharMethod.invoke(glyph) as? Char ?: '\uE000'
                    } catch (_: Exception) { '\uE000' }

                    val width = try {
                        glyphClass.getMethod("getWidth").invoke(glyph) as? Int ?: 16
                    } catch (_: Exception) { 16 }

                    val height = try {
                        glyphClass.getMethod("getHeight").invoke(glyph) as? Int ?: 16
                    } catch (_: Exception) { 16 }

                    GlyphInfo(id = id, name = name, path = "", unicode = unicode, width = width, height = height)
                }.getOrNull()
            }
        }.onFailure {
            logger.warning("[GUI-Editor] Nexo glyph enumeration failed: ${it.message}")
        }.getOrDefault(emptyList())
    }

    override fun getGlyphTexture(glyphId: String): ByteArray? = null

    override fun getGlyphWidths(): Map<Int, Int> = emptyMap()

    override fun getAssets(): Collection<AssetInfo> {
        val now = System.currentTimeMillis()
        if (cachedAssets != null && (now - lastScanTime) < 30_000) {
            return cachedAssets!!
        }
        lastScanTime = now

        cachedAssets = runCatching {
            val itemsClass = resolveClass(*getItemsClassCandidates().toTypedArray())
                ?: return emptyList()

            val getEntriesMethod = try {
                itemsClass.getMethod("getEntries")
            } catch (_: Exception) {
                itemsClass.getMethod("getItems")
            }

            @Suppress("UNCHECKED_CAST")
            val entries = getEntriesMethod.invoke(null) as? Collection<*> ?: return emptyList()

            entries.mapIndexedNotNull { index, entry ->
                runCatching {
                    val entryClass = entry!!.javaClass
                    val id = entryClass.getMethod("getItemID").invoke(entry) as String
                    val name = id
                    val path = try {
                        entryClass.getMethod("getTexturePath").invoke(entry) as? String ?: ""
                    } catch (_: Exception) { "" }

                    val unicode = "\\u%04X".format(GLYPH_UNICODE_BASE + index)
                    AssetInfo(id = "nexo:$id", name = name, path = path, unicode = unicode)
                }.getOrNull()
            }
        }.onFailure {
            logger.warning("[GUI-Editor] Nexo asset enumeration failed: ${it.message}")
        }.getOrDefault(emptyList())

        logger.info("[GUI-Editor] Nexo: ${cachedAssets!!.size} items loaded as assets")
        return cachedAssets!!
    }

    private fun resolveClass(vararg candidates: String): Class<*>? {
        for (name in candidates) {
            runCatching { return Class.forName(name) }
        }
        return null
    }
}
