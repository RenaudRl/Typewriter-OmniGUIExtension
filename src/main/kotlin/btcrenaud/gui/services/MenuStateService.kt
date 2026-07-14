package btcrenaud.gui.services

import btcrenaud.gui.editor.states.MenuStateDefinition
import btcrenaud.gui.editor.states.MenuStateLoader
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.typewritermc.engine.paper.logger
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

/**
 * Runtime access to menu states (`_gui_states`) for published entries.
 *
 * The typed [btcrenaud.gui.OpenGuiActionEntry] cannot carry the editor-managed
 * `_gui_states` blob (it is not part of the entry schema), so this service reads
 * it back from the published page JSON on demand and caches the parsed result.
 *
 * Cache entries expire after [CACHE_TTL_MS]; publishing from the web editor or
 * reloading Typewriter naturally refreshes them on the next open.
 */
object MenuStateService {

    private const val CACHE_TTL_MS = 30_000L
    private val pagesDir: Path = Path.of("plugins/Typewriter/pages")

    private data class CachedStates(val states: Map<String, MenuStateDefinition>, val loadedAt: Long)

    private val cache = ConcurrentHashMap<String, CachedStates>()

    /** Returns the parsed states for an entry, or an empty map when it has none. */
    fun getStates(entryId: String): Map<String, MenuStateDefinition> {
        val now = System.currentTimeMillis()
        cache[entryId]?.let { cached ->
            if (now - cached.loadedAt < CACHE_TTL_MS) return cached.states
        }
        val states = loadFromPublishedPages(entryId)
        cache[entryId] = CachedStates(states, now)
        return states
    }

    /** Drops all cached states (e.g. after a publish). */
    fun invalidate() {
        cache.clear()
    }

    private fun loadFromPublishedPages(entryId: String): Map<String, MenuStateDefinition> {
        return runCatching {
            if (!Files.isDirectory(pagesDir)) return emptyMap()
            Files.list(pagesDir).use { stream ->
                for (file in stream.filter { it.extension == "json" }) {
                    val page = runCatching {
                        JsonParser.parseReader(Files.newBufferedReader(file)).asJsonObject
                    }.getOrNull() ?: continue
                    val entries = page.get("entries")?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
                    val entry = entries.firstOrNull {
                        it is JsonObject && it.get("id")?.asString == entryId
                    } as? JsonObject ?: continue

                    val statesJson = entry.get("_gui_states")
                        ?.takeIf { it.isJsonObject }
                        ?.toString()
                    return MenuStateLoader.loadStates(entryId, statesJson).also {
                        if (it.isNotEmpty()) {
                            logger.fine("[GUI] Loaded ${it.size} state(s) for menu $entryId from ${file.nameWithoutExtension}")
                        }
                    }
                }
            }
            // Entry not found in published pages — still allow provider-registered states.
            MenuStateLoader.loadStates(entryId, null)
        }.getOrElse { e ->
            logger.warning("[GUI] Failed to load states for $entryId: ${e.message}")
            emptyMap()
        }
    }
}
