package btcrenaud.gui

import org.bukkit.event.inventory.InventoryType

/**
 * Enumerates the supported GUI types. Each type optionally exposes the corresponding Bukkit
 * [InventoryType] when the size is fixed. Types without an inventory type require an explicit
 * size to be supplied in their definition.
 *
 * [widthPx] is the pixel width of the inventory GUI background bitmap, used by
 * [btcrenaud.gui.services.TitleCompiler] to compute the cursor-reset offset after drawing
 * the background texture.  176 px is the standard chest/generic 9-wide layout.
 */
enum class GuiType(
    val inventoryType: InventoryType? = null,
    val widthPx: Int = 176
) {
    ANVIL(InventoryType.ANVIL, widthPx = 176),
    BARREL(InventoryType.BARREL, widthPx = 176),
    BEACON(InventoryType.BEACON, widthPx = 176),
    BLAST_FURNACE(InventoryType.BLAST_FURNACE, widthPx = 176),
    BOOK(widthPx = 0),                  // book has no pixel background
    BREWING_STAND(InventoryType.BREWING, widthPx = 176),
    CARTOGRAPHY_TABLE(InventoryType.CARTOGRAPHY, widthPx = 176),
    CRAFTING_TABLE(InventoryType.WORKBENCH, widthPx = 176),
    DISPENSER(InventoryType.DISPENSER, widthPx = 176),
    DROPPER(InventoryType.DROPPER, widthPx = 176),
    ENCHANTING_TABLE(InventoryType.ENCHANTING, widthPx = 176),
    ENDER_CHEST(InventoryType.ENDER_CHEST, widthPx = 176),
    FURNACE(InventoryType.FURNACE, widthPx = 176),
    GRINDSTONE(InventoryType.GRINDSTONE, widthPx = 176),
    HOPPER(InventoryType.HOPPER, widthPx = 176),
    LOOM(InventoryType.LOOM, widthPx = 176),
    MERCHANT(InventoryType.MERCHANT, widthPx = 300), // Merchant trade UI is wider
    SHULKER_BOX(InventoryType.SHULKER_BOX, widthPx = 176),
    SMITHING_TABLE(InventoryType.SMITHING, widthPx = 176),
    SMOKER(InventoryType.SMOKER, widthPx = 176),
    STONECUTTER(InventoryType.STONECUTTER, widthPx = 176),
    VILLAGER_TRADE(InventoryType.MERCHANT, widthPx = 300),
    CHEST(InventoryType.CHEST, widthPx = 176),
    CUSTOM(widthPx = 176)
}
