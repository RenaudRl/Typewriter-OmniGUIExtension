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
    @Help("Enable the web-based GUI editor (Ktor HTTP :8082 + Socket.IO :9093).\nDisabled by default for security and performance.")
    val editorEnabled: Boolean = false,
) : ManifestEntry {
    override val name: String get() = "gui_config"
}
