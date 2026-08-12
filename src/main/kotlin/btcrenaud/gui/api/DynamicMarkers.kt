package btcrenaud.gui.api

import btcrenaud.gui.GuiItemData
import btcrenaud.gui.GuiSlotBuilder
import btcrenaud.gui.LayoutData
import btcrenaud.gui.SimpleLayoutData

/**
 * Indexing of the dynamic content markers of a layout pool.
 *
 * A menu listing entries produced at runtime (quests, vehicles, members…) places identical markers
 * in its page — `QUEST_SLOT`, `VEHICLE_SLOT`. For the resolver to know *which* one to fill with the
 * n-th entry, every marker is given an index here (`VEHICLE_SLOT#0`, `VEHICLE_SLOT#1`, …) **before**
 * the layout is parsed. It then travels through the parser as an ordinary tagged slot and follows
 * the scrollable or paginated viewport of its frame.
 *
 * This lives here rather than in each extension because a private copy per extension is exactly how
 * these semantics drifted apart in the past.
 */
object DynamicMarkers {

    /**
     * Rewrites every item of [pool] whose `buttonType` equals [markerType] into a series of indexed
     * markers, one per occupied position.
     *
     * @return the rewritten pool and the number of markers produced — that number is the size of a
     *   page, hence what bounds the caller's pagination.
     */
    fun index(pool: List<LayoutData>, markerType: String): Pair<List<LayoutData>, Int> {
        var counter = 0
        val rewritten = pool.map { data ->
            if (data !is SimpleLayoutData) return@map data
            val newItems = data.items.flatMap { item ->
                if (item.buttonType != markerType) listOf(item)
                else expandPositions(item).map { (px, py) ->
                    item.copy(
                        buttonType = "$markerType#${counter++}",
                        x = px,
                        y = py,
                        direction = null,
                        count = 1,
                        repeatY = 1,
                    )
                }
            }
            data.copy(items = newItems)
        }
        return rewritten to counter
    }

    /**
     * Expands a marker's repetition into individual positions.
     *
     * Delegates to [GuiSlotBuilder.expandPositions] so markers spread exactly the way the render
     * pass lays them out.
     */
    private fun expandPositions(item: GuiItemData): List<Pair<Int, Int>> =
        GuiSlotBuilder.expandPositions(item)
}
