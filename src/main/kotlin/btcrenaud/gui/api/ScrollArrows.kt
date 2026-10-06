package btcrenaud.gui.api

import btcrenaud.gui.ScrollDirection

/**
 * What the `gui_settings` entry may change on one default arrow. Kept free of engine types so the
 * decisions of [ScrollArrows] stay unit-testable; the entry's `ScrollArrowConfig` implements it.
 */
interface ScrollArrowPlacement {
    val direction: ScrollDirection
    val name: String?
    val x: Int?
    val y: Int?
}

/**
 * Default arrows of a `scrollable` layout (`showDefaultButtons`).
 *
 * Pure decisions only: which directions still need an arrow, and where it goes. Building the
 * slot (item, command) stays in [LayoutParser], which owns the player and the context.
 * Everything an author may want to change lives in the `gui_settings` entry; the constants
 * below are only what an unconfigured install falls back to.
 */
object ScrollArrows {

    /** Arrow shown for [direction], at ([x], [y]) of the menu, with [name] as display name. */
    data class Spec(val direction: ScrollDirection, val x: Int, val y: Int, val name: String)

    private const val COLUMNS = 9

    private val DEFAULT_NAMES = mapOf(
        ScrollDirection.UP to "<gray>▲",
        ScrollDirection.DOWN to "<gray>▼",
        ScrollDirection.LEFT to "<gray>◀",
        ScrollDirection.RIGHT to "<gray>▶",
    )

    /**
     * Directions that get a default arrow: none when [showDefaultButtons] is off, and never one
     * for a direction the author already covers with a button of their own ([covered]).
     */
    fun missingDirections(covered: Collection<ScrollDirection>, showDefaultButtons: Boolean): List<ScrollDirection> {
        if (!showDefaultButtons) return emptyList()
        return ScrollDirection.entries.filter { it !in covered }
    }

    /**
     * The arrow for [direction]: the first `gui_settings` override of that direction fills the
     * name and the position it sets; whatever it leaves empty keeps the default.
     * [totalSize] is the number of slots of the menu, used to put the arrows on its last row.
     */
    fun spec(direction: ScrollDirection, overrides: List<ScrollArrowPlacement>, totalSize: Int): Spec {
        val override = overrides.firstOrNull { it.direction == direction }
        val lastRow = (totalSize / COLUMNS - 1).coerceAtLeast(0)
        val (defaultX, defaultY) = when (direction) {
            ScrollDirection.UP -> (COLUMNS - 1) to 0
            ScrollDirection.DOWN -> (COLUMNS - 1) to lastRow
            ScrollDirection.LEFT -> 0 to lastRow
            ScrollDirection.RIGHT -> 1 to lastRow
        }
        return Spec(
            direction = direction,
            x = override?.x ?: defaultX,
            y = override?.y ?: defaultY,
            name = override?.name?.takeIf { it.isNotEmpty() } ?: DEFAULT_NAMES.getValue(direction),
        )
    }

    /** Command run by an arrow of [direction], [step] slots per click. */
    fun command(direction: ScrollDirection, layoutId: String, step: Int = 1): String = when (direction) {
        ScrollDirection.UP -> "gui:scroll 0 -$step $layoutId"
        ScrollDirection.DOWN -> "gui:scroll 0 $step $layoutId"
        ScrollDirection.LEFT -> "gui:scroll -$step 0 $layoutId"
        ScrollDirection.RIGHT -> "gui:scroll $step 0 $layoutId"
    }
}
