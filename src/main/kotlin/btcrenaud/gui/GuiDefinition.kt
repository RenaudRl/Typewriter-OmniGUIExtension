package btcrenaud.gui

import net.kyori.adventure.text.Component
import org.bukkit.inventory.MerchantRecipe
import btcrenaud.gui.api.GuiSlot

/**
 * Describes a GUI in a type-safe way. Instead of relying on multiple entries with string identifiers this
 * single data structure is used for every GUI type. Only the relevant values for the selected [GuiType] are
 * read at runtime, allowing callers to provide just the information they need.
 */
data class GuiDefinition(
    val type: GuiType,
    val title: Component? = null,
    /**
     * Optional explicit size. Only required for [GuiType.CUSTOM]; other types use
     * their fixed vanilla dimensions.
     */
    val size: InventorySize? = null,
    val slots: List<GuiSlot> = emptyList(),
    /** Pages for [GuiType.BOOK] */
    val bookPages: List<Component> = emptyList(),
    /** Trades for [GuiType.VILLAGER_TRADE] */
    val villagerTrades: List<MerchantRecipe> = emptyList(),
)

