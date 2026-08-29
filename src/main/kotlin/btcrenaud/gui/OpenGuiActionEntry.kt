package btcrenaud.gui

import com.typewritermc.core.entries.Ref
import com.typewritermc.core.extension.annotations.Colored
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.MultiLine
import com.typewritermc.core.extension.annotations.Placeholder
import com.typewritermc.core.extension.annotations.Tags
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
import btcrenaud.gui.api.permits
import btcrenaud.gui.api.Viewport
import btcrenaud.gui.api.StorageGuiSlot
import com.typewritermc.engine.paper.utils.Sound
import com.typewritermc.core.entries.emptyRef
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
@Tags("gui", "gui_menu")
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
    @Help("Reusable storage configurations referenced by GUI items through storageId.")
    val storagePool: List<StorageSlotData> = emptyList(),
    @Help("ID of the layout from layoutPool to use as the main layout. If null, an empty menu is shown.")
    val mainLayoutId: String? = null,
    @Help("Custom audio configuration for this menu (open, close, scroll, click sounds).")
    val audio: GuiAudioData = GuiAudioData(),
    @Help("Entry id of a template menu to inherit from: its layoutPool is merged under this one (same ids override), and mainLayoutId/size are used as fallbacks.")
    val baseMenuId: String = "",
    @Help("Re-render the menu every N ticks while open (0 = disabled). For live counters/timers.")
    val autoRefreshTicks: Long = 0,
    @Help("Addressable screens of this menu. Frames with layoutId '@view' and slots tagged 'view:<id>' resolve against the active one.")
    val views: List<btcrenaud.gui.api.MenuViewData> = emptyList(),
    @Help("View opened first. Empty = the first declared view.")
    val defaultViewId: String? = null,
    @Help("MiniMessage separator inserted between breadcrumb segments by the {breadcrumb} title token.")
    @Colored
    val breadcrumbSeparator: String = btcrenaud.gui.api.MenuViewSupport.DEFAULT_BREADCRUMB_SEPARATOR,
    @Help("Push the previous view onto the back stack when switching tabs.")
    val pushHistoryOnViewSwitch: Boolean = false,
    @Help("When true, the player's own inventory (27 slots + 9 hotbar) is merged into the menu display. Purely visual — items stay in the real inventory. Only applicable to CUSTOM guiType with 6 rows.")
    val extendToPlayerInventory: Boolean = false,
) : ActionEntry {
    override fun ActionTrigger.execute() {
        openFor(player, context, targetViewId = null, pushHistory = true)
    }

    /** Rebuilds the menu when a view switch changes the inventory chrome/title. */
    fun openFor(
        player: Player,
        context: InteractionContext,
        targetViewId: String?,
        pushHistory: Boolean,
    ) {
        val rawTitleString: String? = title.get(player, context)
            .takeIf { it.isNotEmpty() }
            ?.parsePlaceholders(player)

        val componentTitle: Component? = rawTitleString
            ?.asMiniCE()

        val inherited = btcrenaud.gui.api.MenuViewSupport.inherit(
            baseMenuId = baseMenuId,
            ownPool = layoutPool,
            ownViews = views,
            ownMainLayoutId = mainLayoutId,
            ownDefaultViewId = defaultViewId,
        )
        val pool = inherited.pool
        val baseStoragePool = baseMenuId.takeIf { it.isNotBlank() }
            ?.let { Ref(it, OpenGuiActionEntry::class).get()?.storagePool }
        val effectiveStoragePool = (baseStoragePool.orEmpty() + storagePool.orEmpty())
            .associateBy { it.id }
        val effectiveMainLayoutId = inherited.mainLayoutId
        val mainLayout = effectiveMainLayoutId?.let { pool[it] }
        val effectiveViews = inherited.views

        val resolvedSize = when (guiType) {
            GuiType.CUSTOM -> size ?: inherited.size ?: InventorySize.SIZE_54 // default to 6 rows (54 slots)
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
                        player.scheduler.run(plugin, { _ -> player.closeInventory() }, null)
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

        // Final layout resolution. Declared views resolve the shared frame shell;
        // menus without views keep the original pool behavior unchanged.
        val resolvedView = if (effectiveViews.isEmpty()) null else
            btcrenaud.gui.api.MenuViewSupport.resolve(
                player = player,
                context = context,
                guiType = guiType,
                totalSize = totalSize,
                pool = pool,
                mainLayoutId = effectiveMainLayoutId,
                views = effectiveViews,
                defaultViewId = inherited.defaultViewId,
                targetViewId = targetViewId,
                baseTitle = title.get(player, context),
                breadcrumbSeparator = breadcrumbSeparator,
                storagePool = effectiveStoragePool,
            )

        val parsedLayout: btcrenaud.gui.api.MenuLayout = resolvedView?.layout
            ?: mainLayout?.let {
                btcrenaud.gui.api.LayoutParser.parse(
                    player, context, guiType, totalSize, pool, it,
                    storagePool = effectiveStoragePool
                )
            }
            ?: btcrenaud.gui.api.EmptyLayout

        // Globally registered resolvers apply to EVERY `open_gui` menu. This is what lets a page
        // place another extension's marker (e.g. a language toggle in a shared Settings tab) and
        // get a live button instead of a dead item. With no resolver registered, the layout is
        // returned unchanged.
        val finalLayout: btcrenaud.gui.api.MenuLayout =
            btcrenaud.gui.api.GlobalButtonResolvers.decorate(parsedLayout)

        // Menu states (_gui_states): when present, wrap the layout so per-player
        // conditions and LayerOverrides apply at render time.
        val states = btcrenaud.gui.services.MenuStateService.getStates(id)
        val statefulLayout = if (states.isEmpty()) {
            finalLayout
        } else {
            btcrenaud.gui.editor.states.StateAwareLayout(id, id, states, finalLayout)
        }

        val viewTitleString = (resolvedView?.rawTitle ?: title.get(player, context))
            .takeIf { it.isNotEmpty() }
            ?.parsePlaceholders(player)
        val definition = btcrenaud.gui.api.MenuDefinition(
            id = id,
            type = guiType,
            title = viewTitleString?.asMiniCE(),
            rawTitle = viewTitleString,
            size = resolvedSize,
            layout = statefulLayout,
            audio = btcrenaud.gui.api.MenuAudioConfig(
                onOpen = audio.onOpen,
                onClose = audio.onClose,
                onScroll = audio.onScroll,
                onClick = audio.onClick
            ),
            activeViewId = resolvedView?.activeViewId,
            breadcrumb = resolvedView?.breadcrumb ?: emptyList(),
            viewSwitcher = if (effectiveViews.isEmpty()) null else { switchedPlayer, viewId ->
                openFor(switchedPlayer, context, viewId, pushHistoryOnViewSwitch)
            },
        ).also {
            // A menu whose resolved root is an `_extended` variant asks for the projection by its
            // shape alone: the shell already reserves the four bottom rows, so requiring the flag
            // as well would render those rows out of reach with no visible cause.
            it.extendToPlayerInventory = guiType == GuiType.CUSTOM && (
                extendToPlayerInventory ||
                    btcrenaud.gui.api.MenuViewSupport.isExtendedRoot(effectiveMainLayoutId)
            )
        }

        btcrenaud.gui.services.MenuSessionService.register(
            player,
            definition,
            pushHistory = pushHistory,
            autoRefreshTicks = autoRefreshTicks,
        )
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
    @Help("ID of a reusable storage configuration from the menu storagePool. Leave empty for a normal slot.")
    val storageId: String? = null,
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
    @Help("How many times this slot repeats along 'direction' — a number of SLOTS, not a stack size. To show a stack of 64, set the item's amount instead. Ignored when direction is unset.")
    val count: Int = 1,
    @Help("Direction in which to repeat this slot (right, left, down, up). Leave null for a single slot at (x, y).")
    val direction: Direction? = null,
    @Help("Step between two repeated slots, NOT a spacing: 1 = adjacent, 2 = one empty slot between each. Applies to both axes.")
    val gap: Int = 1,
    @Help("How many times the whole repetition repeats on the axis PERPENDICULAR to 'direction' — vertically for right/left, horizontally for down/up. A number of ROWS of slots, not a stack size.")
    val repeatY: Int = 1,
) {
    fun toSlot(
        player: Player,
        context: InteractionContext,
        guiType: GuiType,
        width: Int = 9,
        storagePool: Map<String, StorageSlotData> = emptyMap(),
    ): List<btcrenaud.gui.api.GuiSlot> {
        return GuiSlotBuilder.build(player, context, guiType, width, this, storagePool)
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
     * The maths itself lives in [btcrenaud.gui.api.SlotRepetition] — the single owner shared
     * with the editor validation, so the editor can never predict a placement the server does
     * not draw. Extensions that place their own markers (Shops' `SHOP_ITEM`, QuestCodex's
     * `QUEST_SLOT`/`CATEGORY_SLOT`) MUST call this instead of reimplementing the maths:
     * divergent copies are what made tagged markers refuse to spread.
     */
    fun expandPositions(data: GuiItemData): List<Pair<Int, Int>> =
        btcrenaud.gui.api.SlotRepetition.expand(
            x = data.x,
            y = data.y,
            direction = data.direction?.name,
            count = data.count,
            gap = data.gap,
            repeatY = data.repeatY,
        )

    fun build(
        player: Player,
        context: InteractionContext,
        guiType: GuiType,
        width: Int,
        data: GuiItemData,
        storagePool: Map<String, StorageSlotData> = emptyMap(),
    ): List<btcrenaud.gui.api.GuiSlot> {
        if (!data.criteria.matches(player, context)) return emptyList()
        if (!data.viewPermission.permits(player)) return emptyList()
        val canClick = data.clickPermission.permits(player)
        
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
            // The author's own interactions travel WITH the marker. A resolver that claims the tag
            // overwrites them (`slot.copy(interactions = ...)`), so nothing changes for a resolved
            // button; but when NO resolver claims the tag — wrong prefix, wrong case, extension not
            // installed on this screen — GenericButtonResolverLayout leaves the slot as-is, and the
            // slot must then still do what the author configured. Dropping them here turned every
            // mistyped buttonType into a dead item that clicks in silence, with no console error.
            val taggedInteractions = data.interactionList.associate { interactionData ->
                val cmds = interactionData.commands.map { it.get(player, context).parsePlaceholders(player) }.toMutableList()
                if (interactionData.closeMenu) cmds.add("gui:close")
                if (interactionData.executeReturn) cmds.add("gui:back")
                interactionData.type to btcrenaud.gui.api.GuiSlotInteraction(
                    commands = cmds.toList(),
                    triggers = interactionData.triggers
                )
            }
            return listOf(btcrenaud.gui.api.GuiSlot(
                x = data.x,
                y = data.y,
                item = stack,
                allowPickup = false,
                tag = "$prefix$effectiveButtonType",
                triggers = data.triggers,
                modifiers = data.modifiers,
                interactions = if (canClick) taggedInteractions else emptyMap(),
                input = if (canClick) data.input else null,
                cooldownTicks = data.cooldownTicks,
            ))
        }
        
        // Kept as a function so a live menu can run it again on every render: the parsed layout
        // holds one immutable slot list, so a name or lore built only here would never change.
        fun buildStack(target: Player): org.bukkit.inventory.ItemStack {
            val resolved = data.item.get(target, context)
            val stack = if (resolved == Item.Empty) {
                target.inventory.itemInMainHand.clone()
            } else {
                resolved.build(target, context)
            }
            val meta = stack.itemMeta
            if (meta != null) {
                data.displayName?.get(target, context)?.let {
                    meta.displayName(it.parsePlaceholders(target).asMiniItem())
                }
                data.lore.map { it.get(target, context).parsePlaceholders(target).asMiniItem() }
                    .takeIf { it.isNotEmpty() }
                    ?.let { meta.lore(it) }
                stack.itemMeta = meta
            }
            return stack
        }

        val stack = buildStack(player)

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
                // The panel stamps a default animation block on every slot, so a decorative item arrives
                // here carrying targetX/targetY = 0 and duration = 0. That is not an animation:
                // with duration 0 the progress ratio is +Inf, clamps to 1.0, and the slot is drawn
                // at its target — every such slot collapsing onto index 0 — while also scheduling a
                // re-render that races the pending window open and kills the session. Treat a
                // non-positive duration as "no animation" so the slot keeps its own position.
                animation = data.animation?.takeIf { it.duration > 0 }?.let { anim ->
                    btcrenaud.gui.api.SlotAnimation(anim.targetX, anim.targetY, anim.duration, anim.easing)
                },
                cooldownTicks = data.cooldownTicks,
                // Only slots whose text can actually change carry the cost of a rebuild. Tagged
                // buttons are deliberately excluded (they never reach here): a resolver replaces
                // their item, and re-running the author's build would overwrite what it injected.
                itemProvider = if (data.displayName != null || data.lore.isNotEmpty()) {
                    { target -> buildStack(target) }
                } else null,
            )
            val storage = data.storageId?.let(storagePool::get)
            if (storage != null) {
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
                    dropOnClose = storage.dropOnClose,
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
                    commands = emptyList(),
                    triggers = data.triggers,
                    modifiers = data.modifiers,
                    interactions = allInteractions,
                    input = data.input,
                    storage = storage,
                    // The panel stamps a default animation block on every slot, so a decorative item arrives
                // here carrying targetX/targetY = 0 and duration = 0. That is not an animation:
                // with duration 0 the progress ratio is +Inf, clamps to 1.0, and the slot is drawn
                // at its target — every such slot collapsing onto index 0 — while also scheduling a
                // re-render that races the pending window open and kills the session. Treat a
                // non-positive duration as "no animation" so the slot keeps its own position.
                animation = data.animation?.takeIf { it.duration > 0 }?.let { anim ->
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

/** A layout that positions slots with a configurable flex-like row algorithm. */
@Serializable
@AlgebraicTypeInfo("flex", com.typewritermc.core.books.pages.Colors.BLUE, "mdi:format-align-middle")
data class FlexLayoutData(
    override val id: String = "",
    val items: List<GuiItemData> = emptyList(),
    val justifyContent: btcrenaud.gui.api.FlexJustify = btcrenaud.gui.api.FlexJustify.START,
    val alignItems: btcrenaud.gui.api.FlexAlign = btcrenaud.gui.api.FlexAlign.START,
    val wrap: Boolean = true,
    val virtualHeight: Int = 6,
) : LayoutData

/** A layout that overlays several existing layouts. */
@Serializable
@AlgebraicTypeInfo("composite", com.typewritermc.core.books.pages.Colors.PURPLE, "mdi:layers-outline")
data class CompositeLayoutData(
    override val id: String = "",
    val children: List<String> = emptyList(),
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
    @Help("Navigation buttons. Add NEXT, PREVIOUS, BACK and/or INDICATOR roles as needed.")
    val navigationButtons: List<PaginationButtonData> = emptyList()
) : LayoutData

/**
 * INDICATOR is not clickable: it is a read-only "n/N" counter whose name and lore may use the
 * `{page}`, `{total_pages}` and `{page_indicator}` tokens. It is only drawn when the layout has
 * more than one page.
 */
enum class PaginationButtonRole { NEXT, PREVIOUS, BACK, INDICATOR }

@Serializable
data class PaginationButtonData(
    @Help("Role performed by this navigation button.")
    val role: PaginationButtonRole = PaginationButtonRole.NEXT,
    @Help("The item displayed for this navigation button.")
    val item: GuiItemData = GuiItemData(),
)

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
    @Help("If true, remaining contents are dropped on the ground when the menu really closes.")
    val dropOnClose: Boolean = false,
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

/** A dynamic fact leaderboard embedded in an OpenGUI layout. */
@Serializable
@SerialName("leaderboard")
@AlgebraicTypeInfo("leaderboard", com.typewritermc.core.books.pages.Colors.ORANGE, "mdi:podium")
data class LeaderboardLayoutData(
    @Help("Unique identifier for this layout.")
    override val id: String = "",
    @Help("Reusable gui_leaderboard entry to render.")
    val leaderboard: Ref<btcrenaud.gui.entries.GuiLeaderboardEntry> = emptyRef(),
    @Help("First column of the dynamic rows.")
    val x: Int = 0,
    @Help("First row of the dynamic rows.")
    val y: Int = 0,
    @Help("Number of columns reserved for dynamic rows.")
    val width: Int = 9,
    @Help("Number of rows reserved for dynamic rows.")
    val height: Int = 5,
    @Help("Optional previous-page button. Its position comes from the GuiItemData.")
    val previousButton: GuiItemData? = null,
    @Help("Optional next-page button. Its position comes from the GuiItemData.")
    val nextButton: GuiItemData? = null,
) : LayoutData

/** Storage slot configuration linked to a [GuiStorageEntry] artifact.
 *
 * When a [GuiItemData] has its `storage` field set, the slot becomes
 * a persistent storage slot backed by the referenced artifact.
 * Click handling is delegated to [GuiStorageService].
 */
@Serializable
data class StorageSlotData(
    @Help("Unique identifier referenced by GUI item storageId fields.")
    val id: String = "",
    @Help("Reference to a gui_storage artifact entry")
    val entry: Ref<btcrenaud.gui.entries.GuiStorageEntry> = emptyRef(),
    @Help("Group that determines the storage scope. Leave empty for per-player storage. Use a group entry (e.g. island group) for shared storage.")
    val group: Ref<com.typewritermc.engine.paper.entry.entries.GroupEntry> = emptyRef(),
    @Help("Maximum item amount allowed in this slot (1-64)")
    val maxAmount: Int = 64,
    @Help("If true, the slot content is lost when the menu closes")
    val temporary: Boolean = false,
    @Help("If true, remaining contents are dropped on the ground when the menu really closes. Independent from take/place clicks.")
    val dropOnClose: Boolean = false,
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

/**
 * Parses a MiniMessage string with `<shift>`/`<image>` support.
 *
 * `<shift:N>` is always resolved internally
 * ([btcrenaud.gui.resourcepack.font.GuiTagResolvers.shift]): it is a self-contained pixel offset
 * backed by this extension's own Magic Digit font, so a compiled title keeps its alignment on a
 * server that has no CraftEngine. When CraftEngine IS installed its own tags are added and take
 * precedence, which keeps existing packs rendering exactly as before.
 */
private fun String.asMiniCE(): net.kyori.adventure.text.Component {
    val ceResolvers = CraftEngineResolvers.get()
    if (ceResolvers.isNotEmpty()) return asMiniWithResolvers(*ceResolvers)
    return asMiniWithResolvers(btcrenaud.gui.resourcepack.font.GuiTagResolvers.shift())
}

/**
 * Item name / lore variant of [asMiniCE]. Minecraft forces custom item text to italic by
 * default; here we render it upright by default so menus look clean. Authors who *want*
 * italic simply add the `<italic>` MiniMessage tag, which wins over this root default.
 */
private fun String.asMiniItem(): net.kyori.adventure.text.Component =
    asMiniCE().decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)
