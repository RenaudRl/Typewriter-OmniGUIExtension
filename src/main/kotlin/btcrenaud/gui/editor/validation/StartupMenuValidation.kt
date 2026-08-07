package btcrenaud.gui.editor.validation

import btcrenaud.gui.editor.api.ButtonTypeRegistry
import btcrenaud.gui.editor.api.MenuEditContext
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.logging.Logger

/**
 * Validates every `open_gui` entry on disk once, at startup.
 *
 * A malformed menu costs nothing at load time and everything at open time: a slot placed outside
 * its layout bounds, a `baseMenuId` pointing at a deleted template or a `buttonType` no extension
 * claims all fail silently, in front of a player, with no trace of which page is at fault. This
 * pass reads the published pages, runs [MenuValidationService] over them, and names the offending
 * page and path in the console before anyone opens anything.
 *
 * It only reads and reports: an invalid menu is never rewritten or disabled here, because the
 * authority over what a page contains belongs to the page author.
 */
object StartupMenuValidation {

    private const val ENTRY_TYPE = "open_gui"

    /** @return the number of entries reported with at least one error. */
    fun run(baseDir: File, logger: Logger): Int {
        val pagesDir = File(baseDir, "pages")
        if (!pagesDir.isDirectory) return 0

        val pageFiles = pagesDir.listFiles { f: File -> f.isFile && f.extension == "json" }
            ?: return 0

        // Every entry of every page, so `baseMenuId` chains resolve across page boundaries.
        val entriesById = HashMap<String, JsonObject>()
        val menus = mutableListOf<Pair<String, JsonObject>>() // page name → entry

        for (file in pageFiles) {
            val root = runCatching { JsonParser.parseString(file.readText()) }
                .getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
                ?: run {
                    logger.warning("[GUI] Page ${file.name} could not be read as JSON; skipped.")
                    null
                } ?: continue

            val entries = root.getAsJsonArray("entries") ?: continue
            for (element in entries) {
                if (!element.isJsonObject) continue
                val entry = element.asJsonObject
                val id = entry.get("id")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
                entriesById[id] = entry
                if (entry.get("type")?.takeIf { it.isJsonPrimitive }?.asString == ENTRY_TYPE) {
                    menus += file.nameWithoutExtension to entry
                }
            }
        }

        if (menus.isEmpty()) return 0

        // The registry is the only thing that knows which button types exist; without a single
        // contribution it stays silent rather than rejecting types it cannot judge.
        MenuValidationService.buttonTypeValidator = { buttonType, buttonPrefix, entry ->
            ButtonTypeRegistry.isKnownButtonType(buttonType, buttonPrefix, JsonMenuEditContext(entry))
        }

        var faulty = 0
        var warned = 0
        for ((page, entry) in menus) {
            val result = MenuValidationService.validate(entry) { id -> entriesById[id] }
            if (result.issues.isEmpty()) continue

            val name = entry.get("name")?.takeIf { it.isJsonPrimitive }?.asString
                ?: entry.get("id")?.asString.orEmpty()
            if (result.errors.isNotEmpty()) faulty++
            if (result.warnings.isNotEmpty()) warned++

            reportErrors(result.errors, "$page/$name", logger)
            summarizeWarnings(result.warnings, "$page/$name", logger)
        }

        // Always closed with one line, so a silent boot is proof the pass ran rather than proof
        // it was skipped.
        logger.info(
            "[GUI] Validated ${menus.size} menu(s) across ${pageFiles.size} page file(s) — " +
                "$faulty with errors, $warned with warnings.",
        )
        return faulty
    }

    /** Distinct errors detailed per menu before the rest is summarized away. */
    private const val MAX_ERROR_LINES_PER_MENU = 5

    /**
     * One line per authored element, not per consequence.
     *
     * A single item can expand over dozens of coordinates, and reporting each of them buries the
     * five menus that actually need attention under a hundred copies of one mistake. Errors are
     * grouped by code and by the element they point at; the repetition count is what is reported.
     */
    private fun reportErrors(errors: List<MenuValidationService.Issue>, menu: String, logger: Logger) {
        if (errors.isEmpty()) return
        val groups = errors.groupBy { Triple(it.code, it.editorId, it.path) }

        groups.entries.take(MAX_ERROR_LINES_PER_MENU).forEach { (_, group) ->
            val issue = group.first()
            val repeated = if (group.size > 1) " (x${group.size})" else ""
            logger.severe("[GUI] $menu — ${issue.code} at ${issue.path}$repeated: ${issue.message}")
        }
        val hidden = groups.size - MAX_ERROR_LINES_PER_MENU
        if (hidden > 0) logger.severe("[GUI] $menu — and $hidden other error(s) not shown.")
    }

    /**
     * Warnings get one line per menu, naming their codes and nothing more.
     *
     * They flag authoring choices that still render — an unreferenced layout, a view that leaves a
     * frame empty — so spelling each one out at every boot costs more attention than it returns.
     * The codes are enough to tell whether the line deserves a look.
     */
    private fun summarizeWarnings(warnings: List<MenuValidationService.Issue>, menu: String, logger: Logger) {
        if (warnings.isEmpty()) return
        val codes = warnings.map { it.code }.distinct().sorted()
        logger.warning(
            "[GUI] $menu — ${warnings.size} warning(s): ${codes.joinToString(", ")}.",
        )
    }

    /** Read-only view of a stored entry, so contributions never see the raw JSON shape. */
    private class JsonMenuEditContext(private val entry: JsonObject) : MenuEditContext {
        override val entryId: String
            get() = entry.get("id")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        override val entryType: String
            get() = entry.get("type")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

        override fun stringField(name: String): String? =
            entry.get(name)?.takeIf { it.isJsonPrimitive }?.asString
    }
}
