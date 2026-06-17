package btcrenaud.gui.api

import btcrenaud.gui.*
import com.typewritermc.core.interaction.InteractionContext
import org.bukkit.entity.Player
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

                // Default buttons if enabled and no custom buttons for those directions
                if (data.showDefaultButtons) {
                    val vw = data.virtualWidth ?: inner.virtualWidth
                    val vh = data.virtualHeight ?: inner.virtualHeight
                    
                    if (vh > 1) { // Vertical scrolling
                        if (down == null) {
                            val downItem = org.bukkit.inventory.ItemStack(org.bukkit.Material.ARROW)
                            val downMeta = downItem.itemMeta
                            downMeta.displayName("<white>Scroll Down".asMini())
                            downItem.itemMeta = downMeta
                            down = GuiSlot(
                                x = width - 1, y = 1,
                                item = downItem,
                                commands = listOf("gui:scroll 0 1 ${data.id}")
                            )
                        }
                        if (up == null) {
                            val upItem = org.bukkit.inventory.ItemStack(org.bukkit.Material.ARROW)
                            val upMeta = upItem.itemMeta
                            upMeta.displayName("<white>Scroll Up".asMini())
                            upItem.itemMeta = upMeta
                            up = GuiSlot(
                                x = width - 1, y = 0,
                                item = upItem,
                                commands = listOf("gui:scroll 0 -1 ${data.id}")
                            )
                        }
                    }
                    if (vw > 9) { // Horizontal scrolling
                        if (right == null) {
                            val rightItem = org.bukkit.inventory.ItemStack(org.bukkit.Material.ARROW)
                            val rightMeta = rightItem.itemMeta
                            rightMeta.displayName("<white>Scroll Right".asMini())
                            rightItem.itemMeta = rightMeta
                            right = GuiSlot(
                                x = width - 3, y = 0,
                                item = rightItem,
                                commands = listOf("gui:scroll 1 0 ${data.id}")
                            )
                        }
                        if (left == null) {
                            val leftItem = org.bukkit.inventory.ItemStack(org.bukkit.Material.ARROW)
                            val leftMeta = leftItem.itemMeta
                            leftMeta.displayName("<white>Scroll Left".asMini())
                            leftItem.itemMeta = leftMeta
                            left = GuiSlot(
                                x = 0, y = 0,
                                item = leftItem,
                                commands = listOf("gui:scroll -1 0 ${data.id}")
                            )
                        }
                    }
                }

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
        }
        visited.remove(layoutKey)
        cache[layoutKey] = result
        return result
    }
}
