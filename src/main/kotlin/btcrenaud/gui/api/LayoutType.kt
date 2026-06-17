package btcrenaud.gui.api

enum class MenuLayoutType { PAGINATED, SCROLLABLE, FRAME, SIMPLE, STORAGE, SKILL_TREE }

fun MenuBuilder.applyLayout(
    type: MenuLayoutType,
    items: List<GuiSlot>,
    slotsPerPage: Int,
    id: String,
    nextSlot: GuiSlot? = null,
    prevSlot: GuiSlot? = null,
    virtualWidth: Int = 9,
    virtualHeight: Int = 6,
) {
    if (items.isEmpty()) return
    when (type) {
        MenuLayoutType.PAGINATED -> {
            val pages = items.chunked(slotsPerPage.coerceAtLeast(1))
            pagination(pages = pages, nextSlot = nextSlot, prevSlot = prevSlot, id = id)
        }
        MenuLayoutType.SCROLLABLE -> layout(ScrollableLayout(
            layout = SimpleLayout(items, virtualWidth = virtualWidth, virtualHeight = virtualHeight),
            id = id, virtualWidth = virtualWidth, virtualHeight = virtualHeight,
            leftSlot = prevSlot, rightSlot = nextSlot))
        MenuLayoutType.FRAME -> {
            val frames = items.groupBy { it.y }.toSortedMap().map { (row, rowItems) ->
                MenuFrame("${id}_r$row", 0, row, virtualWidth, 1, SimpleLayout(rowItems, virtualWidth = virtualWidth, virtualHeight = 1))
            }
            if (frames.isNotEmpty()) layout(FrameLayout(frames, id = id, virtualWidth = virtualWidth, virtualHeight = virtualHeight))
        }
        MenuLayoutType.SIMPLE -> layout(SimpleLayout(items, id = id, virtualWidth = virtualWidth, virtualHeight = virtualHeight))
        MenuLayoutType.STORAGE, MenuLayoutType.SKILL_TREE ->
            layout(SimpleLayout(items, id = id, virtualWidth = virtualWidth, virtualHeight = virtualHeight))
    }
}
