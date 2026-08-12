package btcrenaud.gui.api

/**
 * The single owner of the slot repetition formula.
 *
 * The runtime ([btcrenaud.gui.GuiSlotBuilder.expandPositions]) and the editor validation
 * ([btcrenaud.gui.editor.validation.MenuValidationService]) each carried their own copy of
 * this maths. A copy that drifts is an editor validating a menu the server draws differently —
 * both now go through here.
 *
 * Semantics, everywhere in the ecosystem:
 * - no `direction` ⇒ ONE position (x, y). `count`, `gap` and `repeatY` then have no effect:
 *   that is a configuration mistake, and validation reports it.
 * - `count` repeats along `direction`.
 * - `repeatY` repeats the WHOLE block on the axis PERPENDICULAR to `direction`: vertically for
 *   `right`/`left`, horizontally for `down`/`up`. It is not "always downwards".
 * - `gap` is a STEP, not a spacing: 1 = adjacent slots, 2 = one empty slot between each. It
 *   applies to both axes.
 * - `count`/`repeatY` are coerced to at least 1: the editor serializes an unset value as `0`,
 *   and `0 until 0` would silently drop the item.
 */
object SlotRepetition {

    /** The only recognised directions; order matches the `Direction` enum. */
    val DIRECTIONS: List<String> = listOf("right", "left", "down", "up")

    /** Positions occupied by an item, in draw order. */
    fun expand(x: Int, y: Int, direction: String?, count: Int, gap: Int, repeatY: Int): List<Pair<Int, Int>> {
        val dir = direction?.takeIf { it.isNotBlank() } ?: return listOf(x to y)
        if (dir !in DIRECTIONS) return listOf(x to y)

        val rows = repeatY.coerceAtLeast(1)
        val cols = count.coerceAtLeast(1)
        val positions = ArrayList<Pair<Int, Int>>(rows * cols)
        for (ry in 0 until rows) {
            for (rc in 0 until cols) {
                positions += when (dir) {
                    "right" -> (x + rc * gap) to (y + ry * gap)
                    "left" -> (x - rc * gap) to (y + ry * gap)
                    "down" -> (x + ry * gap) to (y + rc * gap)
                    else -> (x + ry * gap) to (y - rc * gap) // "up"
                }
            }
        }
        return positions
    }

    /**
     * True when repetition settings are configured while `direction` is missing: they are
     * ignored at render time, and the menu author expects the opposite.
     */
    fun hasOrphanRepetition(direction: String?, count: Int, gap: Int, repeatY: Int): Boolean =
        direction.isNullOrBlank() && (count > 1 || repeatY > 1 || gap > 1)
}
