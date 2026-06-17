package btcrenaud.gui.editor.assets

import com.typewritermc.engine.paper.logger
import java.nio.file.Files
import java.nio.file.Path

/**
 * Manages user-uploaded GUI assets (custom images, textures).
 *
 * Assets are stored in [plugins/Typewriter/gui/assets/custom/] and
 * registered in the ResourcePackRegistry so they appear in the
 * Flutter asset browser alongside provider assets.
 */
object GuiAssetStorageService {

    /** Base directory for user-uploaded assets. */
    val assetsDir: Path = Path.of("plugins/Typewriter/gui/assets")

    /** Directory for custom user-uploaded images. */
    val customDir: Path get() = assetsDir.resolve("custom")

    init {
        ensureDirectories()
    }

    /** Maximum allowed file size for uploaded assets (5 MiB). */
    private const val MAX_ASSET_SIZE_BYTES = 5 * 1024 * 1024

    private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /**
     * Stores an uploaded PNG image as a custom asset.
     *
     * @param name Asset name (used as filename, sanitized).
     * @param bytes Raw PNG bytes.
     * @return The full path to the saved file, or null on failure.
     */
    fun storeAsset(name: String, bytes: ByteArray): Path? {
        ensureDirectories()

        if (bytes.size > MAX_ASSET_SIZE_BYTES) {
            logger.warning("[GUI-Editor] Rejected asset '$name': exceeds 5 MiB size limit (${bytes.size} bytes)")
            return null
        }

        if (bytes.size < PNG_MAGIC.size || !bytes.take(PNG_MAGIC.size).toByteArray().contentEquals(PNG_MAGIC)) {
            logger.warning("[GUI-Editor] Rejected asset '$name': invalid PNG magic bytes")
            return null
        }

        val safeName = name.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64)
        val file = customDir.resolve("$safeName.png")
        return runCatching {
            Files.write(file, bytes)
            logger.info("[GUI-Editor] Stored custom asset: $safeName (${bytes.size} bytes)")
            file
        }.onFailure {
            logger.warning("[GUI-Editor] Failed to store asset $name: ${it.message}")
        }.getOrNull()
    }

    /**
     * Lists all custom assets by scanning the custom directory.
     *
     * @return List of asset file paths.
     */
    fun listAssets(): List<Path> {
        if (!Files.isDirectory(customDir)) return emptyList()
        return Files.list(customDir).use { stream ->
            stream
                .filter { it.fileName.toString().endsWith(".png") }
                .sorted { a, b -> a.fileName.compareTo(b.fileName) }
                .toList()
        }
    }

    /**
     * Reads a stored asset's bytes.
     *
     * @param name Asset name (without .png extension).
     * @return PNG bytes, or null if not found.
     */
    fun getAsset(name: String): ByteArray? {
        val safeName = name.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64)
        val file = customDir.resolve("$safeName.png")
        return if (Files.isRegularFile(file)) Files.readAllBytes(file) else null
    }

    /**
     * Deletes a custom asset.
     *
     * @param name Asset name (without .png extension).
     * @return true if deleted, false if not found.
     */
    fun deleteAsset(name: String): Boolean {
        val safeName = name.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64)
        val file = customDir.resolve("$safeName.png")
        return Files.deleteIfExists(file)
    }

    private fun ensureDirectories() {
        if (!Files.isDirectory(customDir)) Files.createDirectories(customDir)
    }
}
