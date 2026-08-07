package btcrenaud.gui.api

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The indicator is the only place a player is told where they are in a paginated menu: an
 * off-by-one or an out-of-range page reads as a broken menu, not as a rounding detail.
 */
class PageIndicatorTest {

    @Test
    fun `shows the current page one-indexed`() {
        assertEquals("1/3", PageIndicator.render(0, 3))
        assertEquals("3/3", PageIndicator.render(2, 3))
    }

    @Test
    fun `never displays a page outside the range`() {
        // A stale session page must not print "0/3" or "9/3" while the layout re-renders.
        assertEquals("1/3", PageIndicator.render(-4, 3))
        assertEquals("3/3", PageIndicator.render(8, 3))
        // An empty layout still has one page from the player's point of view.
        assertEquals("1/1", PageIndicator.render(0, 0))
    }

    @Test
    fun `caps the neighbour pages at the bounds`() {
        val first = PageIndicator.placeholders(0, 3)
        assertEquals("1", first["prev_page"])
        assertEquals("2", first["next_page"])

        val last = PageIndicator.placeholders(2, 3)
        assertEquals("2", last["prev_page"])
        assertEquals("3", last["next_page"])
    }

    @Test
    fun `substitutes every token in a name and in a lore`() {
        assertEquals(
            "Page 2 of 5",
            PageIndicator.apply("Page {page} of {total_pages}", 1, 5),
        )
        assertEquals(
            listOf("2/5", "next: 3"),
            PageIndicator.apply(listOf("{page_indicator}", "next: {next_page}"), 1, 5),
        )
    }

    @Test
    fun `leaves text without tokens untouched`() {
        assertEquals("Next page", PageIndicator.apply("Next page", 1, 5))
    }
}
