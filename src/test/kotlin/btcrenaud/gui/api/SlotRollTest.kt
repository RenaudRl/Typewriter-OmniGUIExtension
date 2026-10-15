package btcrenaud.gui.api

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SlotRollTest {

    @Test
    fun `without rollPerSlot the item is rolled once and shared by every cell`() {
        var rolls = 0
        val drawn = SlotRoll.draw(27, rollPerSlot = false) { ++rolls }

        assertEquals(1, rolls)
        assertEquals(27, drawn.size)
        assertEquals(setOf(1), drawn.toSet())
    }

    @Test
    fun `with rollPerSlot every cell rolls on its own`() {
        var rolls = 0
        val drawn = SlotRoll.draw(27, rollPerSlot = true) { ++rolls }

        assertEquals(27, rolls)
        assertEquals((1..27).toList(), drawn)
    }

    @Test
    fun `rollPerSlot can give distinct draws from a random source`() {
        val random = Random(42)
        val drawn = SlotRoll.draw(27, rollPerSlot = true) { random.nextInt(1, 1000) }

        assertTrue(drawn.toSet().size > 1, "27 independent draws from 1..999 must not all collide")
    }

    // A render only decorates the kept draw. The name comes from a value that changes per render
    // (index = render number); a blank one is no name, so the draw is shown as it is.
    private val nameAtRender = listOf("A", "", "   ", "B")
    private fun decorate(draw: String, render: Int): String =
        (nameOverride(nameAtRender[render]) ?: "-") + "#" + draw

    @Test
    fun `renders follow a name that turns blank and back without ever rolling again`() {
        for (rollPerSlot in listOf(false, true)) {
            var rolls = 0
            val drawn = SlotRoll.draw(3, rollPerSlot) { "d${++rolls}" }
            val refreshers = drawn.map { SlotRoll.refresher<String, Int>(it) { d, r -> decorate(d, r) } }
            val rollsAtOpen = rolls

            val renders = nameAtRender.indices.map { render -> refreshers.map { it(render) } }

            assertEquals(rollsAtOpen, rolls, "rollPerSlot=$rollPerSlot: a render must never roll")
            assertEquals(listOf("A", "-", "-", "B"), renders.map { it.first().substringBefore('#') })
            assertEquals(drawn, renders.last().map { it.substringAfter('#') })
        }
    }

    @Test
    fun `without rollPerSlot every cell keeps showing the one shared draw after refreshes`() {
        var rolls = 0
        val drawn = SlotRoll.draw(3, rollPerSlot = false) { "d${++rolls}" }
        val refreshers = drawn.map { SlotRoll.refresher<String, Int>(it) { d, r -> decorate(d, r) } }

        repeat(nameAtRender.size) { render ->
            assertEquals(1, refreshers.map { it(render).substringAfter('#') }.toSet().size)
        }
    }

    // liveItem: a paint visits the cells in order and each paint rolls again.
    private fun paints(live: SlotRoll.LiveDraw<String>, roll: () -> String, cells: List<Int>, count: Int) =
        List(count) { cells.map { cell -> live.next(cell, roll) } }

    @Test
    fun `liveItem with rollPerSlot rolls once per cell at every paint`() {
        var rolls = 0
        val painted = paints(SlotRoll.LiveDraw(rollPerSlot = true), { "d${++rolls}" }, listOf(0, 1, 2), 3)

        assertEquals(9, rolls)
        assertEquals(listOf("d1", "d2", "d3"), painted.first())
        assertEquals(listOf("d7", "d8", "d9"), painted.last())
    }

    @Test
    fun `liveItem without rollPerSlot rolls once per paint and shares it between the cells`() {
        var rolls = 0
        val painted = paints(SlotRoll.LiveDraw(rollPerSlot = false), { "d${++rolls}" }, listOf(0, 1, 2), 3)

        assertEquals(3, rolls)
        assertEquals(listOf(listOf("d1", "d1", "d1"), listOf("d2", "d2", "d2"), listOf("d3", "d3", "d3")), painted)
    }

    @Test
    fun `liveItem rolls again at every paint even when only one cell is visible`() {
        var rolls = 0
        val painted = paints(SlotRoll.LiveDraw(rollPerSlot = false), { "d${++rolls}" }, listOf(4), 3)

        assertEquals(listOf(listOf("d1"), listOf("d2"), listOf("d3")), painted)
    }

    @Test
    fun `liveItem still follows a blank name while the item changes`() {
        var rolls = 0
        val live = SlotRoll.LiveDraw<String>(rollPerSlot = false)

        val renders = nameAtRender.indices.map { render -> decorate(live.next(0) { "d${++rolls}" }, render) }

        assertEquals(listOf("A#d1", "-#d2", "-#d3", "B#d4"), renders)
    }

    @Test
    fun `no cell means no roll`() {
        var rolls = 0

        assertEquals(emptyList(), SlotRoll.draw(0, rollPerSlot = true) { ++rolls })
        assertEquals(emptyList(), SlotRoll.draw(0, rollPerSlot = false) { ++rolls })
        assertEquals(0, rolls)
    }
}
