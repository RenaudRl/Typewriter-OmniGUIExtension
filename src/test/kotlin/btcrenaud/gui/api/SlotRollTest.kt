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

    @Test
    fun `no cell means no roll`() {
        var rolls = 0

        assertEquals(emptyList(), SlotRoll.draw(0, rollPerSlot = true) { ++rolls })
        assertEquals(emptyList(), SlotRoll.draw(0, rollPerSlot = false) { ++rolls })
        assertEquals(0, rolls)
    }
}
