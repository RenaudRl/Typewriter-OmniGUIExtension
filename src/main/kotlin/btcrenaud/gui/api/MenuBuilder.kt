package btcrenaud.gui.api

import org.bukkit.inventory.ItemStack
import org.bukkit.Material
import btcrenaud.gui.GuiType
import btcrenaud.gui.InventorySize
import btcrenaud.gui.entries.GuiStorageEntry
import com.typewritermc.core.entries.Ref
import com.typewritermc.engine.paper.entry.TriggerableEntry

class MenuBuilder(val id: String) {
    private var type: GuiType = GuiType.CUSTOM
    private var title: net.kyori.adventure.text.Component? = null
    private var rawTitle: String? = null
    private var size: InventorySize? = null
    private val staticSlots = mutableListOf<GuiSlot>()
    private val dynamicSlots = mutableListOf<GuiSlot>()
    private val layouts = mutableListOf<MenuLayout>()
    private var rootLayout: MenuLayout? = null
    private var vWidth: Int = 9
    private var vHeight: Int = 6

    fun type(type: GuiType) = apply { this.type = type }
    fun title(title: net.kyori.adventure.text.Component) = apply { this.title = title }
    fun rawTitle(raw: String) = apply { this.rawTitle = raw }
    fun size(size: InventorySize) = apply { this.size = size }
    fun virtualSize(width: Int, height: Int) = apply { 
        this.vWidth = width
        this.vHeight = height
    }

    fun staticSlot(x: Int, y: Int, item: ItemStack, block: (GuiSlotBuilder.() -> Unit)? = null) = apply {
        val builder = GuiSlotBuilder(x, y, item)
        block?.invoke(builder)
        staticSlots.add(builder.build())
    }

    fun dynamicSlot(x: Int, y: Int, item: ItemStack, block: (GuiSlotBuilder.() -> Unit)? = null) = apply {
        val builder = GuiSlotBuilder(x, y, item)
        block?.invoke(builder)
        dynamicSlots.add(builder.build())
    }

    fun frame(id: String, x: Int, y: Int, width: Int, height: Int, layout: MenuLayout) = apply {
        layouts.add(FrameLayout(listOf(MenuFrame(id, x, y, width, height, layout)), id))
    }

    fun pagination(pages: List<List<GuiSlot>>, nextSlot: GuiSlot? = null, prevSlot: GuiSlot? = null, backSlot: GuiSlot? = null, id: String? = null) = apply {
        layouts.add(PaginatedLayout(pages, nextSlot, prevSlot, backSlot, id))
    }

    fun iterator(items: List<ItemStack>, slots: List<Int>, id: String? = null, block: ((ItemStack) -> GuiSlot)? = null) = apply {
        val layout = if (block != null) {
            IteratorLayout(items, slots, id, block)
        } else {
            IteratorLayout(items, slots, id) { item -> GuiSlot(0, 0, item) }
        }
        layouts.add(layout)
    }

    fun slider(id: String, x: Int, y: Int, length: Int, horizontal: Boolean = true, track: ItemStack, thumb: ItemStack, onUpdate: (Double) -> Unit) = apply {
        layouts.add(SliderComponent(x, y, length, horizontal, 0.0, track, thumb, onUpdate, id))
    }

    fun layout(layout: MenuLayout) = apply {
        rootLayout = layout
    }

    /**
     * Adds a group of persistent storage slots backed by a [GuiStorageEntry] artifact.
     *
     * @param entry             Artifact that persists the stored items across sessions.
     * @param groupKeyProvider  Maps a player to a storage group key. Default: player UUID (per-player).
     *                          For shared group storage pass e.g. `{ "island:${getIslandId(it)}" }`.
     * @param id                Optional layout id for viewport / focus tracking.
     * @param block             Defines the individual slot positions and behaviour.
     */
    fun storage(
        entry: GuiStorageEntry,
        groupKeyProvider: (org.bukkit.entity.Player) -> String = { it.uniqueId.toString() },
        id: String? = null,
        block: StorageLayoutBuilder.() -> Unit
    ) = apply {
        val builder = StorageLayoutBuilder(entry, groupKeyProvider, id)
        block(builder)
        layouts.add(builder.build())
    }

    fun build(): MenuDefinition {
        val children = mutableListOf<MenuLayout>()
        if (staticSlots.isNotEmpty()) {
            children.add(SimpleLayout(staticSlots))
        }
        if (dynamicSlots.isNotEmpty()) {
            children.add(ScrollableLayout(SimpleLayout(dynamicSlots)))
        }
        children.addAll(layouts)

        return MenuDefinition(
            id = id,
            type = type,
            title = title,
            rawTitle = rawTitle,
            size = size,
            layout = rootLayout ?: CompositeLayout(children, vWidth, vHeight)
        )
    }
}

class GuiSlotBuilder(val x: Int, val y: Int, val item: ItemStack) {
    var allowPickup: Boolean = false
    var commands = mutableListOf<String>()
    var interactions = mutableMapOf<InteractionType, MutableList<String>>()
    var triggers = mutableListOf<com.typewritermc.core.entries.Ref<com.typewritermc.engine.paper.entry.TriggerableEntry>>()
    var modifiers = mutableListOf<com.typewritermc.engine.paper.entry.Modifier>()

    fun command(cmd: String) = apply { commands.add(cmd) }
    
    fun onInteraction(type: InteractionType, cmd: String) = apply {
        interactions.getOrPut(type) { mutableListOf() }.add(cmd)
    }

    fun onLeftClick(cmd: String) = onInteraction(InteractionType.LEFT_CLICK, cmd)
    fun onRightClick(cmd: String) = onInteraction(InteractionType.RIGHT_CLICK, cmd)
    fun onShiftClick(cmd: String) = onInteraction(InteractionType.SHIFT_LEFT_CLICK, cmd)
    fun onKeyF(cmd: String) = onInteraction(InteractionType.SWAP_OFFHAND, cmd)
    fun onKeyQ(cmd: String) = onInteraction(InteractionType.DROP, cmd)
    fun onKeyNumber(number: Int, cmd: String) = InteractionType.fromNumberKey(number-1)?.let { onInteraction(it, cmd) }

    fun build(): GuiSlot = GuiSlot(
        x = x,
        y = y,
        item = item,
        allowPickup = allowPickup,
        commands = commands,
        triggers = triggers,
        modifiers = modifiers,
        interactions = interactions.mapValues { GuiSlotInteraction(commands = it.value.toList()) }
    )
}

/**
 * Builder for a [StorageLayout] (a grid of persistent item-storage slots).
 *
 * Usage inside [MenuBuilder.storage]:
 * ```
 * storage(myStorageEntry) {
 *     slot(x = 2, y = 1, placeholder = grayPane)
 *     slot(x = 3, y = 1, temporary = true, onFill = listOf(craftTrigger))
 * }
 * ```
 */
class StorageLayoutBuilder(
    private val entry: GuiStorageEntry,
    private val groupKeyProvider: (org.bukkit.entity.Player) -> String,
    private val id: String?
) {
    private val slots = mutableListOf<StorageSlotConfig>()
    private var nextIndex = 0

    fun slot(
        x: Int,
        y: Int,
        maxStack: Int = 64,
        temporary: Boolean = false,
        placeholder: ItemStack = ItemStack(Material.AIR),
        onFill: List<Ref<TriggerableEntry>> = emptyList(),
        onEmpty: List<Ref<TriggerableEntry>> = emptyList(),
        slotIndex: Int = nextIndex,
        requiredItem: org.bukkit.inventory.ItemStack? = null,
        requiredAmount: Int = 0,
        onReachRequired: List<Ref<TriggerableEntry>> = emptyList(),
        consumeItems: Boolean = true
    ) = apply {
        if (slotIndex == nextIndex) nextIndex++
        slots.add(StorageSlotConfig(x, y, slotIndex, maxStack, temporary, emptyList(), onFill, onEmpty, placeholder, requiredItem, requiredAmount, onReachRequired, consumeItems))
    }

    fun build() = StorageLayout(entry, slots.toList(), groupKeyProvider, id)
}
