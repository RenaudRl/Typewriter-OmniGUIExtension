package btcrenaud.gui.api

import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/**
 * Public Bukkit events fired by the GUI engine so third-party plugins and
 * extensions can observe or veto menu activity without touching the core.
 *
 * All events fire on the player's region thread (Folia-safe listeners only).
 */

/** Fired before a menu session opens. Cancel to prevent the menu from opening. */
class GuiOpenEvent(
    val player: Player,
    val definition: MenuDefinition,
) : Event(), Cancellable {
    private var cancelled = false
    override fun isCancelled(): Boolean = cancelled
    override fun setCancelled(cancel: Boolean) {
        cancelled = cancel
    }

    override fun getHandlers(): HandlerList = handlerList

    companion object {
        @JvmStatic
        val handlerList = HandlerList()
    }
}

/**
 * Fired when a player clicks a GUI slot, before any commands/triggers run.
 * Cancel to swallow the interaction entirely.
 */
class GuiClickEvent(
    val player: Player,
    val definition: MenuDefinition,
    val slot: GuiSlot?,
    val interaction: InteractionType,
) : Event(), Cancellable {
    private var cancelled = false
    override fun isCancelled(): Boolean = cancelled
    override fun setCancelled(cancel: Boolean) {
        cancelled = cancel
    }

    override fun getHandlers(): HandlerList = handlerList

    companion object {
        @JvmStatic
        val handlerList = HandlerList()
    }
}

/** Fired after a menu session ends (close, quit, or replacement). Informational. */
class GuiCloseEvent(
    val player: Player,
    val definition: MenuDefinition,
) : Event() {
    override fun getHandlers(): HandlerList = handlerList

    companion object {
        @JvmStatic
        val handlerList = HandlerList()
    }
}
