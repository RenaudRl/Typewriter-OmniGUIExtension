package btcrenaud.gui.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A blank permission field reaching Bukkit is the bug this guards: an unregistered node defaults
 * to OP, so the slot silently disappeared for everyone but operators.
 */
class PermissionGateTest {

    @Test
    fun `an absent permission gates nothing`() {
        assertTrue(PermissionGate.admits(null) { fail("no permission should be looked up") })
    }

    @Test
    fun `a blank permission gates nothing`() {
        assertTrue(PermissionGate.admits("") { fail("no permission should be looked up") })
        assertTrue(PermissionGate.admits("   ") { fail("no permission should be looked up") })
    }

    @Test
    fun `a declared permission is asked of the holder`() {
        val asked = mutableListOf<String>()
        val holder: (String) -> Boolean = { asked += it; it == "menu.see" }

        assertTrue(PermissionGate.admits("menu.see", holder))
        assertFalse(PermissionGate.admits("menu.click", holder))
        assertEquals(listOf("menu.see", "menu.click"), asked)
    }

    private fun fail(message: String): Nothing = throw AssertionError(message)
}
