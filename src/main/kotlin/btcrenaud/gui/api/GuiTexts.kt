package btcrenaud.gui.api

import btcrenaud.gui.entries.GuiSettingEntry
import btcrenaud.gui.entries.GuiTextSettings
import com.typewritermc.core.entries.Query
import com.typewritermc.engine.paper.extensions.placeholderapi.parsePlaceholders
import com.typewritermc.engine.paper.utils.asMini
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player

/**
 * The texts the extension shows by itself (dialog buttons, storage progress line), read from the
 * `gui_settings` entry so an admin edits them in the web editor. Without that entry, or when it
 * cannot be read, the defaults of [GuiTextSettings] apply.
 */
object GuiTexts {

    private val LOGGER = java.util.logging.Logger.getLogger("Typewriter-OmniGUIExtension")

    fun settings(): GuiTextSettings =
        runCatching { Query.find<GuiSettingEntry>().firstOrNull()?.texts }
            .onFailure { LOGGER.warning("[OmniGUI] gui_settings could not be read, built-in texts are used: ${it.message}") }
            .getOrNull() ?: GuiTextSettings()

    /** Confirm button of a dialog with a text field. */
    fun inputConfirmLabel(player: Player): Component = render(player, settings().inputConfirmLabel)

    /** Confirm button of a yes/no dialog. */
    fun confirmLabel(player: Player): Component = render(player, settings().confirmLabel)

    /** Cancel button of a yes/no dialog. */
    fun cancelLabel(player: Player): Component = render(player, settings().cancelLabel)

    /** Progress lore line of a storage slot holding [current] of [required]. */
    fun storageProgress(player: Player, settings: GuiTextSettings, current: Int, required: Int): Component =
        render(
            player,
            StorageProgress.line(
                template = settings.storageProgressLine,
                filledSegment = settings.progressBarFilled,
                emptySegment = settings.progressBarEmpty,
                length = settings.progressBarLength,
                current = current,
                required = required,
            ),
        )

    private fun render(player: Player, text: String): Component = text.parsePlaceholders(player).asMini()
}
