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

    @Test
    fun `no cell means no roll`() {
        var rolls = 0

        assertEquals(emptyList(), SlotRoll.draw(0, rollPerSlot = true) { ++rolls })
        assertEquals(emptyList(), SlotRoll.draw(0, rollPerSlot = false) { ++rolls })
        assertEquals(0, rolls)
    }
}
