package btcrenaud.gui.services

import btcrenaud.gui.api.MenuDefinition
import btcrenaud.gui.api.Viewport
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage

/**
 * Compiles a GUI title Component from a [MenuDefinition].
 *
 * The title MiniMessage string may contain CraftEngine tags ([shift], [image]) that
 * are resolved by CraftEngine's tag resolvers. For scrollable menus, the title is
 * rebuilt with viewport-adjusted offsets so visual elements scroll correctly.
 */
object TitleCompiler {

    /** Regex to extract [shift:N] offsets from a MiniMessage string. */
    private val SHIFT_REGEX = Regex("<shift:(-?\\d+)>")

    fun compile(definition: MenuDefinition, viewport: Viewport? = null): Component {
        val rawTitle = definition.rawTitle

        if (rawTitle == null) {
            return definition.title ?: Component.empty()
        }

        return if (viewport != null && (viewport.x > 0 || viewport.y > 0)) {
            val adjusted = adjustTitleForViewport(rawTitle, viewport)
            parseTitle(adjusted)
        } else {
            parseTitle(rawTitle)
        }
    }

    /**
     * Parses a MiniMessage title string into a Component.
     * Re-parsing at render time ensures CraftEngine's tag resolvers are active.
     */
    private fun parseTitle(miniMessage: String): Component {
        return runCatching {
            MiniMessage.miniMessage().deserialize(miniMessage)
        }.getOrElse {
            // Fallback: return as plain text if MiniMessage parsing fails
            Component.text(miniMessage)
        }
    }

    /**
     * Adjusts the title MiniMessage for a scrolled viewport.
     *
     * Strategy: shift the initial cursor position to account for the viewport offset.
     * This causes elements that are now off-screen to shift left/up, and brings
     * scrolled-into-view elements into the visible area.
     */
    private fun adjustTitleForViewport(rawTitle: String, viewport: Viewport): String {
        // Calculate the pixel offset induced by the viewport scroll.
        // Each slot is 18 pixels wide/tall in the Minecraft GUI coordinate space.
        val scrollOffsetX = viewport.x * 18
        val scrollOffsetY = viewport.y * 18

        if (scrollOffsetX == 0 && scrollOffsetY == 0) return rawTitle

        // For horizontal scrolling: subtract from EVERY shift tag's offset.
        // For vertical scrolling: handled by ascent values in the font provider
        // configuration — the title bar adjusts its Y via the inventory's built-in
        // rendering. Horizontal adjustment is the only one needed in the title.
        // FIX 8: Use replaceAll (Kotlin Regex.replace replaces ALL matches, not just first)
        return if (scrollOffsetX != 0) {
            SHIFT_REGEX.replace(rawTitle) { match ->
                val currentOffset = match.groupValues[1].toIntOrNull() ?: 0
                val adjustedOffset = currentOffset - scrollOffsetX
                "<shift:$adjustedOffset>"
            }
        } else {
            rawTitle
        }
    }
}
