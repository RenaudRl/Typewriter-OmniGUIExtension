package btcrenaud.gui.api

import btcrenaud.gui.ScrollDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Guards which default arrows a scrollable layout gets, and where they go. */
class ScrollArrowsTest {

    private data class Override(
        override val direction: ScrollDirection,
        override val name: String? = null,
        override val x: Int? = null,
        override val y: Int? = null,
    ) : ScrollArrowPlacement

    @Test
    fun `no default arrow at all when showDefaultButtons is off`() {
        assertTrue(ScrollArrows.missingDirections(emptyList(), showDefaultButtons = false).isEmpty())
    }

    @Test
    fun `every direction gets an arrow when the author declared none`() {
        assertEquals(
            ScrollDirection.entries.toList(),
            ScrollArrows.missingDirections(emptyList(), showDefaultButtons = true),
        )
    }

    @Test
    fun `a direction the author already covers keeps their button and gets no default`() {
        val missing = ScrollArrows.missingDirections(
            listOf(ScrollDirection.UP, ScrollDirection.LEFT),
            showDefaultButtons = true,
        )

        assertEquals(listOf(ScrollDirection.DOWN, ScrollDirection.RIGHT), missing)
    }

    @Test
    fun `without override the arrows sit on the right column and the bottom row`() {
        fun place(direction: ScrollDirection) =
            ScrollArrows.spec(direction, emptyList(), totalSize = 54).let { it.x to it.y }

        assertEquals(8 to 0, place(ScrollDirection.UP))
        assertEquals(8 to 5, place(ScrollDirection.DOWN))
        assertEquals(0 to 5, place(ScrollDirection.LEFT))
        assertEquals(1 to 5, place(ScrollDirection.RIGHT))
        assertTrue(ScrollArrows.spec(ScrollDirection.UP, emptyList(), 54).name.isNotEmpty())
    }

    @Test
    fun `the bottom row follows the size of the menu`() {
        assertEquals(2, ScrollArrows.spec(ScrollDirection.DOWN, emptyList(), totalSize = 27).y)
    }

    @Test
    fun `an override replaces only what it sets`() {
        val overrides = listOf(Override(ScrollDirection.DOWN, name = "<gold>More", x = 4))

        val down = ScrollArrows.spec(ScrollDirection.DOWN, overrides, totalSize = 54)

        assertEquals("<gold>More", down.name)
        assertEquals(4, down.x)
        assertEquals(5, down.y)
    }

    @Test
    fun `an override of another direction changes nothing`() {
        val overrides = listOf(Override(ScrollDirection.UP, x = 0, y = 0))

        val down = ScrollArrows.spec(ScrollDirection.DOWN, overrides, totalSize = 54)

        assertEquals(8 to 5, down.x to down.y)
    }

    @Test
    fun `an empty name falls back to the default symbol`() {
        val overrides = listOf(Override(ScrollDirection.UP, name = ""))

        assertTrue(ScrollArrows.spec(ScrollDirection.UP, overrides, totalSize = 54).name.isNotEmpty())
    }

    @Test
    fun `commands match the ones of the custom buttons`() {
        assertEquals("gui:scroll 0 -1 inv", ScrollArrows.command(ScrollDirection.UP, "inv"))
        assertEquals("gui:scroll 0 1 inv", ScrollArrows.command(ScrollDirection.DOWN, "inv"))
        assertEquals("gui:scroll -1 0 inv", ScrollArrows.command(ScrollDirection.LEFT, "inv"))
        assertEquals("gui:scroll 1 0 inv", ScrollArrows.command(ScrollDirection.RIGHT, "inv"))
    }
}
