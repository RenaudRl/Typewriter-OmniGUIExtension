package btcrenaud.gui.api

/**
 * Uniform "n/N" page indicator primitive, exposed by OmniGUI for every extension that builds a
 * paginated menu.
 *
 * Without it each consumer re-derives its own `page`/`total_pages` placeholders with slightly
 * different conventions (0- or 1-indexed, prefixed key names), so two paginated screens in the
 * same server end up counting differently. This object fixes ONE token convention, usable in any
 * MiniMessage display name or lore without duplicated logic.
 *
 * Token convention (the current page is always shown 1-indexed to the player):
 *  - `{page}`           current page, 1-indexed (e.g. "3")
 *  - `{next_page}`      next page, 1-indexed, capped at [totalPages]
 *  - `{prev_page}`      previous page, 1-indexed, floored at 1
 *  - `{total_pages}`    total number of pages (minimum 1)
 *  - `{page_indicator}` short ready-to-display rendering, e.g. "3/12"
 */
object PageIndicator {

    /** Short default rendering, e.g. "3/12". [currentPageZeroIndexed] is a list index. */
    fun render(currentPageZeroIndexed: Int, totalPages: Int): String {
        val total = totalPages.coerceAtLeast(1)
        val current = (currentPageZeroIndexed + 1).coerceIn(1, total)
        return "$current/$total"
    }

    /** Standard placeholder set for this indicator, page 0-indexed on input. */
    fun placeholders(currentPageZeroIndexed: Int, totalPages: Int): Map<String, String> {
        val total = totalPages.coerceAtLeast(1)
        val current = (currentPageZeroIndexed + 1).coerceIn(1, total)
        return mapOf(
            "page" to current.toString(),
            "next_page" to (current + 1).coerceAtMost(total).toString(),
            "prev_page" to (current - 1).coerceAtLeast(1).toString(),
            "total_pages" to total.toString(),
            "page_indicator" to "$current/$total",
        )
    }

    /** Applies the `{key}` tokens of [placeholders] onto [text]. */
    fun apply(text: String, currentPageZeroIndexed: Int, totalPages: Int): String =
        placeholders(currentPageZeroIndexed, totalPages).entries.fold(text) { acc, (key, value) ->
            acc.replace("{$key}", value)
        }

    /** List variant (lore usage): applies the tokens on every line. */
    fun apply(lines: List<String>, currentPageZeroIndexed: Int, totalPages: Int): List<String> {
        val map = placeholders(currentPageZeroIndexed, totalPages)
        return lines.map { line -> map.entries.fold(line) { acc, (key, value) -> acc.replace("{$key}", value) } }
    }
}
