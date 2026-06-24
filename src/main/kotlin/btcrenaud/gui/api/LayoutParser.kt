package btcrenaud.gui.api

import btcrenaud.gui.*
import com.typewritermc.core.interaction.InteractionContext
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.Material
import com.typewritermc.engine.paper.utils.asMini

object LayoutParser {

    fun buildSlots(
        player: Player, 
        context: InteractionContext, 
        guiType: GuiType, 
        totalSize: Int, 
        data: List<GuiItemData>, 
        width: Int = 9
    ): List<GuiSlot> {
        val base = data.flatMap { it.toSlot(player, context, guiType, width) }
        return base.filter { slot -> slot.x >= 0 && slot.y >= 0 && slot.x < width && slot.y >= 0 }
    }

    fun parse(
        player: Player,
        context: InteractionContext,
        guiType: GuiType,
        totalSize: Int,
        pool: Map<String, LayoutData>,
        data: LayoutData,
        nested: Boolean = false,
        width: Int = 9,
        visited: MutableSet<String> = mutableSetOf(),
        cache: MutableMap<String, MenuLayout> = mutableMapOf()
    ): MenuLayout {
        val layoutKey = data.id.ifEmpty { "${data::class.simpleName}@${System.identityHashCode(data)}" }
        // Diamond reuse: return cached result if already fully parsed
        cache[layoutKey]?.let { return it }
        // True cycle: this layout is earlier in the current recursion stack
        if (layoutKey in visited) return EmptyLayout
        visited.add(layoutKey)
        val result = when (data) {
            is SimpleLayoutData -> SimpleLayout(
                slots = buildSlots(player, context, guiType, totalSize, data.items, width),
                id = data.id
            )
            is PaginatedLayoutData -> {
                val slots = data.slots.ifEmpty { (0 until totalSize).toList() }
                val itemSlots = data.items.flatMap { it.toSlot(player, context, guiType) }
                
                // Slice items into pages
                val pages = itemSlots.chunked(data.itemsPerPage.coerceAtLeast(1))
                
                val apiPages = pages.map { pageItems ->
                    // Map items to the available slots
                    pageItems.mapIndexed { index, slot ->
                        val slotIndex = slots.getOrElse(index) { -1 }
                        if (slotIndex != -1) {
                            slot.copy(x = slotIndex % 9, y = slotIndex / 9)
                        } else null
                    }.filterNotNull()
                }

                PaginatedLayout(
                    pages = apiPages,
                    nextSlot = data.nextPage?.let { btn ->
                        btn.item.toSlot(player, context, guiType).firstOrNull()?.let { 
                            it.copy(commands = it.commands + "gui:page 1 ${data.id}")
                        }
                    },
                    prevSlot = data.previousPage?.let { btn ->
                        btn.item.toSlot(player, context, guiType).firstOrNull()?.let { 
                            it.copy(commands = it.commands + "gui:page -1 ${data.id}")
                        }
                    },
                    backSlot = data.backButton?.let { btn ->
                        btn.item.toSlot(player, context, guiType).firstOrNull()?.let {
                            it.copy(commands = it.commands + "gui:back")
                        }
                    },
                    id = data.id
                )
            }
            is ScrollableLayoutData -> {
                val innerData = data.innerId?.let { pool[it] }
                val innerWidth = data.virtualWidth ?: innerData?.let { if (it is SimpleLayoutData) 9 else null } ?: 9
                val inner = innerData?.let { parse(player, context, guiType, totalSize, pool, it, nested = true, width = innerWidth, visited = visited, cache = cache) } ?: EmptyLayout
                
                var up: GuiSlot? = null
                var down: GuiSlot? = null
                var left: GuiSlot? = null
                var right: GuiSlot? = null
                
                // Custom buttons
                data.buttons.forEach { btn ->
                    btn.item.toSlot(player, context, guiType, 9).forEach { slot ->
                        val cmd = when(btn.direction) {
                            ScrollDirection.UP -> "gui:scroll 0 -${btn.step} ${data.id}"
                            ScrollDirection.DOWN -> "gui:scroll 0 ${btn.step} ${data.id}"
                            ScrollDirection.LEFT -> "gui:scroll -${btn.step} 0 ${data.id}"
                            ScrollDirection.RIGHT -> "gui:scroll ${btn.step} 0 ${data.id}"
                        }
                        val apiSlot = slot.copy(commands = slot.commands + cmd)
                        when(btn.direction) {
                            ScrollDirection.UP -> up = apiSlot
                            ScrollDirection.DOWN -> down = apiSlot
                            ScrollDirection.LEFT -> left = apiSlot
                            ScrollDirection.RIGHT -> right = apiSlot
                        }
                    }
                }

                // Default buttons removed — users must configure their own navigation buttons
                // via the entry's layoutPool buttons list.

                ScrollableLayout(
                    layout = inner, 
                    id = data.id, 
                    virtualWidth = data.virtualWidth ?: inner.virtualWidth, 
                    virtualHeight = data.virtualHeight ?: inner.virtualHeight,
                    upSlot = up,
                    downSlot = down,
                    leftSlot = left,
                    rightSlot = right
                )
            }

            is FrameLayoutData -> {
                val frames = data.frames.mapNotNull { frame ->
                    val innerLayout = frame.layoutId?.let { pool[it] }?.let { parse(player, context, guiType, totalSize, pool, it, nested = true, width = frame.width, visited = visited, cache = cache) } ?: return@mapNotNull null
                    MenuFrame(frame.id, frame.x, frame.y, frame.width, frame.height, innerLayout)
                }
                FrameLayout(frames, data.id)
            }
            is BookLayoutData -> EmptyLayout // Handled at top level
            is MerchantLayoutData -> EmptyLayout // Handled at top level
            is StorageLayoutData -> {
                val storageEntry = data.entry.get() ?: return EmptyLayout
                val groupKey = data.groupKey.get(player, context)
                val slotConfigs = data.slots.map { slot ->
                    StorageSlotConfig(
                        x = slot.x, y = slot.y,
                        slotIndex = slot.y * 9 + slot.x,
                        maxStack = slot.maxStack,
                        temporary = slot.temporary,
                        placeholder = slot.placeholder?.get(player, context)
                            ?.let { it.build(player, context).clone() } ?: ItemStack(Material.AIR),
                        onFill = slot.onFill, onEmpty = slot.onEmpty,
                        requiredItem = slot.requiredItem?.get(player, context)
                            ?.let { it.build(player, context).clone() },
                        requiredAmount = slot.requiredAmount,
                        onReachRequired = slot.onReachRequired,
                        consumeItems = slot.consumeItems
                    )
                }
                StorageLayout(storageEntry, slotConfigs, { groupKey }, data.id)
            }
        }
        visited.remove(layoutKey)
        cache[layoutKey] = result
        return result
    }
}
