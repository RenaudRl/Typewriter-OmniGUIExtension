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
}
