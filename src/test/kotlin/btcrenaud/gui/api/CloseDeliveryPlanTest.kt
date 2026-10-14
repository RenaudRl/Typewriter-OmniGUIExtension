package btcrenaud.gui.api

import btcrenaud.gui.api.CloseDeliveryPlan.Cell
import kotlin.test.Test
import kotlin.test.assertEquals

class CloseDeliveryPlanTest {

    private fun cell(
        x: Int,
        y: Int,
        pickup: Boolean = true,
        ghost: Boolean = false,
        delivery: CloseDelivery? = CloseDelivery.GIVE_OR_DROP,
    ) = Cell(x, y, pickup, ghost, delivery)

    private fun indices(vararg cells: Cell, size: Int = 54, viewportX: Int = 0, viewportY: Int = 0) =
        CloseDeliveryPlan.plan(cells.toList(), viewportX, viewportY, size).map { it.index }

    @Test
    fun `an opted-in pickup cell is released at its physical index`() {
        assertEquals(listOf(2 * 9 + 3), indices(cell(3, 2)))
    }

    @Test
    fun `a decoration cell is never released`() {
        assertEquals(emptyList(), indices(cell(0, 0, pickup = false)))
    }

    @Test
    fun `a ghost cell is never released even when it allows pickup`() {
        assertEquals(emptyList(), indices(cell(0, 0, ghost = true)))
    }

    @Test
    fun `a cell that did not opt in is left alone`() {
        assertEquals(emptyList(), indices(cell(0, 0, delivery = null)))
    }

    @Test
    fun `cells come back in ascending order, one per index`() {
        val release = CloseDeliveryPlan.plan(
            listOf(cell(5, 1), cell(0, 0), cell(5, 1, delivery = CloseDelivery.DROP)),
            viewportX = 0, viewportY = 0, inventorySize = 54,
        )

        assertEquals(listOf(0, 14), release.map { it.index })
        assertEquals(CloseDelivery.GIVE_OR_DROP, release.last().delivery)
    }

    @Test
    fun `the delivery mode travels with the cell`() {
        val release = CloseDeliveryPlan.plan(listOf(cell(1, 0, delivery = CloseDelivery.DROP)), 0, 0, 54)

        assertEquals(CloseDelivery.DROP, release.single().delivery)
    }

    @Test
    fun `the viewport offset is applied like the render pass does`() {
        assertEquals(listOf(1 * 9 + 2), indices(cell(2, 3), viewportY = 2))
    }

    @Test
    fun `cells outside the visible window or the inventory are skipped`() {
        assertEquals(emptyList(), indices(cell(9, 0), cell(-1, 0), cell(0, -1), cell(0, 6), size = 54))
        assertEquals(emptyList(), indices(cell(0, 3), size = 27))
    }

    @Test
    fun `deliver gives into the inventory and drops only what did not fit`() {
        val given = mutableListOf<String>()
        val dropped = mutableListOf<String>()

        CloseDeliveryPlan.deliver("loot", CloseDelivery.GIVE_OR_DROP, { given += it; listOf("surplus") }, { dropped += it })

        assertEquals(listOf("loot"), given)
        assertEquals(listOf("surplus"), dropped)
    }

    @Test
    fun `deliver in DROP mode never touches the inventory`() {
        val given = mutableListOf<String>()
        val dropped = mutableListOf<String>()

        CloseDeliveryPlan.deliver("loot", CloseDelivery.DROP, { given += it; emptyList() }, { dropped += it })

        assertEquals(emptyList(), given)
        assertEquals(listOf("loot"), dropped)
    }

    @Test
    fun `a full inventory drops the whole stack and a roomy one drops nothing`() {
        val dropped = mutableListOf<String>()

        CloseDeliveryPlan.deliver("a", CloseDelivery.GIVE_OR_DROP, { listOf(it) }, { dropped += it })
        CloseDeliveryPlan.deliver("b", CloseDelivery.GIVE_OR_DROP, { emptyList() }, { dropped += it })

        assertEquals(listOf("a"), dropped)
    }
}
