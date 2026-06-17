package btcrenaud.gui.resourcepack.adapters

import btcrenaud.gui.resourcepack.AssetInfo
import btcrenaud.gui.resourcepack.GlyphInfo
import btcrenaud.gui.resourcepack.ResourcePackProvider
import btcrenaud.gui.resourcepack.font.GuiTextFontCache
import com.typewritermc.engine.paper.logger
import org.bukkit.Bukkit
import java.nio.file.Files
import java.nio.file.Path

/**
 * Bridges CraftEngine's font system to the GUI editor.
 *
 * Uses reflection to access CraftEngine's internal APIs since
 * the extension has only compileOnly dependency on CraftEngine.
 *
 * This adapter targets the **official** CraftEngine API (v26.5.1+):
 * - [net.momirealms.craftengine.core.font.FontManager.loadedBitmapImages]
 * - [net.momirealms.craftengine.core.font.BitmapImage] (has file, height, ascent)
 * - [net.momirealms.craftengine.core.util.CharacterUtils.encodeCharsToUnicode]
 *
 * Glyph widths are served from [GuiTextFontCache] (populated externally,
 * e.g., by a resource-pack generation hook in CraftEngine).
 */
class CraftEngineRPAdapter : ResourcePackProvider {

    override val providerId = "craftengine"
    override val displayName = "CraftEngine"
    override val priority = 100

    private var cachedAssets: List<AssetInfo>? = null
    private var lastScanTime = 0L

    override fun isAvailable(): Boolean {
        return Bukkit.getPluginManager().getPlugin("CraftEngine") != null
    }

    // ─── Glyphs ───────────────────────────────────────────────────────────────

    override fun getGlyphs(): Collection<GlyphInfo> {
        return runCatching {
            val ceClass = Class.forName("net.momirealms.craftengine.core.plugin.CraftEngine")
            val instance = ceClass.getMethod("instance").invoke(null)
            val fontManager = ceClass.getMethod("fontManager").invoke(instance)

            val loadedMethod = fontManager.javaClass.getMethod("loadedBitmapImages")
            @Suppress("UNCHECKED_CAST")
            val images = loadedMethod.invoke(fontManager) as Map<*, *>

            images.entries.mapNotNull { (key, bitmapImage) ->
                runCatching {
                    val img = bitmapImage!!
                    val imgClass = img.javaClass

                    val keyStr = key.toString()
                    val name = keyStr.substringAfterLast(":")
                        .substringAfterLast("/")
                        .ifEmpty { keyStr }

                    val id = if (keyStr.contains(":")) keyStr else "craftengine:$keyStr"

                    val fileMethod = imgClass.getMethod("file")
                    val path = fileMethod.invoke(img) as String

                    val codepointMethod = imgClass.getMethod(
                        "codepointAt",
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType
                    )
                    val cp = codepointMethod.invoke(img, 0, 0) as Int

                    val height = try {
                        val heightMethod = imgClass.getMethod("height")
                        heightMethod.invoke(img) as Int
                    } catch (_: Exception) { 16 }

                    GlyphInfo(
                        id = id,
                        name = name,
                        path = path,
                        unicode = cp.toChar(),
                        width = 16,
                        height = height
                    )
                }.getOrNull()
            }
        }.onFailure {
            logger.warning("[GUI-Editor] CraftEngine glyph enumeration failed: ${it.message}")
        }.getOrDefault(emptyList())
    }

    override fun getGlyphTexture(glyphId: String): ByteArray? {
        return runCatching {
            // Strip provider prefix if present
            val name = glyphId.removePrefix("craftengine:")
            val baseName = name.substringAfterLast(":")

            // Try to find the CraftEngine resource pack output directory via reflection
            val packDir = findCraftEnginePackDir() ?: return null

            // Search for the texture by name in assets/minecraft/textures/ subdirectories
            val texturesDir = packDir.resolve("assets/minecraft/textures")
            if (!Files.isDirectory(texturesDir)) return null

            Files.walk(texturesDir).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".png") }
                    .filter { path ->
                        val fileName = path.fileName.toString().removeSuffix(".png")
                        fileName.equals(baseName, ignoreCase = true) ||
                            fileName.equals(name.replace('/', '_'), ignoreCase = true)
                    }
                    .findFirst()
                    .map { Files.readAllBytes(it) }
                    .orElse(null)
            }
        }.onFailure {
            logger.warning("[GUI-Editor] CraftEngine glyph texture lookup failed for '$glyphId': ${it.message}")
        }.getOrNull()
    }

    /**
     * Attempts to locate the CraftEngine resource pack output directory.
     * First tries reflection on CraftEngine's pack output path, then falls back
     * to the standard plugins/CraftEngine/resourcepack/ directory.
     */
    private fun findCraftEnginePackDir(): Path? {
        // Try reflection to access the pack output path
        runCatching {
            val ceClass = Class.forName("net.momirealms.craftengine.core.plugin.CraftEngine")
            val instance = ceClass.getMethod("instance").invoke(null)

            // Try getting the pack directory via reflection
            for (methodName in listOf("getPackDir", "packDir", "getResourcePackDir", "resourcePackDir")) {
                runCatching {
                    val method = ceClass.getMethod(methodName)
                    val dir = method.invoke(instance)
                    if (dir is Path && Files.isDirectory(dir)) return dir
                    if (dir is java.io.File && dir.isDirectory) return dir.toPath()
                }
            }

            // Try accessing via the plugin's data folder
            runCatching {
                val plugin = Bukkit.getPluginManager().getPlugin("CraftEngine")
                if (plugin != null) {
                    val dataFolder = plugin.dataFolder
                    val packDir = dataFolder.toPath().resolve("resourcepack")
                    if (Files.isDirectory(packDir)) return packDir
                }
            }
        }

        // Fall back to standard path
        val fallback = Path.of("plugins/CraftEngine/resourcepack")
        if (Files.isDirectory(fallback)) return fallback

        return null
    }

    // ─── Glyph widths ─────────────────────────────────────────────────────────

    override fun getGlyphWidths(): Map<Int, Int> {
        return GuiTextFontCache.getAllGlyphWidths()
    }

    // ─── Assets ────────────────────────────────────────────────────────────────

    override fun getAssets(): Collection<AssetInfo> {
        val now = System.currentTimeMillis()
        if (cachedAssets != null && (now - lastScanTime) < 30_000) {
            return cachedAssets!!
        }
        lastScanTime = now

        cachedAssets = runCatching {
            val ceClass = Class.forName("net.momirealms.craftengine.core.plugin.CraftEngine")
            val instance = ceClass.getMethod("instance").invoke(null)
            val fontManager = ceClass.getMethod("fontManager").invoke(instance)
            val loadedMethod = fontManager.javaClass.getMethod("loadedBitmapImages")
            @Suppress("UNCHECKED_CAST")
            val images = loadedMethod.invoke(fontManager) as Map<*, *>

            images.entries.mapNotNull { (key, bitmapImage) ->
                runCatching {
                    val img = bitmapImage!!
                    val imgClass = img.javaClass

                    val keyStr = key.toString()
                    val name = keyStr.substringAfterLast(":")
                        .substringAfterLast("/")
                        .ifEmpty { keyStr }

                    val id = if (keyStr.contains(":")) keyStr else "craftengine:$keyStr"

                    val fileMethod = imgClass.getMethod("file")
                    val path = fileMethod.invoke(img) as String

                    val codepointMethod = imgClass.getMethod(
                        "codepointAt",
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType
                    )
                    val cp = codepointMethod.invoke(img, 0, 0) as Int

                    val unicode = runCatching {
                        val charUtilsClass = Class.forName(
                            "net.momirealms.craftengine.core.util.CharacterUtils"
                        )
                        val encodeMethod = charUtilsClass.getMethod(
                            "encodeCharsToUnicode",
                            CharArray::class.java
                        )
                        encodeMethod.invoke(null, charArrayOf(cp.toChar())) as String
                    }.getOrDefault("\\u%04X".format(cp))

                    AssetInfo(
                        id = id,
                        name = name,
                        path = path,
                        unicode = unicode
                    )
                }.getOrNull()
            }
        }.onFailure {
            logger.warning("[GUI-Editor] CraftEngine asset enumeration failed: ${it.message}")
        }.getOrDefault(emptyList())

        logger.info("[GUI-Editor] CraftEngine: ${cachedAssets!!.size} assets loaded")
        return cachedAssets!!
    }
}
