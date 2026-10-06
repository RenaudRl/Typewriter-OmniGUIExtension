package btcrenaud.gui.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Guards the `gui_settings.audioDefaults` fallback rule: per sound, and the action's own sound
 * wins. The rule lives in [SoundSet] (generic) so it runs here without the engine's Sound type.
 */
class MenuAudioFallbackTest {

    @Test
    fun `a sound the action leaves empty comes from the defaults`() {
        val merged = SoundSet<String>(null, null, null, null)
            .orElse(SoundSet(onOpen = "d-open", onClose = null, onScroll = null, onClick = "d-click"))

        assertEquals("d-open", merged.onOpen)
        assertEquals("d-click", merged.onClick)
    }

    @Test
    fun `a sound the action sets is never replaced by the defaults`() {
        val merged = SoundSet<String>("own-open", null, null, null)
            .orElse(SoundSet("d-open", "d-close", "d-scroll", "d-click"))

        assertEquals("own-open", merged.onOpen)
    }

    @Test
    fun `the fallback is per sound and each slot reads its own default`() {
        val merged = SoundSet<String>(null, "own-close", null, null)
            .orElse(SoundSet("d-open", "d-close", "d-scroll", "d-click"))

        assertEquals("d-open", merged.onOpen)
        assertEquals("own-close", merged.onClose)
        assertEquals("d-scroll", merged.onScroll)
        assertEquals("d-click", merged.onClick)
    }

    @Test
    fun `no defaults and no sound stays silent`() {
        val merged = SoundSet<String>(null, null, null, null).orElse(SoundSet(null, null, null, null))

        assertNull(merged.onOpen)
        assertNull(merged.onClose)
        assertNull(merged.onScroll)
        assertNull(merged.onClick)
    }
}
