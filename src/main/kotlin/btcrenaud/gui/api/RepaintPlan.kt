package btcrenaud.gui.api

/**
 * Decides which cells a refresh writes back into a menu that is already open.
 *
 * A refresh compares the new picture with the previous one ([last]), not with the live inventory.
 * For a cell the player can take from, that is not enough: once they have taken the item the cell is
 * empty, and a text that changed since the last paint (a countdown in a lore) would put a copy of
 * the item back, to be handed to them again when the menu closes. Free of Bukkit items so the rule
 * is unit-tested; [btcrenaud.gui.services.MenuSessionService] supplies the real inventory reads.
 */
object RepaintPlan {

    /**
     * The writes to perform: index to the item to set, or null to clear the cell.
     *
     * A cell in [pickupIndices] that held an item on the last paint but is [isEmptyNow] was emptied
     * by the player and is left alone. Every other cell is rewritten when its item changed.
     */
    fun <T> changes(
        last: Map<Int, T>,
        next: Map<Int, T>,
        pickupIndices: Set<Int>,
        isEmptyNow: (Int) -> Boolean,
    ): Map<Int, T?> {
        val writes = java.util.TreeMap<Int, T?>()
        for (index in last.keys + next.keys) {
            val item = next[index]
            if (item == last[index]) continue
            if (index in pickupIndices && last[index] != null && isEmptyNow(index)) continue
            writes[index] = item
        }
        return writes
    }
}
