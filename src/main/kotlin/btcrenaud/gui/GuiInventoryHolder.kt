package btcrenaud.gui

import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder

/**
 * Marker holder attached to every inventory this extension creates.
 *
 * Menu protection (click/drag cancellation) used to depend entirely on an active
 * [btcrenaud.gui.services.MenuSessionService.ActiveSession]. Inventories were created with a
 * `null` holder, so a menu was indistinguishable from a real chest once its session was gone —
 * and every handler bailed out with a bare `return`, leaving the still-open menu fully editable:
 * items could be dragged into the player's inventory and buttons stopped responding.
 *
 * Carrying a holder makes a GUI inventory self-identifying and independent of session state, so
 * protection can fail *closed*: anything owned by this extension is locked down by default, and a
 * real container is never mistaken for a menu (nor a menu for a real container on close).
 */
class GuiInventoryHolder(
    /** Id of the menu definition this inventory was opened for, for diagnostics. */
    val menuId: String? = null,
) : InventoryHolder {
    /** Assigned right after creation — Bukkit requires the holder before the inventory exists. */
    internal var backing: Inventory? = null

    override fun getInventory(): Inventory =
        backing ?: error("GuiInventoryHolder used before its inventory was assigned")

    companion object {
        /** True when [inventory] was created by this extension. */
        fun owns(inventory: Inventory?): Boolean = inventory?.holder is GuiInventoryHolder
    }
}
