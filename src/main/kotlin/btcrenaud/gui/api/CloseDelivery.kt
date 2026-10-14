package btcrenaud.gui.api

/** How an item still sitting in a menu cell is handed back to the player when the menu closes. */
enum class CloseDelivery {
    /** Into the player's inventory; whatever does not fit falls at their feet. */
    GIVE_OR_DROP,

    /** Everything falls at the player's feet. */
    DROP,
}

/**
 * The single owner of the rule "what leaves a menu with the player when it closes".
 *
 * Without it a closing menu simply erases its inventory, so loot the player never took (or an
 * item they placed in a pickup cell) vanished. The rules live here, free of Bukkit items, so they
 * are unit-tested; [btcrenaud.gui.services.MenuSessionService] only reads and clears the real cells.
 *
 * Two invariants hold for every caller: a decoration (a cell the player cannot take from) is never
 * handed back, and a cell is read once — the caller empties it before delivering, so a close that is
 * replayed finds nothing left and cannot duplicate the item.
 */
object CloseDeliveryPlan {

    /** What the plan needs to know about one drawn slot. */
    data class Cell(
        val x: Int,
        val y: Int,
        val allowPickup: Boolean,
        val isGhost: Boolean,
        val delivery: CloseDelivery?,
    )

    /** One physical inventory index to hand back, and how. */
    data class Release(val index: Int, val delivery: CloseDelivery)

    /**
     * The inventory indices to hand back, in ascending order, one per index.
     *
     * Only cells that opted in ([Cell.delivery] set) AND that the player can take from are kept: a
     * pickup-less cell is a decoration whatever its layout says. The index maths mirrors the render
     * pass (virtual position minus [viewportX]/[viewportY], nine columns), so the cell read is the
     * cell that was drawn; anything outside `0 until inventorySize` was never a real cell.
     * [cells] must already be collapsed to one slot per coordinate (see [SlotOverlay.collapse]).
     */
    fun plan(cells: List<Cell>, viewportX: Int, viewportY: Int, inventorySize: Int): List<Release> {
        // A sorted map rather than sortedBy/distinctBy chains: those inline into synthetic classes,
        // and this runs inside an inventory event where such classes are not reliably resolvable.
        val byIndex = java.util.TreeMap<Int, Release>()
        for (cell in cells) {
            val delivery = cell.delivery ?: continue
            if (!cell.allowPickup || cell.isGhost) continue
            val relX = cell.x - viewportX
            val relY = cell.y - viewportY
            if (relX !in 0 until COLUMNS || relY < 0) continue
            val index = relY * COLUMNS + relX
            if (index !in 0 until inventorySize) continue
            byIndex.putIfAbsent(index, Release(index, delivery))
        }
        return byIndex.values.toList()
    }

    /**
     * Hands [item] to the player. [give] puts it in the inventory and returns what did not fit;
     * [drop] lets one stack fall at the player's feet.
     *
     * Compared with `==` rather than matched with `when`: this runs inside event handlers, where
     * the synthetic classes Kotlin generates for an enum `when` are not reliably resolvable.
     */
    fun <T> deliver(item: T, delivery: CloseDelivery, give: (T) -> List<T>, drop: (T) -> Unit) {
        if (delivery == CloseDelivery.DROP) {
            drop(item)
            return
        }
        give(item).forEach(drop)
    }

    private const val COLUMNS = 9
}
