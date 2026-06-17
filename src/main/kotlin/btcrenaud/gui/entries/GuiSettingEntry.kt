package btcrenaud.gui.entries

import btcrenaud.gui.api.MenuAudioConfig
import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.engine.paper.entry.ManifestEntry
import com.typewritermc.engine.paper.utils.item.Item
import org.bukkit.event.inventory.ClickType

/**
 * Enum mapping each storage action to a Bukkit [ClickType].
 * Used by [StorageInteractionConfig] to make storage click behavior
 * configurable from the gui_settings entry in Tapwriter.
 */
enum class StorageClickType(
    @Help("The Bukkit ClickType this enum value maps to")
    val bukkit: ClickType
) {
    @Help("Left click")
    LEFT(ClickType.LEFT),
    @Help("Right click")
    RIGHT(ClickType.RIGHT),
    @Help("Shift + left click")
    SHIFT_LEFT(ClickType.SHIFT_LEFT),
    @Help("Shift + right click")
    SHIFT_RIGHT(ClickType.SHIFT_RIGHT),
    @Help("Middle click (scroll wheel press)")
    MIDDLE(ClickType.MIDDLE),
    @Help("Double click")
    DOUBLE_CLICK(ClickType.DOUBLE_CLICK),
    @Help("Drop key (Q)")
    DROP(ClickType.DROP),
    @Help("Ctrl + drop key (Ctrl+Q)")
    DROP_ALL(ClickType.CONTROL_DROP),
    @Help("Swap offhand (F)")
    SWAP_OFFHAND(ClickType.SWAP_OFFHAND);
}

/**
 * Defines which [StorageClickType] maps to which storage action.
 * Configured globally via [GuiSettingEntry.storageInteraction].
 *
 * The default values match the traditional storage behavior:
 *   LEFT=placeOne, RIGHT=takeOne, SHIFT_LEFT=placeAll, SHIFT_RIGHT=takeStack,
 *   SWAP_OFFHAND=fillFromInventory, DROP=dropAll.
 */
data class StorageInteractionConfig(
    @Help("Click to place ONE item from cursor into the storage slot")
    val placeOneClick: StorageClickType = StorageClickType.LEFT,
    @Help("Click to place ALL items from cursor into the storage slot")
    val placeAllClick: StorageClickType = StorageClickType.SHIFT_LEFT,
    @Help("Click to take ONE item from the storage slot onto cursor")
    val takeOneClick: StorageClickType = StorageClickType.RIGHT,
    @Help("Click to take ALL items from the storage slot onto cursor")
    val takeAllClick: StorageClickType = StorageClickType.LEFT,
    @Help("Click to take a full STACK (64) from the storage slot into inventory")
    val takeStackClick: StorageClickType = StorageClickType.SHIFT_RIGHT,
    @Help("Click to FILL the storage slot from the player inventory")
    val fillFromInvClick: StorageClickType = StorageClickType.SWAP_OFFHAND,
    @Help("Click to DROP all items from the storage slot onto the ground")
    val dropAllClick: StorageClickType = StorageClickType.DROP,
)

/**
 * Global settings for the GUI Extension.
 * Allows defining default sounds, storage interaction config, and skill tree node connectors.
 */
@Entry("gui_settings", "GUI Global Settings", Colors.BLUE, "fa6-solid:gear")
class GuiSettingEntry(
    override val id: String = "",
    override val name: String = "",

    @Help("Default audio configuration for all GUIs if not overridden.")
    val audioDefaults: MenuAudioConfig = MenuAudioConfig(),

    @Help("Configure which mouse/keyboard clicks trigger each storage action (place, take, fill, drop).")
    val storageInteraction: StorageInteractionConfig = StorageInteractionConfig(),

    @Help("Default Skill Tree node connector items for each state.")
    val nodeDefaults: Map<NodeState, NodeDirectionalMap> = emptyMap()
) : ManifestEntry

/**
 * Represents the state of a skill tree node.
 */
enum class NodeState {
    @Help("Node is already unlocked and completed.")
    UNLOCKED,
    @Help("Node is locked but can be unlocked (requirements met).")
    UNLOCKABLE,
    @Help("Node is locked and requirements are not met.")
    LOCKED,
    @Help("Node is locked and cannot be reached yet.")
    FULLY_LOCKED
}

/**
 * Mapping of directional connectors to items.
 */
data class NodeDirectionalMap(
    @Help("Single node with no connections.")
    val none: Item = Item.Empty,
    
    @Help("Connector towards Up.")
    val up: Item = Item.Empty,
    @Help("Connector towards Down.")
    val down: Item = Item.Empty,
    @Help("Connector towards Left.")
    val left: Item = Item.Empty,
    @Help("Connector towards Right.")
    val right: Item = Item.Empty,

    @Help("Connectors for Up and Down.")
    val upDown: Item = Item.Empty,
    @Help("Connectors for Up and Left.")
    val upLeft: Item = Item.Empty,
    @Help("Connectors for Up and Right.")
    val upRight: Item = Item.Empty,
    @Help("Connectors for Down and Left.")
    val downLeft: Item = Item.Empty,
    @Help("Connectors for Down and Right.")
    val downRight: Item = Item.Empty,
    @Help("Connectors for Left and Right.")
    val leftRight: Item = Item.Empty,

    @Help("Connectors for Up, Down, and Left.")
    val upDownLeft: Item = Item.Empty,
    @Help("Connectors for Up, Down, and Right.")
    val upDownRight: Item = Item.Empty,
    @Help("Connectors for Up, Left, and Right.")
    val upLeftRight: Item = Item.Empty,
    @Help("Connectors for Down, Left, and Right.")
    val downLeftRight: Item = Item.Empty,

    @Help("Connectors for all four directions (Cross).")
    val upDownLeftRight: Item = Item.Empty,

    @Help("Item used for empty/no path slots (background).")
    val noPath: Item = Item.Empty
)
