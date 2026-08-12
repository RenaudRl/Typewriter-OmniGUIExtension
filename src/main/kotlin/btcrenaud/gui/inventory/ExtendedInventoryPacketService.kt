package btcrenaud.gui.inventory

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.item.ItemStack
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenWindow
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack as BukkitItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps the visual player-inventory rows of an extended GUI authoritative at packet level.
 *
 * The Bukkit inventory still contains the player's real items. The open chest window receives
 * a separate, client-side projection for raw slots 54..89. This listener reapplies that
 * projection whenever the server sends a complete container snapshot or a single slot update.
 *
 * PacketEvents is intentionally used here instead of CraftBukkit/NMS: the container id is
 * learned from the public OPEN_WINDOW packet and item conversion preserves components and
 * custom model data through PacketEvents' reflection bridge — so the same jar keeps working
 * across server versions and forks.
 */
object ExtendedInventoryPacketService : PacketListenerAbstract(PacketListenerPriority.HIGHEST) {
    private data class WindowState(
        val windowId: Int = -1,
        val packetStateId: Int = 0,
        val items: Map<Int, ItemStack> = emptyMap(),
    )

    private val states = ConcurrentHashMap<UUID, WindowState>()

    fun initialize() {
        PacketEvents.getAPI().eventManager.registerListener(this)
    }

    /** Starts/replaces the client-side bottom projection before the next window opens. */
    fun activate(player: Player, items: Map<Int, BukkitItemStack>) {
        val encoded = buildMap(PLAYER_SLOT_COUNT) {
            for (rawSlot in FIRST_PLAYER_SLOT until EXTENDED_SLOT_COUNT) {
                val bukkitItem = items[rawSlot]
                put(rawSlot, encode(bukkitItem))
            }
        }
        states.compute(player.uniqueId) { _, previous ->
            WindowState(
                windowId = previous?.windowId ?: -1,
                packetStateId = previous?.packetStateId ?: 0,
                items = encoded,
            )
        }
    }

    fun deactivate(player: Player) {
        states.remove(player.uniqueId)
    }

    fun shutdown() {
        try {
            PacketEvents.getAPI().eventManager.unregisterListener(this)
        } catch (_: Exception) {
            // PacketEvents may already be shutting down.
        }
        states.clear()
    }

    override fun onPacketSend(event: PacketSendEvent?) {
        if (event == null) return
        // PacketEvents also forwards connection-level client-bound packets for which no Bukkit
        // player can be resolved yet (or anymore). Those packets are irrelevant to a GUI
        // projection and must never abort the encoder pipeline with Kotlin's non-null assertion.
        val player = runCatching { event.getPlayer<Player>() }.getOrNull() ?: return
        val current = states[player.uniqueId] ?: return

        when (event.packetType) {
            PacketType.Play.Server.OPEN_WINDOW -> {
                val packet = WrapperPlayServerOpenWindow(event)
                states.computeIfPresent(player.uniqueId) { _, state ->
                    state.copy(windowId = packet.containerId)
                }
            }

            PacketType.Play.Server.WINDOW_ITEMS -> {
                val packet = WrapperPlayServerWindowItems(event)
                if (packet.windowId != current.windowId) return

                val items = packet.items.toMutableList()
                // A custom chest window is opened with 54 top slots, but the client-side
                // container also contains the player's 36 slots. Some server paths emit a
                // WINDOW_ITEMS list containing only the top inventory. Extend it before
                // applying the projection; otherwise raw slots 54..89 are silently absent
                // and the lower GUI rows can never be displayed.
                while (items.size < EXTENDED_SLOT_COUNT) {
                    items.add(ItemStack.EMPTY)
                }
                for (rawSlot in FIRST_PLAYER_SLOT until EXTENDED_SLOT_COUNT) {
                    items[rawSlot] = current.items[rawSlot] ?: ItemStack.EMPTY
                }
                packet.items = items
                states.computeIfPresent(player.uniqueId) { _, state ->
                    state.copy(packetStateId = packet.stateId)
                }
            }

            PacketType.Play.Server.SET_SLOT -> {
                val packet = WrapperPlayServerSetSlot(event)
                if (packet.windowId != current.windowId) return
                if (packet.slot !in FIRST_PLAYER_SLOT until EXTENDED_SLOT_COUNT) return
                packet.item = current.items[packet.slot] ?: ItemStack.EMPTY
                states.computeIfPresent(player.uniqueId) { _, state ->
                    state.copy(packetStateId = packet.stateId)
                }
            }
        }
    }

    /** Sends the current projection after GuiFactory has opened/updated the chest window. */
    fun sendCurrentProjection(player: Player) {
        val state = states[player.uniqueId] ?: return
        if (state.windowId < 0) return

        for (rawSlot in FIRST_PLAYER_SLOT until EXTENDED_SLOT_COUNT) {
            PacketEvents.getAPI().playerManager.sendPacket(
                player,
                WrapperPlayServerSetSlot(
                    state.windowId,
                    state.packetStateId,
                    rawSlot,
                    state.items[rawSlot] ?: ItemStack.EMPTY,
                )
            )
        }
    }

    /** Re-sends one projected slot after the client-side click prediction is cancelled. */
    fun sendProjectionSlot(player: Player, rawSlot: Int) {
        if (rawSlot !in FIRST_PLAYER_SLOT until EXTENDED_SLOT_COUNT) return
        val state = states[player.uniqueId] ?: return
        if (state.windowId < 0) return

        PacketEvents.getAPI().playerManager.sendPacket(
            player,
            WrapperPlayServerSetSlot(
                state.windowId,
                state.packetStateId,
                rawSlot,
                state.items[rawSlot] ?: ItemStack.EMPTY,
            )
        )
    }

    private fun encode(item: BukkitItemStack?): ItemStack =
        if (item == null || item.type.isAir) {
            ItemStack.EMPTY
        } else {
            decodeWithPacketEvents(item) ?: runCatching {
                val type = ItemTypes.getByName("minecraft:${item.type.name.lowercase()}")
                    ?: ItemTypes.getByName(item.type.name.lowercase())
                    ?: return@runCatching ItemStack.EMPTY
                ItemStack.builder().type(type).amount(item.amount).build()
            }.getOrDefault(ItemStack.EMPTY)
        }

    /**
     * PacketEvents 2.11 does not expose SpigotReflectionUtil in its API artifact, while newer
     * builds do. Resolve it only when available so the extension remains binary-compatible with
     * every supported runtime; the fallback still renders the material and amount if an older
     * runtime has no metadata bridge.
     */
    private fun decodeWithPacketEvents(item: BukkitItemStack): ItemStack? = runCatching {
        val type = sequenceOf(
            "io.github.retrooper.packetevents.util.SpigotReflectionUtil",
            "com.github.retrooper.packetevents.util.SpigotReflectionUtil",
        ).mapNotNull { className -> runCatching { Class.forName(className) }.getOrNull() }
            .firstOrNull() ?: return@runCatching null
        val method = type.getMethod("decodeBukkitItemStack", BukkitItemStack::class.java)
        method.invoke(null, item) as ItemStack
    }.getOrNull()

    private const val PLAYER_SLOT_COUNT = 36
    private const val FIRST_PLAYER_SLOT = 54
    private const val EXTENDED_SLOT_COUNT = FIRST_PLAYER_SLOT + PLAYER_SLOT_COUNT
}
