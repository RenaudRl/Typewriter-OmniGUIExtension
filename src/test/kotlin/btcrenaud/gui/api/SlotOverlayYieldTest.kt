package btcrenaud.gui.api

import kotlin.test.Test
import kotlin.test.assertEquals

/** Guards that a default scroll arrow only exists on a cell nothing else uses. */
class SlotOverlayYieldTest {

    private data class Cell(val name: String, val x: Int, val y: Int, val arrow: Boolean = false, val solid: Boolean = true)

    private fun keep(vararg cells: Cell): List<String> =
        SlotOverlay.dropYielding(cells.toList(), { it.x }, { it.y }, { it.arrow }, { it.solid }).map { it.name }

    @Test
    fun `an arrow on a free cell is kept`() {
        assertEquals(listOf("content", "arrow"), keep(Cell("content", 0, 0), Cell("arrow", 8, 0, arrow = true)))
    }

    @Test
    fun `an arrow never covers content or a button`() {
        assertEquals(listOf("content"), keep(Cell("content", 8, 0), Cell("arrow", 8, 0, arrow = true)))
    }

    @Test
    fun `the arrow gives way whatever the declaration order`() {
        assertEquals(listOf("button"), keep(Cell("arrow", 8, 5, arrow = true), Cell("button", 8, 5)))
    }

    @Test
    fun `an empty cell does not take the place of an arrow`() {
        assertEquals(
            listOf("air", "arrow"),
            keep(Cell("air", 8, 0, solid = false), Cell("arrow", 8, 0, arrow = true)),
        )
    }

    @Test
    fun `only the covered arrow is dropped`() {
        assertEquals(
            listOf("content", "down"),
            keep(Cell("content", 8, 0), Cell("up", 8, 0, arrow = true), Cell("down", 8, 5, arrow = true)),
        )
    }

    @Test
    fun `a layout without arrows is returned untouched`() {
        val cells = listOf(Cell("a", 0, 0), Cell("b", 0, 0))

        assertEquals(cells, SlotOverlay.dropYielding(cells, { it.x }, { it.y }, { it.arrow }, { it.solid }))
    }
}
