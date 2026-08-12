package btcrenaud.gui.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * This formula is shared by the render path and by the editor validation. A drift here means an
 * editor that green-lights a menu the server draws somewhere else, which is exactly the class of
 * bug that made repeated markers land outside their layout without a word.
 */
class SlotRepetitionTest {

    @Test
    fun `without a direction the slot stays single`() {
        assertEquals(listOf(3 to 2), SlotRepetition.expand(3, 2, null, count = 5, gap = 2, repeatY = 4))
        assertEquals(listOf(3 to 2), SlotRepetition.expand(3, 2, "", count = 5, gap = 2, repeatY = 4))
    }

    @Test
    fun `an unknown direction never multiplies the slot`() {
        assertEquals(listOf(0 to 0), SlotRepetition.expand(0, 0, "sideways", count = 9, gap = 1, repeatY = 1))
    }

    @Test
    fun `count repeats along the direction`() {
        assertEquals(
            listOf(1 to 0, 2 to 0, 3 to 0),
            SlotRepetition.expand(1, 0, "right", count = 3, gap = 1, repeatY = 1),
        )
        assertEquals(
            listOf(4 to 0, 3 to 0),
            SlotRepetition.expand(4, 0, "left", count = 2, gap = 1, repeatY = 1),
        )
    }

    @Test
    fun `gap is a step, not a spacing`() {
        assertEquals(
            listOf(0 to 0, 2 to 0, 4 to 0),
            SlotRepetition.expand(0, 0, "right", count = 3, gap = 2, repeatY = 1),
        )
    }

    @Test
    fun `repeatY repeats on the axis perpendicular to the direction`() {
        // right => the block repeats DOWNWARDS
        assertEquals(
            listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1),
            SlotRepetition.expand(0, 0, "right", count = 2, gap = 1, repeatY = 2),
        )
        // down => the block repeats to the RIGHT
        assertEquals(
            listOf(0 to 0, 0 to 1, 1 to 0, 1 to 1),
            SlotRepetition.expand(0, 0, "down", count = 2, gap = 1, repeatY = 2),
        )
    }

    @Test
    fun `zero counts are treated as one, never as nothing`() {
        assertEquals(listOf(2 to 2), SlotRepetition.expand(2, 2, "right", count = 0, gap = 1, repeatY = 0))
    }

    @Test
    fun `repetition settings without a direction are reported`() {
        assertTrue(SlotRepetition.hasOrphanRepetition(null, count = 4, gap = 1, repeatY = 1))
        assertTrue(SlotRepetition.hasOrphanRepetition(null, count = 1, gap = 2, repeatY = 1))
        assertTrue(SlotRepetition.hasOrphanRepetition("", count = 1, gap = 1, repeatY = 3))
        assertFalse(SlotRepetition.hasOrphanRepetition(null, count = 1, gap = 1, repeatY = 1))
        assertFalse(SlotRepetition.hasOrphanRepetition("right", count = 4, gap = 2, repeatY = 2))
    }
}
