package btcrenaud.gui.resourcepack

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Thread-safe registry of [ResourcePackProvider] instances.
 *
 * Providers are discovered via Koin and registered here. The registry
 * offers merged views across all active providers, resolving conflicts
 * by priority (highest wins).
 */
class ResourcePackRegistry {
    private val providers = ConcurrentHashMap<String, ResourcePackProvider>()

    /** Listeners notified when providers are registered or deregistered. */
    private val changeListeners = CopyOnWriteArrayList<() -> Unit>()

    /** External/merged glyphs registered outside of providers (e.g., from RP merger). */
    private val externalGlyphs = ConcurrentHashMap<String, GlyphInfo>()

    fun addChangeListener(listener: () -> Unit) {
        changeListeners.add(listener)
    }

    fun removeChangeListener(listener: () -> Unit) {
        changeListeners.remove(listener)
    }

    private fun notifyChangeListeners() {
        changeListeners.forEach { it.invoke() }
    }

    fun registerExternalGlyph(glyph: GlyphInfo) {
        externalGlyphs[glyph.id] = glyph
    }

    fun getExternalGlyphs(): Collection<GlyphInfo> = externalGlyphs.values.toList()

    fun register(provider: ResourcePackProvider) {
        providers[provider.providerId] = provider
        notifyChangeListeners()
    }

    fun unregister(providerId: String) {
        providers.remove(providerId)
        notifyChangeListeners()
    }

    fun getProvider(providerId: String): ResourcePackProvider? = providers[providerId]

    fun getAllProviders(): Collection<ResourcePackProvider> = providers.values.toList()

    /** The highest-priority available provider, or null if none are available. */
    fun getActiveProvider(): ResourcePackProvider? =
        providers.values
            .filter { it.isAvailable() }
            .maxByOrNull { it.priority }

    /** All providers that are currently available, sorted by priority descending. */
    fun getAvailableProviders(): List<ResourcePackProvider> =
        providers.values
            .filter { it.isAvailable() }
            .sortedByDescending { it.priority }

    // ─── Merged views ──────────────────────────────────────────────────────

    fun getAllGlyphs(): Collection<GlyphInfo> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<GlyphInfo>()

        for (glyph in externalGlyphs.values) {
            if (seen.add(glyph.id)) {
                result.add(glyph)
            }
        }

        for (provider in getAvailableProviders()) {
            for (glyph in provider.getGlyphs()) {
                if (seen.add(glyph.id)) {
                    result.add(glyph)
                }
            }
        }
        return result
    }

    fun getAllAssets(): Collection<AssetInfo> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<AssetInfo>()
        for (provider in getAvailableProviders()) {
            for (asset in provider.getAssets()) {
                if (seen.add(asset.id)) {
                    result.add(asset)
                }
            }
        }
        return result
    }

    fun getAllGlyphWidths(): Map<Int, Int> {
        val merged = mutableMapOf<Int, Int>()
        for (provider in getAvailableProviders()) {
            provider.getGlyphWidths().forEach { (codepoint, width) ->
                merged.putIfAbsent(codepoint, width)
            }
        }
        return merged
    }

    /** Look up a glyph texture across all providers (first match wins by priority). */
    fun getGlyphTexture(glyphId: String): ByteArray? {
        for (provider in getAvailableProviders()) {
            provider.getGlyphTexture(glyphId)?.let { return it }
        }
        return null
    }
}
