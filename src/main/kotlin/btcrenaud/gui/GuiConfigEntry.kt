package btcrenaud.gui

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.engine.paper.entry.ManifestEntry

/**
 * Global configuration entry for the GUI extension.
 * Set [debug] to true to enable verbose logging for scroll, command, and animation events.
 */
@Entry("gui_config", "GUI Extension Configuration", Colors.BLUE, "mdi:cog")
class GuiConfigEntry(
    override val id: String = "default",
    @Help("Enable debug logging for GUI internal commands, scroll events, and animations.")
    val debug: Boolean = false,
    @Help("Hide vanilla tooltip lines (attack damage, armour, durability, enchantment lists…) on menu buttons.\nSlots the player can take from are never touched.")
    val hideVanillaItemStats: Boolean = true,
) : ManifestEntry {
    override val name: String get() = "gui_config"
}
