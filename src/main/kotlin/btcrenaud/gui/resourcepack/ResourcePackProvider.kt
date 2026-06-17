package btcrenaud.gui.resourcepack

/**
 * Abstraction layer for resource pack font/glyph providers.
 *
 * Implementations can wrap CraftEngine, ItemsAdder, Nexo, or serve
 * standalone assets from the filesystem. The registry merges all
 * available providers by priority.
 */
interface ResourcePackProvider {
    /** Unique identifier, e.g. "craftengine", "itemsadder", "nexo", "standalone" */
    val providerId: String

    /** Human-readable name for logging and status display */
    val displayName: String

    /**
     * Priority for conflict resolution. Higher priority providers
     * win when two claim the same glyph/asset id.
     *
     * Recommended ranges:
     *  - 100: primary RP plugin (CraftEngine)
     *  - 50:  secondary RP plugins (ItemsAdder, Nexo)
     *  - 10:  fallback standalone provider
     */
    val priority: Int

    /** Whether this provider is currently available (plugin loaded, directory exists, etc.) */
    fun isAvailable(): Boolean

    /** All font glyphs this provider can serve */
    fun getGlyphs(): Collection<GlyphInfo>

    /** Raw PNG bytes for a glyph texture by its id */
    fun getGlyphTexture(glyphId: String): ByteArray?

    /** Unicode codepoint -> pixel width map for text measurement */
    fun getGlyphWidths(): Map<Int, Int>

    /** Flat asset list matching the format expected by the web editor */
    fun getAssets(): Collection<AssetInfo>
}

data class GlyphInfo(
    val id: String,
    val name: String,
    val path: String,
    val unicode: Char,
    val width: Int,
    val height: Int
)

data class AssetInfo(
    val id: String,
    val name: String,
    val path: String,
    val unicode: String
)
