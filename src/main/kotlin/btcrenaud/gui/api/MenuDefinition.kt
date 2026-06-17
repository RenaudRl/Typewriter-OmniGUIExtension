package btcrenaud.gui.api

import net.kyori.adventure.text.Component
import btcrenaud.gui.GuiType
import btcrenaud.gui.InventorySize
import btcrenaud.gui.services.MenuSessionService
import btcrenaud.gui.InputData
import btcrenaud.gui.StorageSlotData

import com.typewritermc.engine.paper.utils.Sound
import com.typewritermc.core.extension.annotations.Help

data class MenuDefinition(
    @Help("Unique identifier for this menu definition.")
    val id: String,
    @Help("Type of the GUI inventory (e.g., CHEST, ANVIL, CUSTOM).")
    val type: GuiType,
    @Help("Title displayed to the player. Supports MiniMessage formatting.")
    val title: net.kyori.adventure.text.Component? = null,
    @Help("Raw MiniMessage title string, for re-parsing at render time with CraftEngine resolvers.")
    val rawTitle: String? = null,
    @Help("Size of the inventory (if type is CUSTOM). Must be a multiple of 9.")
    val size: InventorySize? = null,
    @Help("Modular layout structure defining the contents of the GUI.")
    val layout: MenuLayout = EmptyLayout,
    @Help("Audio configuration for lifecycle events (Open, Close, Click, Scroll).")
    val audio: MenuAudioConfig = MenuAudioConfig()
)

data class MenuAudioConfig(
    @Help("Sound played when the GUI is opened.")
    val onOpen: Sound? = null,
    @Help("Sound played when the GUI is closed.")
    val onClose: Sound? = null,
    @Help("Sound played when the GUI is scrolled.")
    val onScroll: Sound? = null,
    @Help("Sound played when an item is clicked.")
    val onClick: Sound? = null
)

interface MenuLayout {
    val id: String? get() = null
    fun getSlots(session: MenuSessionService.ActiveSession, viewport: Viewport): List<GuiSlot>
    
    val virtualWidth: Int get() = 9
    val virtualHeight: Int get() = 6

    /**
     * Optional inner layout for wrapper/decorator layouts.
     * When non-null, the session service traverses into it during layout state
     * initialization and scroll resolution. Wrapper layouts (e.g. dungeon button
     * resolvers, augmented layouts) should override this so their inner structure
     * is visible to the scroll/pagination system.
     */
    val innerLayout: MenuLayout? get() = null
}

object EmptyLayout : MenuLayout {
    override fun getSlots(session: MenuSessionService.ActiveSession, viewport: Viewport): List<GuiSlot> = emptyList()
}

/**
 * A simple grid-based layout.
 */
class SimpleLayout(
    val slots: List<GuiSlot>,
    override val id: String? = null,
    override val virtualWidth: Int = 9,
    override val virtualHeight: Int = 6
) : MenuLayout {
    override fun getSlots(session: MenuSessionService.ActiveSession, viewport: Viewport): List<GuiSlot> = slots
}

/**
 * A layout composed of static elements and a scrollable viewport.
 */
class CompositeLayout(
    val children: List<MenuLayout> = emptyList(),
    override val virtualWidth: Int = 9,
    override val virtualHeight: Int = 6,
    override val id: String? = null
) : MenuLayout {
    
    override fun getSlots(session: MenuSessionService.ActiveSession, viewport: Viewport): List<GuiSlot> {
        return children.flatMap { it.getSlots(session, viewport) }
    }
}

/**
 * A layout wrapper that clips children to the viewport.
 */
class ScrollableLayout(
    val layout: MenuLayout,
    override val id: String? = null,
    override val virtualWidth: Int = layout.virtualWidth,
    override val virtualHeight: Int = layout.virtualHeight,
    val upSlot: GuiSlot? = null,
    val downSlot: GuiSlot? = null,
    val leftSlot: GuiSlot? = null,
    val rightSlot: GuiSlot? = null
) : MenuLayout {
    override fun getSlots(session: MenuSessionService.ActiveSession, viewport: Viewport): List<GuiSlot> {
        val subViewport = id?.let { session.subViewports[it] } ?: viewport
        // Request slots in virtual space (unclipped by this wrapper, but clipped by parent if needed)
        // We pass the subViewport to the inner layout so it can optimize its lookup
        val filtered = layout.getSlots(session, subViewport).filter { slot ->
            slot.x >= subViewport.x && slot.x < subViewport.x + subViewport.width &&
            slot.y >= subViewport.y && slot.y < subViewport.y + subViewport.height
        }.map { slot ->
            // Remap to relative coordinates (0..width, 0..height)
            slot.copy(x = slot.x - subViewport.x, y = slot.y - subViewport.y)
        }

        val nav = mutableListOf<GuiSlot>()
        val vw = virtualWidth
        val vh = virtualHeight
        
        // Buttons are always shown if their corresponding scroll direction is available
        if (subViewport.y > 0) upSlot?.let { nav.add(it) }
        if (subViewport.y < vh - subViewport.height) downSlot?.let { nav.add(it) }
        if (subViewport.x > 0) leftSlot?.let { nav.add(it) }
        if (subViewport.x < vw - subViewport.width) rightSlot?.let { nav.add(it) }

        return filtered + nav
    }
}

/**
 * A layout that supports multiple discrete pages.
 */
class PaginatedLayout(
    val pages: List<List<GuiSlot>>,
    val nextSlot: GuiSlot? = null,
    val prevSlot: GuiSlot? = null,
    val backSlot: GuiSlot? = null,
    override val id: String? = null,
    override val virtualWidth: Int = 9,
    override val virtualHeight: Int = 6
) : MenuLayout {
    override fun getSlots(session: MenuSessionService.ActiveSession, viewport: Viewport): List<GuiSlot> {
        val currentPage = id?.let { session.pageStates[it] } ?: 0
        val currentSlots = pages.getOrNull(currentPage) ?: emptyList()
        val nav = mutableListOf<GuiSlot>()
        if (currentPage > 0) prevSlot?.let { nav.add(it) }
        backSlot?.let { nav.add(it) }
        if (currentPage < pages.size - 1) nextSlot?.let { nav.add(it) }
        return currentSlots + nav
    }
}

/**
 * A layout composed of multiple independent frames (panes).
 */
class FrameLayout(
    val frames: List<MenuFrame>,
    override val id: String? = null,
    override val virtualWidth: Int = 9,
    override val virtualHeight: Int = 6
) : MenuLayout {
    override fun getSlots(session: MenuSessionService.ActiveSession, viewport: Viewport): List<GuiSlot> {
        return frames.flatMap { frame ->
            // Pass a viewport matching the frame's bounds to the inner layout
            // This ensures children calculate their visibility based on the frame dimensions
            val frameViewport = Viewport(width = frame.width, height = frame.height)
            frame.layout.getSlots(session, frameViewport).map { slot ->
                // Offset the relative slots by the frame position
                slot.copy(x = slot.x + frame.x, y = slot.y + frame.y)
            }
        }
    }
}

/**
 * A rectangular frame within a FrameLayout. Each frame must have a unique [id] within
 * its parent FrameLayout to avoid ambiguity during layout resolution and session tracking.
 */
data class MenuFrame(
    val id: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val layout: MenuLayout
)

/**
 * A slider component for selecting a value in a range.
 */
class SliderComponent(
    val x: Int,
    val y: Int,
    val length: Int,
    val horizontal: Boolean = true,
    @Volatile var value: Double = 0.0,
    val trackItem: org.bukkit.inventory.ItemStack,
    val thumbItem: org.bukkit.inventory.ItemStack,
    val onUpdate: (Double) -> Unit,
    override val id: String? = null
) : MenuLayout {
    override fun getSlots(session: MenuSessionService.ActiveSession, viewport: Viewport): List<GuiSlot> {
        val thumbPos = (value * (length - 1)).toInt()
        return (0 until length).map { i ->
            val isThumb = i == thumbPos
            val slotX = x + (if (horizontal) i else 0)
            val slotY = y + (if (horizontal) 0 else i)
            GuiSlot(
                x = slotX,
                y = slotY,
                item = if (isThumb) thumbItem else trackItem,
                allowPickup = false,
                commands = listOf("gui:slider_set $i")
            )
        }
    }
}

/**
 * A layout that automatically paginates a sequence of items.
 */
class IteratorLayout<T>(
    val items: Iterable<T>,
    val slots: List<Int>, // Physical indices to fill
    override val id: String? = null,
    val mapper: (T) -> GuiSlot
) : MenuLayout {
    private val pageSize = slots.size
    
    constructor(
        items: Iterable<T>,
        startX: Int, startY: Int, endX: Int, endY: Int,
        id: String? = null,
        mapper: (T) -> GuiSlot
    ) : this(
        items = items,
        slots = (startY..endY).flatMap { y -> (startX..endX).map { x -> x + y * 9 } },
        mapper = mapper,
        id = id
    )

    override fun getSlots(session: MenuSessionService.ActiveSession, viewport: Viewport): List<GuiSlot> {
        val currentPage = id?.let { session.pageStates[it] } ?: 0
        val list = items.toList()
        val start = (currentPage * pageSize).coerceIn(0, list.size)
        val end = (start + pageSize).coerceAtMost(list.size)
        if (start >= list.size) return emptyList()
        
        return list.subList(start, end).mapIndexed { i, item ->
            val physicalIndex = slots[i]
            val slot = mapper(item)
            // Override position to match the slot index
            slot.copy(x = physicalIndex % 9, y = physicalIndex / 9)
        }
    }
    
    override val virtualWidth: Int get() = 9
    override val virtualHeight: Int get() = ((items.toList().size + 8) / 9)
}

data class Viewport(
    @Help("Horizontal scroll offset.")
    val x: Int = 0,
    @Help("Vertical scroll offset.")
    val y: Int = 0,
    @Help("Width of the visible area in slots.")
    val width: Int = 9,
    @Help("Height of the visible area in slots.")
    val height: Int = 6
)

/**
 * Represents a slot in the GUI.
 */
open class GuiSlot(
    @Help("Horizontal coordinate in the layout (virtual X).")
    val x: Int,
    @Help("Vertical coordinate in the layout (virtual Y).")
    val y: Int,
    @Help("The Bukkit ItemStack to display in this slot.")
    val item: org.bukkit.inventory.ItemStack,
    @Help("Whether players can pick up this item from the GUI.")
    val allowPickup: Boolean = false,
    @Help("If true, clicking this item copies it to the cursor instead of moving it (Ghost Mode).")
    val isGhost: Boolean = false,
    @Help("Commands executed when this slot is interacted with.")
    val commands: List<String> = emptyList(),
    @Help("Triggers (Entry References) executed when this slot is clicked.")
    val triggers: List<com.typewritermc.core.entries.Ref<com.typewritermc.engine.paper.entry.TriggerableEntry>> = emptyList(),
    @Help("Modifiers applied to the player when this slot is clicked.")
    val modifiers: List<com.typewritermc.engine.paper.entry.Modifier> = emptyList(),
    @Help("Advanced interaction mapping (e.g., RIGHT_CLICK, MOVE_UP, JUMP).")
    val interactions: Map<InteractionType, GuiSlotInteraction> = emptyMap(),
    @Help("Input dialog configuration for this slot (if clicking opens a prompt).")
    val input: InputData? = null,
    @Help("Storage configuration for this slot (if clicking opens a persistent storage slot).")
    val storage: StorageSlotData? = null,
    @Help("Animation configuration for smooth slot movement (Tweening).")
    val animation: SlotAnimation? = null,
    @Help("Cooldown in ticks before this slot can be interacted with again.")
    val cooldownTicks: Long = 0,
    @Help("Optional tag for identifying special slots (e.g., dungeon buttons).")
    val tag: String? = null
) {
    fun copy(
        x: Int = this.x,
        y: Int = this.y,
        item: org.bukkit.inventory.ItemStack = this.item,
        allowPickup: Boolean = this.allowPickup,
        isGhost: Boolean = this.isGhost,
        commands: List<String> = this.commands,
        triggers: List<com.typewritermc.core.entries.Ref<com.typewritermc.engine.paper.entry.TriggerableEntry>> = this.triggers,
        modifiers: List<com.typewritermc.engine.paper.entry.Modifier> = this.modifiers,
        interactions: Map<InteractionType, GuiSlotInteraction> = this.interactions,
        input: InputData? = this.input,
        storage: StorageSlotData? = this.storage,
        animation: SlotAnimation? = this.animation,
        cooldownTicks: Long = this.cooldownTicks,
        tag: String? = this.tag
    ) = GuiSlot(x, y, item, allowPickup, isGhost, commands, triggers, modifiers, interactions, input, storage, animation, cooldownTicks, tag)
}

data class SlotAnimation(
    @Help("Target X coordinate for the animation.")
    val targetX: Int,
    @Help("Target Y coordinate for the animation.")
    val targetY: Int,
    @Help("Duration of the animation in milliseconds.")
    val duration: Long,
    @Help("Easing function name (e.g., 'linear', 'ease_in', 'ease_out').")
    val easing: String = "linear"
)

/**
 * Bundles commands and triggers for a specific interaction type on a slot.
 * Replaces the plain List<String> in [GuiSlot.interactions] to allow trigger references
 * to flow from [btcrenaud.gui.InteractionData] into the API layer.
 */
data class GuiSlotInteraction(
    @Help("Commands executed for this interaction.")
    val commands: List<String> = emptyList(),
    @Help("Triggers executed for this interaction.")
    val triggers: List<com.typewritermc.core.entries.Ref<com.typewritermc.engine.paper.entry.TriggerableEntry>> = emptyList()
)

enum class InteractionType {
    LEFT_CLICK,
    RIGHT_CLICK,
    SHIFT_LEFT_CLICK,
    SHIFT_RIGHT_CLICK,
    MIDDLE_CLICK,
    DOUBLE_CLICK,
    NUMBER_KEY_1,
    NUMBER_KEY_2,
    NUMBER_KEY_3,
    NUMBER_KEY_4,
    NUMBER_KEY_5,
    NUMBER_KEY_6,
    NUMBER_KEY_7,
    NUMBER_KEY_8,
    NUMBER_KEY_9,
    DROP,
    DROP_ALL,
    SWAP_OFFHAND,
    
    // Advanced WASD/Key Inputs (via Vehicle Mount)
    MOVE_UP,
    MOVE_DOWN,
    MOVE_LEFT,
    MOVE_RIGHT,
    JUMP,
    SNEAK,
    SPRINT,
    
    // Scroll Inputs (via Held Item Change)
    SCROLL_UP,
    SCROLL_DOWN;

    companion object {
        /**
         * Uses if-else instead of when() to avoid generating $$WhenMappings synthetic
         * classes that are invisible to Paper's ClassLoader during event dispatch.
         */
        fun fromBukkit(click: org.bukkit.event.inventory.ClickType): InteractionType? {
            if (click == org.bukkit.event.inventory.ClickType.LEFT) return LEFT_CLICK
            if (click == org.bukkit.event.inventory.ClickType.RIGHT) return RIGHT_CLICK
            if (click == org.bukkit.event.inventory.ClickType.SHIFT_LEFT) return SHIFT_LEFT_CLICK
            if (click == org.bukkit.event.inventory.ClickType.SHIFT_RIGHT) return SHIFT_RIGHT_CLICK
            if (click == org.bukkit.event.inventory.ClickType.MIDDLE) return MIDDLE_CLICK
            if (click == org.bukkit.event.inventory.ClickType.DOUBLE_CLICK) return DOUBLE_CLICK
            if (click == org.bukkit.event.inventory.ClickType.DROP) return DROP
            if (click == org.bukkit.event.inventory.ClickType.CONTROL_DROP) return DROP_ALL
            if (click == org.bukkit.event.inventory.ClickType.SWAP_OFFHAND) return SWAP_OFFHAND
            if (click == org.bukkit.event.inventory.ClickType.NUMBER_KEY) return null // Handled separately
            return null
        }
        
        /**
         * Uses if-else instead of when() to avoid generating $$WhenMappings synthetic
         * classes that are invisible to Paper's ClassLoader during event dispatch.
         */
        fun fromNumberKey(index: Int): InteractionType? {
            if (index == 0) return NUMBER_KEY_1
            if (index == 1) return NUMBER_KEY_2
            if (index == 2) return NUMBER_KEY_3
            if (index == 3) return NUMBER_KEY_4
            if (index == 4) return NUMBER_KEY_5
            if (index == 5) return NUMBER_KEY_6
            if (index == 6) return NUMBER_KEY_7
            if (index == 7) return NUMBER_KEY_8
            if (index == 8) return NUMBER_KEY_9
            return null
        }
    }
}
