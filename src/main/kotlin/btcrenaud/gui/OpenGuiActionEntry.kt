package btcrenaud.gui

import com.typewritermc.core.entries.Ref
import com.typewritermc.core.extension.annotations.Colored
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.MultiLine
import com.typewritermc.core.extension.annotations.Placeholder
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.extension.annotations.AlgebraicTypeInfo
import com.typewritermc.core.books.pages.Colors
import com.typewritermc.engine.paper.entry.Criteria
import com.typewritermc.engine.paper.entry.Modifier
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.engine.paper.entry.entries.ActionEntry
import com.typewritermc.engine.paper.entry.entries.ActionTrigger
import com.typewritermc.engine.paper.entry.entries.ConstVar
import com.typewritermc.engine.paper.entry.entries.Var
import com.typewritermc.core.interaction.InteractionContext
import com.typewritermc.engine.paper.entry.matches
import com.typewritermc.engine.paper.extensions.placeholderapi.parsePlaceholders
import com.typewritermc.engine.paper.utils.asMini
import com.typewritermc.engine.paper.utils.asMiniWithResolvers
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.MerchantRecipe
import java.util.logging.Logger

import com.typewritermc.engine.paper.utils.item.Item
import com.typewritermc.engine.paper.utils.item.components.ItemMaterialComponent
import org.bukkit.Material
import btcrenaud.gui.api.MenuAudioConfig
import btcrenaud.gui.api.InteractionType
import btcrenaud.gui.api.Viewport
import com.typewritermc.engine.paper.utils.Sound
import com.typewritermc.core.entries.emptyRef
import btcrenaud.gui.api.StorageGuiSlot
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

/**
 * Single entry used to open a GUI for a player. Unlike the original GUI extensions
 * that required several entries for definitions and item data, this action
 * embeds all required information such as type, title, size and individual slot
 * items. Each item can define a custom name and lore.
 */
@Entry(
    "open_gui",
    "Open a GUI for the player",
    com.typewritermc.core.books.pages.Colors.BLUE,
    "mdi:treasure-chest-outline"
)
class OpenGuiActionEntry(
    override val id: String = "",
    override val name: String = "",
    override val criteria: List<Criteria> = emptyList(),
    override val modifiers: List<Modifier> = emptyList(),
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("Type of GUI to open: CUSTOM (standard inventory), ANVIL, ENCHANTING_TABLE, etc.")
    val guiType: GuiType = GuiType.CUSTOM,
    @Help("Title displayed at the top of the inventory. Supports MiniMessage and PlaceholderAPI.")
    @Placeholder @Colored @MultiLine
    val title: Var<String> = ConstVar(""),
    @Help("Inventory size (only for CUSTOM guiType). Default: 6 rows (54 slots).")
    val size: InventorySize? = null,
    @Help("Collection of layouts available to this menu. Referenced by mainLayoutId and frame layoutId fields.")
    val layoutPool: List<LayoutData> = emptyList(),
    @Help("ID of the layout from layoutPool to use as the main layout. If null, an empty menu is shown.")
    val mainLayoutId: String? = null,
    @Help("Custom audio configuration for this menu (open, close, scroll, click sounds).")
    val audio: GuiAudioData = GuiAudioData(),
    @Help("Entry id of a template menu to inherit from: its layoutPool is merged under this one (same ids override), and mainLayoutId/size are used as fallbacks.")
    val baseMenuId: String = "",
    @Help("Re-render the menu every N ticks while open (0 = disabled). For live counters/timers.")
    val autoRefreshTicks: Long = 0,
) : ActionEntry {
    override fun ActionTrigger.execute() {
        val rawTitleString: String? = title.get(player, context)
            .takeIf { it.isNotEmpty() }
            ?.parsePlaceholders(player)

        val componentTitle: Component? = rawTitleString
            ?.asMiniCE()

        // Template inheritance: the base menu's pool merges UNDER ours —
        // a child layout with the same id overrides the template's version.
        val base = baseMenuId.takeIf { it.isNotBlank() }
            ?.let { Ref(it, OpenGuiActionEntry::class).get() }
        val pool = ((base?.layoutPool ?: emptyList()) + layoutPool)
            .filterNotNull()
            .associateBy { it.id }
        val effectiveMainLayoutId = mainLayoutId ?: base?.mainLayoutId
        val mainLayout = effectiveMainLayoutId?.let { pool[it] }

        val resolvedSize = when (guiType) {
            GuiType.CUSTOM -> size ?: base?.size ?: InventorySize.SIZE_54 // default to 6 rows (54 slots)
            else -> null
        }
        val totalSize = resolvedSize?.slots ?: guiType.inventoryType?.defaultSize ?: 54

        // Handle Specialized Layouts (Book/Merchant)
        if (mainLayout is BookLayoutData) {
            // Book GUIs do not use sessions — openBook() does not fire InventoryCloseEvent,
            // so sessions would never be cleaned up. Close the current session (if any)
            // and open the book directly.
            val currentSession = btcrenaud.gui.services.MenuSessionService.getSession(player)
            if (currentSession != null) {
                if (org.bukkit.Bukkit.isPrimaryThread()) {
                    player.closeInventory()
                } else {
                    val plugin = org.bukkit.Bukkit.getPluginManager().getPlugin("Typewriter")
                    if (plugin != null) {
                        org.bukkit.Bukkit.getScheduler().runTask(plugin, java.lang.Runnable { player.closeInventory() })
                    }
                }
            }
            val legacyDef = btcrenaud.gui.GuiDefinition(
                type = GuiType.BOOK,
                title = componentTitle,
                bookPages = mainLayout.pages.map { it.get(player, context).parsePlaceholders(player).asMiniCE() }
            )
            GuiFactory.open(player, legacyDef)
            return
        }
        
        if (mainLayout is MerchantLayoutData) {
            val definition = btcrenaud.gui.api.MenuDefinition(
                id = id,
                type = GuiType.VILLAGER_TRADE,
                title = componentTitle,
                rawTitle = rawTitleString,
                layout = btcrenaud.gui.api.EmptyLayout
            )
            btcrenaud.gui.services.MenuSessionService.register(player, definition)
            val legacyDef = btcrenaud.gui.GuiDefinition(
                type = GuiType.VILLAGER_TRADE,
                title = componentTitle,
                villagerTrades = mainLayout.trades
                    .filter { it.criteria.matches(player, context) }
                    .mapNotNull { it.toRecipe(player, context) }
            )
            GuiFactory.open(player, legacyDef)
            return
        }

        // Final layout resolution
        val finalLayout: btcrenaud.gui.api.MenuLayout = mainLayout?.let {
            btcrenaud.gui.api.LayoutParser.parse(player, context, guiType, totalSize, pool, it)
        } ?: btcrenaud.gui.api.EmptyLayout

        // Menu states (_gui_states): when present, wrap the layout so per-player
        // conditions and LayerOverrides apply at render time.
        val states = btcrenaud.gui.services.MenuStateService.getStates(id)
        val statefulLayout = if (states.isEmpty()) {
            finalLayout
        } else {
            btcrenaud.gui.editor.states.StateAwareLayout(id, id, states, finalLayout)
        }

        val definition = btcrenaud.gui.api.MenuDefinition(
            id = id,
            type = guiType,
            title = componentTitle,
            rawTitle = rawTitleString,
            size = resolvedSize,
            layout = statefulLayout,
            audio = btcrenaud.gui.api.MenuAudioConfig(
                onOpen = audio.onOpen,
                onClose = audio.onClose,
                onScroll = audio.onScroll,
                onClick = audio.onClick
            )
        )

        btcrenaud.gui.services.MenuSessionService.register(player, definition, autoRefreshTicks = autoRefreshTicks)
    }
}

/** Data for a single GUI slot. */
enum class Direction { right, left, down, up }

@Serializable
data class GuiItemData(
    @Help("The item to display. Leave empty to use the item held in the player's hand.")
    val item: Var<Item> = ConstVar(Item.Empty),
    @Help("Custom display name for this item. Supports MiniMessage and PlaceholderAPI.")
    val displayName: Var<String>? = null,
    @Help("Custom lore lines for this item. Supports MiniMessage and PlaceholderAPI.")
    val lore: List<Var<String>> = emptyList(),
    @Help("Criteria to determine if this slot should be visible to the player.")
    val criteria: List<Criteria> = emptyList(),
    @Help("If true, the player can pick up this item from the inventory.")
    val allowPickup: Boolean = false,
    @Help("Modifiers applied when this slot is rendered.")
    val modifiers: List<Modifier> = emptyList(),
    @Help("Triggers executed when this slot is clicked (regardless of interaction type).")
    val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("Per-click-type interactions (commands and triggers for LEFT, RIGHT, SHIFT, etc.). Use this instead of the legacy 'commands' field.")
    val interactionList: List<InteractionData> = emptyList(),
    @Help("Input dialog configuration. When set, clicking this slot opens an input prompt.")
    val input: InputData? = null,
    @Help("Persistent storage configuration. When set, this slot becomes a storage slot backed by a gui_storage artifact.")
    val storage: StorageSlotData? = null,
    @Help("If true, the item appears as a ghost (not collectable, same as allowPickup=false but semantically different).")
    val isGhost: Boolean = false,
    @Help("Animation applied to this slot when the menu opens.")
    val animation: SlotAnimationData? = null,
    @Help("Cooldown in ticks before this slot can be clicked again.")
    val cooldownTicks: Long = 0,
    @Help("Button type tag for button resolvers (e.g. 'next_page', 'close', 'SHOP_ITEM'). The configured item visuals are preserved. Pair with buttonPrefix for extension-specific buttons.")
    val buttonType: String? = null,
    @Help("Prefix prepended to button type tag. Default: 'dungeon_button:'. Set to e.g. 'shop_button:' or 'codex_button:' for other extensions.")
    val buttonPrefix: String? = null,
    @Help("Permission required to SEE this slot (null = everyone).")
    val viewPermission: String? = null,
    @Help("Permission required to INTERACT with this slot; without it the slot renders but is inert.")
    val clickPermission: String? = null,
    @Help("Starting X position (column) of this slot. 0 = leftmost.")
    val x: Int = 0,
    @Help("Starting Y position (row) of this slot. 0 = top row.")
    val y: Int = 0,
    @Help("Number of consecutive slots to create when direction is set.")
    val count: Int = 1,
    @Help("Direction in which to repeat this slot (right, left, down, up). Leave null for a single slot at (x, y).")
    val direction: Direction? = null,
    @Help("Gap in slots between repeated items. A gap of 1 means adjacent slots.")
    val gap: Int = 1,
    @Help("Number of rows to repeat in the secondary direction.")
    val repeatY: Int = 1,
) {
    fun toSlot(player: Player, context: InteractionContext, guiType: GuiType, width: Int = 9): List<btcrenaud.gui.api.GuiSlot> {
        return GuiSlotBuilder.build(player, context, guiType, width, this)
    }
}


/**
 * Builder/factory that converts GuiItemData entries into GuiSlot instances.
 * Extracted from the GuiItemData god object to keep the data class focused on configuration.
 */
object GuiSlotBuilder {
    /**
     * Expands an item's repetition settings into the positions it occupies.
     *
     * This is the canonical repetition semantics for the whole ecosystem — `gap` is a step
     * multiplier (1 = adjacent), `count` repeats along [GuiItemData.direction] and `repeatY`
     * adds rows perpendicular to it. Extensions that place their own markers (Shops'
     * `SHOP_ITEM`, QuestCodex's `QUEST_SLOT`/`CATEGORY_SLOT`) MUST call this instead of
     * reimplementing the maths: divergent copies are what made tagged markers refuse to spread.
     *
     * `count`/`repeatY` are coerced to at least 1 because the editor serializes an unset value
     * as `0`, and `0 until 0` would silently drop the item entirely.
     */
    fun expandPositions(data: GuiItemData): List<Pair<Int, Int>> {
        // No direction = single position at (x, y), no repetition.
        val direction = data.direction ?: return listOf(data.x to data.y)
        val positions = mutableListOf<Pair<Int, Int>>()
        for (ry in 0 until data.repeatY.coerceAtLeast(1)) {
            for (rc in 0 until data.count.coerceAtLeast(1)) {
                val px: Int
                val py: Int
                when (direction) {
                    Direction.right -> {
                        px = data.x + rc * data.gap  // rc shifts in x
                        py = data.y + ry * data.gap  // ry shifts in y
                    }
                    Direction.left -> {
                        px = data.x - rc * data.gap
                        py = data.y + ry * data.gap
                    }
                    Direction.down -> {
                        px = data.x + ry * data.gap  // ry shifts in x
                        py = data.y + rc * data.gap  // rc shifts in y
                    }
                    Direction.up -> {
                        px = data.x + ry * data.gap
                        py = data.y - rc * data.gap
                    }
                }
                positions.add(px to py)
            }
        }
        return positions
    }

    fun build(
        player: Player,
        context: InteractionContext,
        guiType: GuiType,
        width: Int,
        data: GuiItemData,
    ): List<btcrenaud.gui.api.GuiSlot> {
        if (!data.criteria.matches(player, context)) return emptyList()
        if (data.viewPermission != null && !player.hasPermission(data.viewPermission)) return emptyList()
        val canClick = data.clickPermission == null || player.hasPermission(data.clickPermission)
        
        // Handle tagged button types — build the user-configured slot with a tag so resolvers can
        // replace the interactions while preserving the configured visual (item, name, lore).
        // An empty string is NOT a button type: decorative items serialize buttonType as "" and must
        // fall through to the normal (repeatable) path — otherwise they collapse to a single slot.
        val effectiveButtonType = data.buttonType?.takeIf { it.isNotEmpty() }
        if (effectiveButtonType != null) {
            // Build the actual item from user configuration instead of STRUCTURE_VOID placeholder
            val resolved = data.item.get(player, context)
            val stack = if (resolved == Item.Empty) {
                org.bukkit.inventory.ItemStack(org.bukkit.Material.STRUCTURE_VOID)
            } else {
                resolved.build(player, context).clone()
            }
            val meta = stack.itemMeta
            if (meta != null) {
                data.displayName?.get(player, context)?.let {
                    meta.displayName(it.parsePlaceholders(player).asMiniItem())
                }
                data.lore.map { it.get(player, context).parsePlaceholders(player).asMiniItem() }
                    .takeIf { it.isNotEmpty() }
                    ?.let { meta.lore(it) }
                stack.itemMeta = meta
            }
            val prefix = data.buttonPrefix ?: "dungeon_button:"
            return listOf(btcrenaud.gui.api.GuiSlot(
                x = data.x,
                y = data.y,
                item = stack,
                allowPickup = false,
                tag = "$prefix$effectiveButtonType",
                triggers = data.triggers,
                modifiers = data.modifiers
            ))
        }
        
        val resolved = data.item.get(player, context)
        val stack = if (resolved == Item.Empty) {
            player.inventory.itemInMainHand.clone()
        } else {
            resolved.build(player, context)
        }
        val meta = stack.itemMeta
        if (meta != null) {
            data.displayName?.get(player, context)?.let {
                meta.displayName(it.parsePlaceholders(player).asMiniItem())
            }
            data.lore.map { it.get(player, context).parsePlaceholders(player).asMiniItem() }
                .takeIf { it.isNotEmpty() }
                ?.let { meta.lore(it) }
            stack.itemMeta = meta
        }

        val allInteractions = data.interactionList.associate { interactionData ->
            val cmds = interactionData.commands.map { it.get(player, context).parsePlaceholders(player) }.toMutableList()
            if (interactionData.closeMenu) cmds.add("gui:close")
            if (interactionData.executeReturn) cmds.add("gui:back")
            interactionData.type to btcrenaud.gui.api.GuiSlotInteraction(
                commands = cmds.toList(),
                triggers = interactionData.triggers
            )
        }

        val positions = expandPositions(data)

        return positions.map { (px, py) ->
            val baseSlot = btcrenaud.gui.api.GuiSlot(
                x = px,
                y = py,
                item = stack.clone(),
                triggers = if (canClick) data.triggers else emptyList(),
                modifiers = if (canClick) data.modifiers else emptyList(),
                allowPickup = data.allowPickup && canClick,
                isGhost = data.isGhost && canClick,
                interactions = if (canClick) allInteractions else emptyMap(),
                input = if (canClick) data.input else null,
                animation = data.animation?.let { anim ->
                    btcrenaud.gui.api.SlotAnimation(anim.targetX, anim.targetY, anim.duration, anim.easing)
                },
                cooldownTicks = data.cooldownTicks
            )
            if (data.storage != null) {
                val storage = data.storage
                val entry = storage.entry.get() ?: return@map baseSlot
                val groupEntry = storage.group.get()
                val groupKey = groupEntry?.groupId(player)?.id ?: player.uniqueId.toString()
                val placeholderStack = storage.placeholder?.get(player, context)?.build(player, context) ?: org.bukkit.inventory.ItemStack(org.bukkit.Material.AIR)
                val requiredStack = storage.requiredItem?.get(player, context)?.build(player, context)
                StorageGuiSlot(
                    x = px,
                    y = py,
                    item = stack.clone(),
                    entry = entry,
                    groupKey = groupKey,
                    slotIndex = py * 9 + px,
                    maxStack = storage.maxAmount,
                    temporary = storage.temporary,
                    temporaryTriggers = storage.temporaryTriggers,
                    onFill = storage.onFill,
                    onEmpty = storage.onEmpty,
                    placeholder = placeholderStack,
                    requiredItem = requiredStack,
                    requiredAmount = storage.requiredAmount,
                    onReachRequired = storage.onReachRequired,
                    consumeItems = storage.consumeOnReach,
                    forceStorage = storage.forceStorage,
                    accumulated = 0,
                    allowPickup = data.allowPickup,
                    isGhost = data.isGhost,
                    commands = emptyList(), // Commands are on interactions
                    triggers = data.triggers,
                    modifiers = data.modifiers,
                    interactions = allInteractions,
                    input = data.input,
                    storage = data.storage,
                    animation = data.animation?.let { anim ->
                        btcrenaud.gui.api.SlotAnimation(anim.targetX, anim.targetY, anim.duration, anim.easing)
                    },
                    cooldownTicks = data.cooldownTicks,
                    tag = null
                )
            } else {
                baseSlot
            }
        }
    }
}


private fun mapInteractionKey(key: String): btcrenaud.gui.api.InteractionType {
    val LOGGER = Logger.getLogger("Typewriter-GUIExtension")
    val upper = key.uppercase()
    return when (upper) {
        "LEFT" -> btcrenaud.gui.api.InteractionType.LEFT_CLICK
        "RIGHT" -> btcrenaud.gui.api.InteractionType.RIGHT_CLICK
        "SHIFT_LEFT" -> btcrenaud.gui.api.InteractionType.SHIFT_LEFT_CLICK
        "SHIFT_RIGHT" -> btcrenaud.gui.api.InteractionType.SHIFT_RIGHT_CLICK
        "MIDDLE" -> btcrenaud.gui.api.InteractionType.MIDDLE_CLICK
        "DOUBLE" -> btcrenaud.gui.api.InteractionType.DOUBLE_CLICK
        "KEY_F", "F" -> btcrenaud.gui.api.InteractionType.SWAP_OFFHAND
        "KEY_Q", "Q" -> btcrenaud.gui.api.InteractionType.DROP
        "KEY_1", "1" -> btcrenaud.gui.api.InteractionType.NUMBER_KEY_1
        "KEY_2", "2" -> btcrenaud.gui.api.InteractionType.NUMBER_KEY_2
        "KEY_3", "3" -> btcrenaud.gui.api.InteractionType.NUMBER_KEY_3
        "KEY_4", "4" -> btcrenaud.gui.api.InteractionType.NUMBER_KEY_4
        "KEY_5", "5" -> btcrenaud.gui.api.InteractionType.NUMBER_KEY_5
        "KEY_6", "6" -> btcrenaud.gui.api.InteractionType.NUMBER_KEY_6
        "KEY_7", "7" -> btcrenaud.gui.api.InteractionType.NUMBER_KEY_7
        "KEY_8", "8" -> btcrenaud.gui.api.InteractionType.NUMBER_KEY_8
        "KEY_9", "9" -> btcrenaud.gui.api.InteractionType.NUMBER_KEY_9
        else -> {
            val result = runCatching { btcrenaud.gui.api.InteractionType.valueOf(upper) }.getOrNull()
            if (result == null) {
                LOGGER.warning("Unknown interaction key '$key' -- falling back to LEFT_CLICK")
                btcrenaud.gui.api.InteractionType.LEFT_CLICK
            } else result
        }
    }
}

data class GuiAudioData(
    @Help("Sound played when the GUI opens.")
    val onOpen: Sound? = null,
    @Help("Sound played when the GUI closes.")
    val onClose: Sound? = null,
    @Help("Sound played when scrolling through pages or slots.")
    val onScroll: Sound? = null,
    @Help("Sound played when clicking any slot.")
    val onClick: Sound? = null
)

/** Per-click-type interaction configuration. Each entry maps a click type to commands and triggers. */
@Serializable
data class InteractionData(
    @Help("Click type that triggers this interaction.")
    val type: InteractionType = InteractionType.LEFT_CLICK,
    @Help("Commands executed when this interaction fires. Supports PlaceholderAPI.")
    val commands: List<Var<String>> = emptyList(),
    @Help("Triggers executed when this interaction fires.")
    val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("If true, the menu closes after executing this interaction.")
    val closeMenu: Boolean = false,
    @Help("If true, the previous menu (gui:back) is opened after executing this interaction.")
    val executeReturn: Boolean = false
)

/** Input dialog configuration. Opens a sign-like dialog when the slot is clicked. */
@Serializable
data class InputData(
    @Help("If true, the input dialog is enabled for this slot.")
    val enabled: Boolean = true,
    @Help("Title of the input dialog.")
    val title: Var<String> = ConstVar("Enter Value"),
    @Help("Placeholder text shown in the input field.")
    val placeholder: Var<String> = ConstVar("Value"),
    @Help("Name of the variable to store the input result into.")
    val targetVar: String = "",
    @Help("Commands executed after input is submitted. Use %value% to reference the player's input.")
    val onInputCommands: List<Var<String>> = emptyList(),
    @Help("Triggers executed after input is submitted.")
    val onInputTriggers: List<Ref<TriggerableEntry>> = emptyList()
)
/** Configuration for a slot animation trigger when the menu opens. */
@Serializable
data class SlotAnimationData(
    @Help("Starting X position for the slot animation.")
    val targetX: Int = 0,
    @Help("Target Y position for the slot animation.")
    val targetY: Int = 0,
    @Help("Duration of the animation in milliseconds.")
    val duration: Long = 500,
    @Help("Easing function for the animation (linear, ease_in, ease_out, ease_in_out).")
    val easing: String = "linear"
)

/** Advanced layout definitions. */
@Serializable
sealed interface LayoutData {
    val id: String
}

/** A simple list of items with optional filling. This is the most common layout type. */
@Serializable
@SerialName("simple")
@AlgebraicTypeInfo("simple", com.typewritermc.core.books.pages.Colors.BLUE, "mdi:grid")
data class SimpleLayoutData(
    @Help("Unique identifier for this layout. Used by mainLayoutId and frame references.")
    override val id: String = "",
    @Help("List of items to display in this layout.")
    val items: List<GuiItemData> = emptyList()
) : LayoutData

/** A layout that supports multiple pages of items. */
@Serializable
@SerialName("paginated")
@AlgebraicTypeInfo("paginated", com.typewritermc.core.books.pages.Colors.ORANGE, "mdi:book-open-page-variant")
data class PaginatedLayoutData(
    @Help("Unique identifier for this layout.")
    override val id: String = "",
    @Help("Number of items per page. Default: 45 (5 rows of 9).")
    val itemsPerPage: Int = 45,
    @Help("Custom slot indices for paginated items. When empty, items fill top-to-bottom left-to-right.")
    val slots: List<Int> = emptyList(),
    @Help("Items to paginate across multiple pages.")
    val items: List<GuiItemData> = emptyList(),
    @Help("Next-page navigation button. Shown on every page except the last.")
    val nextPage: ScrollButtonData? = null,
    @Help("Previous-page navigation button. Shown on every page except the first.")
    val previousPage: ScrollButtonData? = null,
    @Help("Back navigation button. Always visible.")
    val backButton: ScrollButtonData? = null
) : LayoutData

/** A layout that can be scrolled if the content exceeds the viewport. */
@Serializable
@SerialName("scrollable")
@AlgebraicTypeInfo("scrollable", com.typewritermc.core.books.pages.Colors.GREEN, "mdi:mouse-scroll-wheel")
data class ScrollableLayoutData(
    @Help("Unique identifier for this layout.")
    override val id: String = "",
    @Help("Identifier of the inner layout to scroll. Usually references another layout in the pool.")
    val innerId: String? = null,
    @Help("Virtual width of the scrollable area. Default: inventory width.")
    val virtualWidth: Int? = null,
    @Help("Virtual height of the scrollable area. Default: inventory height.")
    val virtualHeight: Int? = null,
    @Help("Scroll navigation buttons (up, down, left, right arrows). These are fixed outside the scrollable viewport.")
    val buttons: List<ScrollButtonData> = emptyList(),
    @Help("If true, default arrow buttons are added automatically. Set to false when using custom buttons.")
    val showDefaultButtons: Boolean = true
) : LayoutData

/** Configuration for a scroll navigation button. */
@Serializable
data class ScrollButtonData(
    @Help("The item that acts as a scroll button (arrow, head, etc.).")
    val item: GuiItemData = GuiItemData(),
    @Help("Direction to scroll when clicked.")
    val direction: ScrollDirection = ScrollDirection.UP,
    @Help("Number of slots/lines to scroll per click.")
    val step: Int = 1
)

enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }

/** A layout composed of multiple independent regions (frames), each with its own scrollable content. */
@Serializable
@SerialName("frame")
@AlgebraicTypeInfo("frame", com.typewritermc.core.books.pages.Colors.PURPLE, "mdi:view-quilt")
data class FrameLayoutData(
    @Help("Unique identifier for this layout.")
    override val id: String = "",
    @Help("List of frames defining independent regions within the inventory.")
    val frames: List<FrameData> = emptyList()
) : LayoutData

/** A layout specifically for Books. Displays pages of formatted text. */
@Serializable
@SerialName("book")
@AlgebraicTypeInfo("book", com.typewritermc.core.books.pages.Colors.BLUE, "mdi:book-open-variant")
data class BookLayoutData(
    @Help("Unique identifier for this layout.")
    override val id: String = "",
    @Help("Text pages of the book. Each string becomes a separate page. Supports MiniMessage and PlaceholderAPI.")
    val pages: List<Var<String>> = emptyList()
) : LayoutData

/** A layout specifically for Merchant/Villager trades. */
@Serializable
@SerialName("merchant")
@AlgebraicTypeInfo("merchant", com.typewritermc.core.books.pages.Colors.GREEN, "mdi:store")
data class MerchantLayoutData(
    @Help("Unique identifier for this layout.")
    override val id: String = "",
    @Help("List of trade offers displayed in the merchant GUI.")
    val trades: List<TradeData> = emptyList()
) : LayoutData

/** A layout that provides persistent item storage slots backed by a GuiStorageEntry artifact. */
@Serializable
@SerialName("storage")
@AlgebraicTypeInfo("storage", com.typewritermc.core.books.pages.Colors.GREEN, "fa6-solid:box-open")
data class StorageLayoutData(
    @Help("Unique identifier for this layout.")
    override val id: String = "",
    @Help("Reference to a gui_storage artifact entry that persists the stored items.")
    val entry: Ref<btcrenaud.gui.entries.GuiStorageEntry> = emptyRef(),
    @Help("Group key for storage scope. Use %player_uuid% for per-player, or a group reference for shared storage.")
    val groupKey: Var<String> = ConstVar("%player_uuid%"),
    @Help("List of storage slot definitions (position, maxStack, triggers, etc.).")
    val slots: List<StorageSlotLayoutItemData> = emptyList()
) : LayoutData

@Serializable
data class StorageSlotLayoutItemData(
    @Help("X position (column) of this storage slot. 0 = leftmost.")
    val x: Int = 0,
    @Help("Y position (row) of this storage slot. 0 = top row.")
    val y: Int = 0,
    @Help("Maximum item amount allowed in this slot (1-64).")
    val maxStack: Int = 64,
    @Help("If true, the slot content is lost when the menu closes.")
    val temporary: Boolean = false,
    @Help("Placeholder item shown when the slot is empty.")
    val placeholder: Var<Item>? = null,
    @Help("Triggers executed when the slot becomes non-empty.")
    val onFill: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("Triggers executed when the slot becomes empty.")
    val onEmpty: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("If set, only items matching this type can be stored in this slot.")
    val requiredItem: Var<Item>? = null,
    @Help("Number of items that must be deposited to trigger onReachRequired.")
    val requiredAmount: Int = 0,
    @Help("Triggers executed when the required amount is reached.")
    val onReachRequired: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("If true, deposited items are consumed when requiredAmount is reached.")
    val consumeItems: Boolean = true
)

/** Storage slot configuration linked to a [GuiStorageEntry] artifact.
 *
 * When a [GuiItemData] has its `storage` field set, the slot becomes
 * a persistent storage slot backed by the referenced artifact.
 * Click handling is delegated to [GuiStorageService].
 */
@Serializable
data class StorageSlotData(
    @Help("Reference to a gui_storage artifact entry")
    val entry: Ref<btcrenaud.gui.entries.GuiStorageEntry> = emptyRef(),
    @Help("Group that determines the storage scope. Leave empty for per-player storage. Use a group entry (e.g. island group) for shared storage.")
    val group: Ref<com.typewritermc.engine.paper.entry.entries.GroupEntry> = emptyRef(),
    @Help("Maximum item amount allowed in this slot (1-64)")
    val maxAmount: Int = 64,
    @Help("If true, the slot content is lost when the menu closes")
    val temporary: Boolean = false,
    @Help("Triggers executed when the menu closes if the slot was filled and temporary is true")
    val temporaryTriggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("Placeholder item shown when the slot is empty")
    val placeholder: Var<Item>? = null,
    @Help("If set, only items matching this type can be stored in this slot")
    val requiredItem: Var<Item>? = null,
    @Help("Number of items that must be deposited to trigger onReachRequired. Requires requiredItem.")
    val requiredAmount: Int = 0,
    @Help("Triggers executed when the required amount is reached")
    val onReachRequired: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("If true, deposited items are consumed when requiredAmount is reached")
    val consumeOnReach: Boolean = true,
    @Help("If true, non-stackable items (swords, tools) can be stored in this slot")
    val forceStorage: Boolean = true,
    @Help("Triggers executed when the slot becomes non-empty")
    val onFill: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("Triggers executed when the slot becomes empty")
    val onEmpty: List<Ref<TriggerableEntry>> = emptyList()
)

/** A single trade offer for a Merchant/Villager layout. */
@Serializable
data class TradeData(
    @Help("The item the player receives from this trade.")
    val result: Var<Item> = ConstVar(Item.Empty),
    @Help("First cost item the player must provide.")
    val costOne: Var<Item> = ConstVar(Item.Empty),
    @Help("Optional second cost item.")
    val costTwo: Var<Item>? = null,
    @Help("Maximum number of uses before the trade locks. Set to 999999 for unlimited.")
    val maxUses: Int = 1,
    @Help("If true, the player receives experience when completing this trade.")
    val experienceReward: Boolean = true,
    @Help("Amount of villager experience gained from this trade.")
    val villagerExperience: Int = 0,
    @Help("Price multiplier applied to this trade after each use (0.0 = no change).")
    val priceMultiplier: Float = 0.0f,
    @Help("Criteria to determine if this trade should be visible to the player.")
    val criteria: List<Criteria> = emptyList()
) {
    fun toRecipe(player: Player, context: InteractionContext): MerchantRecipe? {
        val resultStack = result.get(player, context).let {
            if (it == Item.Empty) player.inventory.itemInMainHand.clone() else it.build(player, context)
        }
        if (resultStack.type.isAir) return null

        val first = costOne.get(player, context).let {
            if (it == Item.Empty) player.inventory.itemInMainHand.clone() else it.build(player, context)
        }
        if (first.type.isAir) return null

        val recipe = MerchantRecipe(resultStack, 0, maxUses, experienceReward, villagerExperience, priceMultiplier)
        recipe.addIngredient(first)

        costTwo?.let { secondVar ->
            val second = secondVar.get(player, context).let {
                if (it == Item.Empty) player.inventory.itemInMainHand.clone() else it.build(player, context)
            }
            if (!second.type.isAir) {
                recipe.addIngredient(second)
            }
        }

        return recipe
    }
}

/** A single frame (independent region) within a FrameLayout. Each frame has its own scrollable content. */
@Serializable
data class FrameData(
    @Help("Unique identifier for this frame.")
    val id: String = "",
    @Help("Starting X position (column) of this frame.")
    val x: Int = 0,
    @Help("Starting Y position (row) of this frame.")
    val y: Int = 0,
    @Help("Width of this frame in slots.")
    val width: Int = 9,
    @Help("Height of this frame in slots.")
    val height: Int = 1,
    @Help("Optional reference to another layout in the pool that provides this frame's content.")
    val layoutId: String? = null
)

private object CraftEngineResolvers {
    private val resolvers: Array<net.kyori.adventure.text.minimessage.tag.resolver.TagResolver> by lazy {
        try {
            val cePlugin = org.bukkit.Bukkit.getPluginManager().getPlugin("CraftEngine") ?: return@lazy emptyArray()
            val cl = cePlugin.javaClass.classLoader
            val imageTag = cl.loadClass("net.momirealms.craftengine.core.plugin.text.minimessage.ImageTag")
                .getField("INSTANCE").get(null) as net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
            val shiftTag = cl.loadClass("net.momirealms.craftengine.core.plugin.text.minimessage.ShiftTag")
                .getField("INSTANCE").get(null) as net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
            arrayOf(imageTag, shiftTag)
        } catch (_: Exception) {
            Logger.getLogger("Typewriter-GUIExtension").warning(
                "Failed to resolve CraftEngine tags for MiniMessage. Image/Shift tags will be unavailable. " +
                "Ensure CraftEngine is installed and up to date."
            )
            emptyArray()
        }
    }

    fun get(): Array<net.kyori.adventure.text.minimessage.tag.resolver.TagResolver> = resolvers
}

private fun String.asMiniCE(): net.kyori.adventure.text.Component {
    val resolvers = CraftEngineResolvers.get()
    return if (resolvers.isNotEmpty()) asMiniWithResolvers(*resolvers) else asMini()
}

/**
 * Item name / lore variant of [asMiniCE]. Minecraft forces custom item text to italic by
 * default; here we render it upright by default so menus look clean. Authors who *want*
 * italic simply add the `<italic>` MiniMessage tag, which wins over this root default.
 */
private fun String.asMiniItem(): net.kyori.adventure.text.Component =
    asMiniCE().decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)
