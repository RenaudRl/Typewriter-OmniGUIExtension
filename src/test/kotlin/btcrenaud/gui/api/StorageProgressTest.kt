package btcrenaud.gui.api

import btcrenaud.gui.entries.GuiTextSettings
import kotlin.test.Test
import kotlin.test.assertEquals

/** Guards the progress line of a storage slot and the defaults it starts from. */
class StorageProgressTest {

    private val defaults = GuiTextSettings()

    private fun line(settings: GuiTextSettings, current: Int, required: Int) = StorageProgress.line(
        template = settings.storageProgressLine,
        filledSegment = settings.progressBarFilled,
        emptySegment = settings.progressBarEmpty,
        length = settings.progressBarLength,
        current = current,
        required = required,
    )

    @Test
    fun `defaults give the line the extension always showed`() {
        assertEquals(
            "<gray>Progress: <white>3/10 " + "<green>■".repeat(3) + "<gray>■".repeat(7),
            line(defaults, current = 3, required = 10),
        )
    }

    @Test
    fun `the bar fills in whole segments rounded down`() {
        val settings = defaults.copy(storageProgressLine = "{bar}", progressBarFilled = "#", progressBarEmpty = "-")

        assertEquals("###-------", line(settings, current = 39, required = 100))
    }

    @Test
    fun `an over-collected slot shows a full bar and never a negative empty part`() {
        val settings = defaults.copy(storageProgressLine = "{bar}", progressBarFilled = "#", progressBarEmpty = "-")

        assertEquals("#".repeat(10), line(settings, current = 25, required = 10))
    }

    @Test
    fun `the bar length is the one set in the settings`() {
        val settings = defaults.copy(storageProgressLine = "{bar}", progressBarFilled = "#", progressBarEmpty = "-", progressBarLength = 4)

        assertEquals("##--", line(settings, current = 1, required = 2))
    }

    @Test
    fun `a template without tokens is kept as the admin wrote it`() {
        val settings = defaults.copy(storageProgressLine = "<aqua>Collecting")

        assertEquals("<aqua>Collecting", line(settings, current = 1, required = 2))
    }

    @Test
    fun `dialog buttons start as they were before the texts became editable`() {
        assertEquals("Confirm", defaults.inputConfirmLabel)
        assertEquals("<green>Confirm", defaults.confirmLabel)
        assertEquals("<red>Cancel", defaults.cancelLabel)
    }
}
