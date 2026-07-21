package btcrenaud.gui.services

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.plugin.Plugin

/**
 * Handles complex drag and drop interactions in GUIs.
 */
object DragAndDropService : Listener {
    private lateinit var plugin: Plugin

    fun initialize(plugin: Plugin) {
        this.plugin = plugin
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    fun shutdown() {
        org.bukkit.event.HandlerList.unregisterAll(this)
    }

    /**
     * True when [inventory] was created by GuiFactory (it carries a GuiInventoryHolder).
     * Checked via class NAME, not a class literal: Paper's ClassLoader cannot resolve
     * extension classes during event dispatch (same pitfall as StorageGuiSlot in
     * MenuSessionService), so referencing GuiInventoryHolder here would NoClassDefFoundError.
     */
    private fun isGuiInventory(inventory: org.bukkit.inventory.Inventory?): Boolean =
        inventory?.getHolder(false)?.javaClass?.name == "btcrenaud.gui.GuiInventoryHolder"

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return

        // By default, cancel drag if it affects the top inventory and slots are not allowPickup
        val session = MenuSessionService.getSession(player)
        if (session == null) {
            // Fail closed: a menu whose session was dropped is still a menu, not a chest.
            if (isGuiInventory(event.view.topInventory)) {
                event.isCancelled = true
            }
            return
        }
        val slots = session.definition.layout.getSlots(session, session.viewport)

        for (rawSlot in event.rawSlots) {
            if (rawSlot < event.view.topInventory.size) {
                val relSlot = rawSlot
                val relX = relSlot % 9
                val relY = relSlot / 9
                val virtualX = relX + session.viewport.x
                val virtualY = relY + session.viewport.y

                val clickedSlot = slots.find { it.x == virtualX && it.y == virtualY }
                
                if (clickedSlot == null || !clickedSlot.allowPickup) {
                    event.isCancelled = true
                    return
                }
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onMove(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return

        // Merchant/Book GUIs have their own shift-click handling in MenuSessionService.onClick()
        // They use EmptyLayout (no slots with allowPickup), so this handler would incorrectly
        // cancel all shift-clicks before MenuSessionService can process them.
        val session = MenuSessionService.getSession(player)
        if (session == null) {
            // Fail closed: block shift-moves into/out of a menu that lost its session.
            if (isGuiInventory(event.view.topInventory)) {
                event.isCancelled = true
            }
            return
        }
        val guiType = session.definition.type
        if (guiType == btcrenaud.gui.GuiType.VILLAGER_TRADE ||
            guiType == btcrenaud.gui.GuiType.MERCHANT ||
            guiType == btcrenaud.gui.GuiType.BOOK) {
            return
        }

        if (event.isShiftClick) {
            // If clicking in player inventory, it tries to move TO the GUI
            if (event.clickedInventory == event.view.bottomInventory) {
                // Find empty slots in GUI that allow pickup
                val slots = session.definition.layout.getSlots(session, session.viewport)
                val topInventory = event.view.topInventory
                
                // If the top inventory is a custom GUI, we usually want to control where items go.
                // For now, only allow if there's at least one slot that allows pickup and is either empty or matches the item.
                val canFitInGui = (0 until topInventory.size).any { i ->
                    val itemAtSlot = topInventory.getItem(i)
                    val guiSlot = slots.find { it.x == i % 9 + session.viewport.x && it.y == i / 9 + session.viewport.y }
                    guiSlot?.allowPickup == true && (itemAtSlot == null || itemAtSlot.isSimilar(event.currentItem))
                }
                
                if (!canFitInGui) {
                    event.isCancelled = true
                }
            } else if (event.clickedInventory == event.view.topInventory) {
                // If clicking in GUI, it tries to move TO player inventory
                // This is allowed only if the clicked slot in GUI allows pickup
                val slots = session.definition.layout.getSlots(session, session.viewport)
                val relSlot = event.slot
                val clickedSlot = slots.find { 
                    it.x == relSlot % 9 + session.viewport.x && it.y == relSlot / 9 + session.viewport.y
                }
                
                if (clickedSlot == null || !clickedSlot.allowPickup) {
                    event.isCancelled = true
                }
            }
        }
    }
}
