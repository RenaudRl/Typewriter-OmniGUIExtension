package btcrenaud.gui.api

import kotlin.test.Test
import kotlin.test.assertEquals

class RepaintPlanTest {

    /** A menu inventory of strings; null is an empty cell. Enough to replay a refresh and a close. */
    private class FakeMenu(private val pickup: Set<Int>) {
        val cells = mutableMapOf<Int, String>()
        private var last: Map<Int, String> = emptyMap()

        fun render(next: Map<Int, String>) {
            val writes = RepaintPlan.changes(last, next, pickup) { cells[it] == null }
            for ((index, item) in writes) if (item == null) cells.remove(index) else cells[index] = item
            last = next
        }

        fun playerTakes(index: Int): String? = cells.remove(index)

        /** The close: every cell is read once and emptied before the item is handed over. */
        fun close(delivered: MutableList<String>) {
            for (index in pickup.sorted()) cells.remove(index)?.let(delivered::add)
            cells.clear()
        }
    }

    @Test
    fun `an item the player took is not shown again by a refresh, nor handed back at close`() {
        val menu = FakeMenu(pickup = setOf(4))
        menu.render(mapOf(4 to "loot, 10s left"))

        val taken = menu.playerTakes(4)
        menu.render(mapOf(4 to "loot, 9s left"))
        val delivered = mutableListOf<String>()
        menu.close(delivered)

        assertEquals("loot, 10s left", taken)
        assertEquals(emptyList(), delivered)
    }

    @Test
    fun `an untouched pickup cell is refreshed and handed back once`() {
        val menu = FakeMenu(pickup = setOf(4))
        menu.render(mapOf(4 to "loot, 10s left"))
        menu.render(mapOf(4 to "loot, 9s left"))
        val delivered = mutableListOf<String>()

        menu.close(delivered)
        menu.close(delivered)

        assertEquals(listOf("loot, 9s left"), delivered)
    }

    @Test
    fun `a decoration cell is repainted even when it is empty`() {
        val menu = FakeMenu(pickup = emptySet())
        menu.render(mapOf(0 to "frame 1"))
        menu.cells.clear()

        menu.render(mapOf(0 to "frame 2"))

        assertEquals(mapOf(0 to "frame 2"), menu.cells)
    }

    @Test
    fun `a pickup cell that was empty at the last paint is filled when an item arrives`() {
        val menu = FakeMenu(pickup = setOf(2))
        menu.render(emptyMap())

        menu.render(mapOf(2 to "new loot"))

        assertEquals(mapOf(2 to "new loot"), menu.cells)
    }

    @Test
    fun `a cell that disappears from the picture is cleared`() {
        val writes = RepaintPlan.changes(mapOf(1 to "a", 2 to "b"), mapOf(2 to "b"), emptySet()) { false }

        assertEquals(mapOf<Int, String?>(1 to null), writes)
    }
}
