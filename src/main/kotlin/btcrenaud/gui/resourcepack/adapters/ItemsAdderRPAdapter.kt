package btcrenaud.gui.resourcepack.adapters

import btcrenaud.gui.resourcepack.AssetInfo
import btcrenaud.gui.resourcepack.GlyphInfo
import btcrenaud.gui.resourcepack.ResourcePackProvider
import com.typewritermc.engine.paper.logger
import org.bukkit.Bukkit
import java.nio.file.Files
import java.nio.file.Path

/**
 * Bridges ItemsAdder's font system to the GUI editor.
 *
 * All access is via reflection since ItemsAdder has no public Maven repository.
 * Gracefully degrades if ItemsAdder is not installed or its API has changed.
 */
class ItemsAdderRPAdapter : ResourcePackProvider {

    override val providerId = "itemsadder"
    override val displayName = "ItemsAdder"
    override val priority = 50

    private var cachedAssets: List<AssetInfo>? = null
    private var lastScanTime = 0L

    override fun isAvailable(): Boolean {
        return Bukkit.getPluginManager().getPlugin("ItemsAdder") != null
    }

    override fun getGlyphs(): Collection<GlyphInfo> {
        return runCatching {
            val iaClass = Class.forName("dev.lone.itemsadder.api.ItemsAdder")
            val getFontImagesMethod = iaClass.getMethod("getFontImages")

            @Suppress("UNCHECKED_CAST")
            val images = getFontImagesMethod.invoke(null) as? Collection<*> ?: return emptyList()

            images.mapNotNull { image ->
                runCatching {
                    val imgClass = image!!.javaClass
                    val getNamespacedIdMethod = imgClass.getMethod("getNamespacedID")
                    val id = getNamespacedIdMethod.invoke(image) as String

                    val getNameMethod = imgClass.getMethod("getName")
                    val name = getNameMethod.invoke(image) as String

                    val path = try {
                        val getTextureMethod = imgClass.getMethod("getTexture")
                        getTextureMethod.invoke(image) as? String ?: ""
                    } catch (_: Exception) { "" }

                    val unicode = try {
                        val getCharMethod = imgClass.getMethod("getChar")
                        getCharMethod.invoke(image) as? Char ?: '\uE000'
                    } catch (_: Exception) { '\uE000' }

                    GlyphInfo(
                        id = id,
                        name = name,
                        path = path,
                        unicode = unicode,
                        width = 16,
                        height = 16
                    )
                }.getOrNull()
            }
        }.onFailure {
            logger.warning("[GUI-Editor] ItemsAdder glyph enumeration failed: ${it.message}")
        }.getOrDefault(emptyList())
    }

    override fun getGlyphTexture(glyphId: String): ByteArray? {
        return runCatching {
            // Strip provider prefix if present
            val name = glyphId.removePrefix("itemsadder:")

            // Strategy 1: Try ItemsAdder API to get the pack URL/path
            runCatching {
                val iaClass = Class.forName("dev.lone.itemsadder.api.ItemsAdder")

                // Try getPackUrl() to locate the pack
                runCatching {
                    val getPackUrlMethod = iaClass.getMethod("getPackUrl")
                    val packUrl = getPackUrlMethod.invoke(null) as? String
                    if (packUrl != null) {
                        // The URL might be a file path or point to a local resource pack
                        val uri = java.net.URI(packUrl)
                        if (uri.scheme == "file") {
                            val packFile = Path.of(uri)
                            if (Files.isDirectory(packFile)) {
                                val found = searchForTexture(packFile, name)
                                if (found != null) return found
                            }
                        }
                    }
                }

                // Strategy 2: Try FontImageWrapper.getPath() via reflection
                runCatching {
                    val getFontImagesMethod = iaClass.getMethod("getFontImages")
                    @Suppress("UNCHECKED_CAST")
                    val images = getFontImagesMethod.invoke(null) as? Collection<*> ?: return@runCatching

                    for (image in images) {
                        runCatching {
                            val imgClass = image!!.javaClass
                            val getNamespacedIdMethod = imgClass.getMethod("getNamespacedID")
                            val id = getNamespacedIdMethod.invoke(image) as String
                            if (id == glyphId || id == name) {
                                // Try getPath() method for FontImageWrapper
                                for (methodName in listOf("getPath", "getTexturePath", "getTexture")) {
                                    runCatching {
                                        val getPathMethod = imgClass.getMethod(methodName)
                                        val pathStr = getPathMethod.invoke(image) as? String
                                        if (pathStr != null) {
                                            val textureFile = Path.of(pathStr)
                                            if (Files.isRegularFile(textureFile)) {
                                                return Files.readAllBytes(textureFile)
                                            }
                                            // Try relative to ItemsAdder data folder
                                            val iaPlugin = Bukkit.getPluginManager().getPlugin("ItemsAdder")
                                            if (iaPlugin != null) {
                                                val absPath = iaPlugin.dataFolder.toPath().resolve(pathStr)
                                                if (Files.isRegularFile(absPath)) {
                                                    return Files.readAllBytes(absPath)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Strategy 3: Search plugins/ItemsAdder/output/ directory
            val outputDir = Path.of("plugins/ItemsAdder/output")
            if (Files.isDirectory(outputDir)) {
                val found = searchForTexture(outputDir, name)
                if (found != null) return found
            }

            // Strategy 4: Search ItemsAdder data folder
            val iaPlugin = Bukkit.getPluginManager().getPlugin("ItemsAdder")
            if (iaPlugin != null) {
                val dataDir = iaPlugin.dataFolder.toPath()
                val found = searchForTexture(dataDir, name)
                if (found != null) return found
            }

            null
        }.onFailure {
            logger.warning("[GUI-Editor] ItemsAdder glyph texture lookup failed for '$glyphId': ${it.message}")
        }.getOrNull()
    }

    /**
     * Searches a directory tree for a texture PNG matching the given name.
     * Looks in assets/minecraft/textures/ subdirectories as well as the root.
     */
    private fun searchForTexture(baseDir: Path, name: String): ByteArray? {
        val baseName = name.substringAfterLast(":")

        // Search in assets/minecraft/textures/ subdirectories
        val texturesDir = baseDir.resolve("assets/minecraft/textures")
        val searchDirs = mutableListOf<Path>()
        if (Files.isDirectory(texturesDir)) {
            searchDirs.add(texturesDir)
        }
        searchDirs.add(baseDir) // Also search root as fallback

        for (searchDir in searchDirs) {
            if (!Files.isDirectory(searchDir)) continue
            Files.walk(searchDir).use { stream ->
                val match = stream
                    .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".png") }
                    .filter { path ->
                        val fileName = path.fileName.toString().removeSuffix(".png")
                        fileName.equals(baseName, ignoreCase = true) ||
                            fileName.equals(name.replace('/', '_'), ignoreCase = true) ||
                            fileName.equals(name.replace(':', '_'), ignoreCase = true)
                    }
                    .findFirst()

                if (match.isPresent) {
                    return Files.readAllBytes(match.get())
                }
            }
        }

        return null
    }

    override fun getGlyphWidths(): Map<Int, Int> {
        return runCatching {
            val iaClass = Class.forName("dev.lone.itemsadder.api.ItemsAdder")
            val getAllFontImagesMethod = iaClass.getMethod("getAllFontImages")

            @Suppress("UNCHECKED_CAST")
            val images = getAllFontImagesMethod.invoke(null) as? Collection<*> ?: return emptyMap()

            images.mapNotNull { image ->
                runCatching {
                    val imgClass = image!!.javaClass
                    val getCharMethod = imgClass.getMethod("getChar")
                    val char = getCharMethod.invoke(image) as? Char ?: return@runCatching null

                    val getWidthMethod = try {
                        imgClass.getMethod("getWidth")
                    } catch (_: Exception) { imgClass.getMethod("getWidthPixels") }
                    val width = getWidthMethod.invoke(image) as? Int ?: 16
                    char.code to width
                }.getOrNull()
            }.toMap()
        }.onFailure {
            logger.warning("[GUI-Editor] ItemsAdder glyph widths unavailable: ${it.message}")
        }.getOrDefault(emptyMap())
    }

    override fun getAssets(): Collection<AssetInfo> {
        val now = System.currentTimeMillis()
        if (cachedAssets != null && (now - lastScanTime) < 30_000) {
            return cachedAssets!!
        }
        lastScanTime = now

        cachedAssets = runCatching {
            val iaClass = Class.forName("dev.lone.itemsadder.api.ItemsAdder")
            val getFontImagesMethod = iaClass.getMethod("getFontImages")

            @Suppress("UNCHECKED_CAST")
            val images = getFontImagesMethod.invoke(null) as? Collection<*> ?: return emptyList()

            images.mapNotNull { image ->
                runCatching {
                    val imgClass = image!!.javaClass
                    val getNamespacedIdMethod = imgClass.getMethod("getNamespacedID")
                    val id = getNamespacedIdMethod.invoke(image) as String

                    val getNameMethod = imgClass.getMethod("getName")
                    val name = getNameMethod.invoke(image) as String

                    val path = try {
                        val getTextureMethod = imgClass.getMethod("getTexture")
                        getTextureMethod.invoke(image) as? String ?: ""
                    } catch (_: Exception) { "" }

                    val unicode = try {
                        val getCharMethod = imgClass.getMethod("getChar")
                        val char = getCharMethod.invoke(image) as? Char ?: '\uE000'
                        "\\u%04X".format(char.code)
                    } catch (_: Exception) { "\\uE000" }

                    AssetInfo(id = id, name = name, path = path, unicode = unicode)
                }.getOrNull()
            }
        }.onFailure {
            logger.warning("[GUI-Editor] ItemsAdder asset enumeration failed: ${it.message}")
        }.getOrDefault(emptyList())

        logger.info("[GUI-Editor] ItemsAdder: ${cachedAssets!!.size} assets loaded")
        return cachedAssets!!
    }
}
