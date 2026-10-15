package btcrenaud.gui.api

/**
 * Decides how many times an item is drawn for the cells of a repeated slot.
 *
 * An item can be a random value (a variable backed by a ranged variable, a loot roll). Repeating
 * it over 27 cells either shows the same draw 27 times or 27 independent draws; which one the
 * author wants is the `rollPerSlot` setting, honoured here and nowhere else.
 */
object SlotRoll {

    /**
     * One entry per cell, in cell order.
     *
     * With [rollPerSlot] off the item is rolled once and that single draw is shared by every cell
     * (the callers clone it). With it on, [roll] runs once per cell. The returned list is a
     * snapshot taken now: it is the value the menu keeps for its whole session, so a later render
     * decorates these draws instead of rolling again.
     */
    fun <T> draw(cells: Int, rollPerSlot: Boolean, roll: () -> T): List<T> {
        if (cells <= 0) return emptyList()
        if (!rollPerSlot) {
            val shared = roll()
            return List(cells) { shared }
        }
        return List(cells) { roll() }
    }

    /**
     * What a live menu runs on every render for one cell: [decorate] applied to the draw it kept
     * since opening. It never rolls, so the draw stays frozen for the session whatever
     * `rollPerSlot` says, and it is built for every cell, because whether a name or lore exists
     * can change between two renders (a variable that turns blank, a lore added by a placeholder).
     */
    fun <T, P> refresher(drawn: T, decorate: (T, P) -> T): (P) -> T = { target -> decorate(drawn, target) }

    /**
     * The opt-in counterpart of [refresher] (`liveItem`): the item is rolled again at every paint.
     * One instance serves all the cells of one item, which a paint visits in cell order.
     *
     * With [rollPerSlot] on, every call rolls. With it off, the cells of one paint share one roll.
     * Providers get no render id, so a new paint is recognised by the cell order not increasing.
     * shortcut: if a paint only reaches higher cells than the previous one (a lower cell newly
     * hidden by another slot), it reuses the previous shared roll once; upgrade by handing a
     * render id to item providers.
     */
    class LiveDraw<T>(private val rollPerSlot: Boolean) {
        private var lastCell = Int.MAX_VALUE
        private var shared: Any? = null

        fun next(cell: Int, roll: () -> T): T {
            if (rollPerSlot) return roll()
            if (cell <= lastCell) shared = roll()
            lastCell = cell
            @Suppress("UNCHECKED_CAST")
            return shared as T
        }
    }
}
