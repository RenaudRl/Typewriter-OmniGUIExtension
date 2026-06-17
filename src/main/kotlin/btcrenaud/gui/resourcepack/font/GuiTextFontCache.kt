package btcrenaud.gui.resourcepack.font

import java.util.concurrent.ConcurrentHashMap

/**
 * Central cache for CraftEngine glyph widths populated by the
 * resource-pack generation hook.
 *
 * Unlike the standalone mode which generates its own widths,
 * CraftEngine glyph widths are discovered at resource-pack build time
 * and stored here for the web editor to query.
 */
object GuiTextFontCache {
    private val cache = ConcurrentHashMap<Int, Int>()

    fun put(codepoint: Int, width: Int) {
        cache[codepoint] = width
    }

    fun putAll(widths: Map<Int, Int>) {
        cache.putAll(widths)
    }

    fun get(codepoint: Int): Int? = cache[codepoint]

    fun getAllGlyphWidths(): Map<Int, Int> = cache.toMap()

    fun clear() {
        cache.clear()
    }

    fun size(): Int = cache.size
}
