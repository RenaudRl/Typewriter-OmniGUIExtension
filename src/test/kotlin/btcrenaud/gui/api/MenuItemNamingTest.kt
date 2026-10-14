package btcrenaud.gui.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MenuItemNamingTest {

    @Test
    fun `a declared name is kept as is`() {
        assertEquals("<red>Row 1", nameOverride("<red>Row 1"))
    }

    @Test
    fun `an empty string leaves the item name component intact`() {
        assertNull(nameOverride(""))
    }

    @Test
    fun `a blank string leaves the item name component intact`() {
        assertNull(nameOverride("   "))
    }

    @Test
    fun `no override stays no override`() {
        assertNull(nameOverride(null))
    }
}
