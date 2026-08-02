package btcrenaud.gui.api

import btcrenaud.gui.services.MenuSessionService
import com.typewritermc.engine.paper.logger
import kotlinx.serialization.Serializable

@Serializable
enum class FlexJustify { START, CENTER, END, SPACE_BETWEEN }
@Serializable
enum class FlexAlign { START, CENTER, END }

/**
 * A layout that automatically positions slots using Flexbox-like logic for fixed grids.
 */
class FlexLayout(
    val slots: List<GuiSlot>,
    val justifyContent: FlexJustify = FlexJustify.START,
    val alignItems: FlexAlign = FlexAlign.START,
    val wrap: Boolean = true,
    override val id: String? = null,
    override val virtualWidth: Int = 9,
    override val virtualHeight: Int = 6
) : MenuLayout {

    override fun getSlots(session: MenuSessionService.ActiveSession, viewport: Viewport): List<GuiSlot> {
        if (slots.isEmpty()) return emptyList()

        val result = mutableListOf<GuiSlot>()
        val rows = mutableListOf<MutableList<GuiSlot>>()
        var currentRow = mutableListOf<GuiSlot>()
        var curX = 0

        slots.forEach { slot ->
            if (wrap && curX >= virtualWidth) {
                rows.add(currentRow)
                currentRow = mutableListOf()
                curX = 0
            }
            currentRow.add(slot)
            curX++
        }
        if (currentRow.isNotEmpty()) rows.add(currentRow)

        // Vertical Alignment (AlignItems)
        val totalHeight = rows.size
        val verticalOffset = when (alignItems) {
            FlexAlign.START -> 0
            FlexAlign.CENTER -> (virtualHeight - totalHeight) / 2
            FlexAlign.END -> virtualHeight - totalHeight
        }

        // Apply justification to each row
        rows.forEachIndexed { rowIndex, row ->
            val rowY = rowIndex + verticalOffset
            if (rowY < 0 || rowY >= virtualHeight) return@forEachIndexed

            val freeSpace = virtualWidth - row.size
            val startX = when (justifyContent) {
                FlexJustify.START -> 0
                FlexJustify.CENTER -> freeSpace / 2
                FlexJustify.END -> freeSpace
                FlexJustify.SPACE_BETWEEN -> 0
            }

            val gap = if (justifyContent == FlexJustify.SPACE_BETWEEN && row.size > 1) {
                freeSpace.toDouble() / (row.size - 1)
            } else 0.0

            val itemWidth = 1.0
            var currentX = startX.toDouble()
            for (item in row) {
                val x = currentX.toInt()
                if (x in 0 until virtualWidth) {
                    result.add(item.copy(x = x, y = rowY))
                } else if (x >= virtualWidth) {
                    logger.warning("[GUI-Editor] FlexLayout: item silently dropped at x=$x (>= virtualWidth=$virtualWidth). Consider reducing items or enabling wrap.")
                }
                currentX += itemWidth + gap
            }
        }

        return result
    }
}
