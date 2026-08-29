package btcrenaud.gui.api

import btcrenaud.gui.*
import com.typewritermc.core.interaction.InteractionContext
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.Material
import com.typewritermc.engine.paper.utils.asMini

object LayoutParser {

    private val LOGGER = java.util.logging.Logger.getLogger("Typewriter-OmniGUIExtension")

    /** Rejections already reported ("layout@x,y"), so a re-render does not repeat them. */
    private val reportedRejections: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    private const val MAX_REPORTED_REJECTIONS = 2048

    /**
     * Builds the slots of an item list, then drops those falling outside the grid.
     *
     * Height is NOT bounded from above here: a layout reached through a `scrollable` lives in a
     * virtual space taller than the inventory, and the render pass does the final clipping. Only
     * impossible positions (negative x/y, x past the width) are dropped — and they are now
     * logged: an oversized `count` used to throw its extra slots away without a word.
     */
    fun buildSlots(
        player: Player,
        context: InteractionContext,
        guiType: GuiType,
        totalSize: Int,
        data: List<GuiItemData>,
        width: Int = 9,
        storagePool: Map<String, StorageSlotData> = emptyMap(),
        layoutId: String = "",
    ): List<GuiSlot> {
        val base = data.flatMap { it.toSlot(player, context, guiType, width, storagePool) }
        val kept = ArrayList<GuiSlot>(base.size)
        var rejected = 0
        for (slot in base) {
            if (slot.x in 0 until width && slot.y >= 0) {
                kept += slot
            } else {
                rejected++
                reportRejection(layoutId, slot.x, slot.y, width)
            }
        }
        if (rejected > 0) reportRejectionSummary(layoutId, rejected, base.size, width)
        return kept
    }

    private fun reportRejection(layoutId: String, x: Int, y: Int, width: Int) {
        if (reportedRejections.size >= MAX_REPORTED_REJECTIONS) return
        val layout = layoutId.ifBlank { "<unnamed>" }
        if (!reportedRejections.add("$layout@$x,$y")) return
        LOGGER.warning(
            "[OmniGUI] Layout '$layout': slot at (x=$x, y=$y) falls outside the grid " +
                "(width $width, x and y must be >= 0) and was dropped. Repetition settings " +
                "(count/gap/repeatY) count SLOTS, not stack size."
        )
    }

    private fun reportRejectionSummary(layoutId: String, rejected: Int, total: Int, width: Int) {
        if (reportedRejections.size >= MAX_REPORTED_REJECTIONS) return
        val layout = layoutId.ifBlank { "<unnamed>" }
        if (!reportedRejections.add("$layout#summary")) return
        LOGGER.warning(
            "[OmniGUI] Layout '$layout': $rejected of $total slots dropped for falling outside " +
                "the ${width}-wide grid."
        )
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
        cache: MutableMap<String, MenuLayout> = mutableMapOf(),
        storagePool: Map<String, StorageSlotData> = emptyMap(),
    ): MenuLayout {
        val layoutKey = data.id.ifEmpty { "${data::class.simpleName}@${System.identityHashCode(data)}" }
        // Diamond reuse: return cached result if already fully parsed
        cache[layoutKey]?.let { return it }
        // True cycle: this layout is earlier in the current recursion stack
        if (layoutKey in visited) return EmptyLayout
        visited.add(layoutKey)
        val result = when (data) {
            is SimpleLayoutData -> SimpleLayout(
                slots = buildSlots(player, context, guiType, totalSize, data.items, width, storagePool, data.id),
                id = data.id
            )
            is FlexLayoutData -> FlexLayout(
                slots = buildSlots(player, context, guiType, totalSize, data.items, width, storagePool, data.id),
                justifyContent = data.justifyContent,
                alignItems = data.alignItems,
                wrap = data.wrap,
                id = data.id,
                virtualWidth = width,
                virtualHeight = data.virtualHeight,
            )
            is CompositeLayoutData -> CompositeLayout(
                children = data.children.mapNotNull { childId ->
                    pool[childId]?.let {
                        parse(player, context, guiType, totalSize, pool, it, nested = true, width = width, visited = visited, cache = cache, storagePool = storagePool)
                    }
                },
                id = data.id,
            )
            is PaginatedLayoutData -> {
                val slots = data.slots.ifEmpty { (0 until totalSize).toList() }
                val itemSlots = data.items.flatMap {
                    it.toSlot(player, context, guiType, storagePool = storagePool)
                }
                
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
                    nextSlot = data.navigationButtons.orEmpty().firstOrNull { it.role == PaginationButtonRole.NEXT }?.let { btn ->
                        btn.item.toSlot(player, context, guiType, storagePool = storagePool).firstOrNull()?.let {
                            it.copy(commands = it.commands + "gui:page 1 ${data.id}")
                        }
                    },
                    prevSlot = data.navigationButtons.orEmpty().firstOrNull { it.role == PaginationButtonRole.PREVIOUS }?.let { btn ->
                        btn.item.toSlot(player, context, guiType, storagePool = storagePool).firstOrNull()?.let {
                            it.copy(commands = it.commands + "gui:page -1 ${data.id}")
                        }
                    },
                    backSlot = data.navigationButtons.orEmpty().firstOrNull { it.role == PaginationButtonRole.BACK }?.let { btn ->
                        btn.item.toSlot(player, context, guiType, storagePool = storagePool).firstOrNull()?.let {
                            it.copy(commands = it.commands + "gui:back")
                        }
                    },
                    indicatorSlot = data.navigationButtons.orEmpty().firstOrNull { it.role == PaginationButtonRole.INDICATOR }?.let { btn ->
                        // No command is appended: the indicator only reports where the player is.
                        btn.item.toSlot(player, context, guiType, storagePool = storagePool).firstOrNull()
                    },
                    id = data.id
                )
            }
            is ScrollableLayoutData -> {
                val innerData = data.innerId?.let { pool[it] }
                val innerWidth = data.virtualWidth ?: innerData?.let { if (it is SimpleLayoutData) 9 else null } ?: 9
                val inner = innerData?.let {
                    parse(player, context, guiType, totalSize, pool, it, nested = true, width = innerWidth, visited = visited, cache = cache, storagePool = storagePool)
                } ?: EmptyLayout
                
                var up: GuiSlot? = null
                var down: GuiSlot? = null
                var left: GuiSlot? = null
                var right: GuiSlot? = null
                
                // Custom buttons
                data.buttons.forEach { btn ->
                    btn.item.toSlot(player, context, guiType, 9, storagePool).forEach { slot ->
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
                    val innerLayout = frame.layoutId?.let { pool[it] }?.let {
                        parse(player, context, guiType, totalSize, pool, it, nested = true, width = frame.width, visited = visited, cache = cache, storagePool = storagePool)
                    } ?: return@mapNotNull null
                    MenuFrame(frame.id, frame.x, frame.y, frame.width, frame.height, innerLayout)
                }
                FrameLayout(frames, data.id)
            }
            is BookLayoutData -> EmptyLayout // Handled at top level
            is MerchantLayoutData -> EmptyLayout // Handled at top level
            is LeaderboardLayoutData -> {
                val leaderboard = data.leaderboard.get() ?: return EmptyLayout
                val effectiveWidth = data.width.coerceAtLeast(1)
                val effectiveHeight = data.height.coerceAtLeast(1)
                val positions = (0 until effectiveHeight).flatMap { row ->
                    (0 until effectiveWidth).map { column -> data.x + column to data.y + row }
                }
                LeaderboardLayout(
                    entry = leaderboard,
                    context = context,
                    positions = positions,
                    previousButton = data.previousButton?.toSlot(player, context, guiType).orEmpty().firstOrNull(),
                    nextButton = data.nextButton?.toSlot(player, context, guiType).orEmpty().firstOrNull(),
                    id = data.id,
                )
            }
            is StorageLayoutData -> {
                val storageEntry = data.entry.get() ?: return EmptyLayout
                val groupKey = data.groupKey.get(player, context)
                val slotConfigs = data.slots.map { slot ->
                    StorageSlotConfig(
                        x = slot.x, y = slot.y,
                        slotIndex = slot.y * 9 + slot.x,
                        maxStack = slot.maxStack,
                        temporary = slot.temporary,
                        dropOnClose = slot.dropOnClose,
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
