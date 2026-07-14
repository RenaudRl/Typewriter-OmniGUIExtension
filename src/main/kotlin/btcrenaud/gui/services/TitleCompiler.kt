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

        // Shift tags are RELATIVE cursor moves, so adjusting every tag would compound
        // the offset (element k would drift by k×offset). Prefixing a single negative
        // shift moves the whole composition left while preserving relative positions.
        // Vertical scrolling cannot be expressed in the title string itself — it
        // requires per-offset ascent variants in the font providers.
        return if (scrollOffsetX != 0) {
            "<shift:${-scrollOffsetX}>$rawTitle"
        } else {
            rawTitle
        }
    }
}
