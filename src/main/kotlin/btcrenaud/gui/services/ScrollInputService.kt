package btcrenaud.gui.services

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType.Play
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.concurrent.ConcurrentHashMap

/**
 * Detects mouse-wheel scroll in GUI menus by intercepting hotbar slot changes.
 *
 * When a player scrolls while a menu is open, the client sends
 * [WrapperPlayClientHeldItemChange]. The vanilla hotbar change is cancelled and the scroll
 * direction forwarded to [MenuSessionService.scroll] instead.
 *
 * Folia-safe: ConcurrentHashMap for per-player slot tracking.
 */
object ScrollInputService : PacketListenerAbstract() {
    /** Last known held slot per player, for direction detection. */
    private val lastSlot = ConcurrentHashMap<java.util.UUID, Int>()

    fun initialize(plugin: Plugin) {
        PacketEvents.getAPI().eventManager.registerListener(this)
    }

    fun shutdown() {
        try {
            PacketEvents.getAPI().eventManager.unregisterListener(this)
        } catch (_: Exception) { /* already unregistered */ }
        lastSlot.clear()
    }

    override fun onPacketReceive(event: PacketReceiveEvent?) {
        if (event == null) return
        if (event.packetType != Play.Client.HELD_ITEM_CHANGE) return

        val player = event.getPlayer<Player>() ?: return

        if (!MenuSessionService.hasActiveSession(player)) return

        val packet = WrapperPlayClientHeldItemChange(event)
        val previous = lastSlot[player.uniqueId] ?: player.inventory.heldItemSlot
        val current = packet.slot

        if (current == previous) return

        lastSlot[player.uniqueId] = current

        // Direction: 0→1 = down(+1), 8→0 = wrap down(+1), etc.
        val direction = when {
            current > previous && (current - previous) <= 4 -> 1
            current < previous && (previous - current) <= 4 -> -1
            current == 0 && previous == 8 -> 1
            current == 8 && previous == 0 -> -1
            else -> 0
        }

        if (direction != 0) {
            event.isCancelled = true
            MenuSessionService.scroll(player, 0, -direction)
        }
    }
}
