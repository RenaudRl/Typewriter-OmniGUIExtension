package btcrenaud.gui.editor.states

import btcrenaud.gui.api.GuiSlot
import btcrenaud.gui.api.MenuLayout
import btcrenaud.gui.api.Viewport
import btcrenaud.gui.services.MenuSessionService
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta

/**
 * Wraps a [MenuLayout] with state-based visual overrides.
 *
 * At render time, resolves the active state for the player and applies
 * layer overrides (visibility, texture, text, color, etc.) before
 * returning the slot list.
 */
class StateAwareLayout(
    private val menuId: String,
    private val entryId: String,
    private val states: Map<String, MenuStateDefinition>,
    private val baseLayout: MenuLayout
) : MenuLayout {

    private val evaluator = StateEvaluator()
    override val id: String? get() = baseLayout.id
    override val virtualWidth: Int get() = baseLayout.virtualWidth
    override val virtualHeight: Int get() = baseLayout.virtualHeight

    override fun getSlots(session: MenuSessionService.ActiveSession, viewport: Viewport): List<GuiSlot> {
        val activeState = evaluator.resolveActiveState(states, session.player, entryId)
        val activeSubStates = activeState?.let {
            evaluator.resolveActiveSubStates(it, session.player, entryId)
        } ?: emptyList()

        // Start with base slots
        val baseSlots = baseLayout.getSlots(session, viewport)

        if (activeState == null) return baseSlots

        // Collect all overrides: parent first, then state, then sub-states
        val allOverrides = mutableMapOf<String, LayerOverride>()

        // Apply parent state overrides (inheritance)
        activeState.parentId?.let { parentId ->
            states[parentId]?.layerOverrides?.let { allOverrides.putAll(it) }
        }

        // Apply current state overrides
        allOverrides.putAll(activeState.layerOverrides)

        // Apply sub-state overrides (later sub-states override earlier ones)
        for (sub in activeSubStates) {
            allOverrides.putAll(sub.layerOverrides)
        }

        if (allOverrides.isEmpty()) return baseSlots

        // Apply overrides to each slot
        return baseSlots.map { slot ->
            applyOverrides(slot, allOverrides)
        }
    }

    private fun applyOverrides(slot: GuiSlot, overrides: Map<String, LayerOverride>): GuiSlot {
        val slotKey = (slot.x + slot.y * virtualWidth).toString()
        val override = overrides[slotKey] ?: return slot

        var item = slot.item.clone()
        val meta: ItemMeta = item.itemMeta ?: return slot

        // Apply visibility override — if not visible, return AIR
        if (override.visible == false) {
            return slot.copy(item = ItemStack(org.bukkit.Material.AIR))
        }

        // Apply custom model data for texture swap
        override.src?.let { src ->
            src.toIntOrNull()?.let { cmd ->
                @Suppress("DEPRECATION")
                meta.setCustomModelData(cmd)
            }
        }

        // Apply display name override
        override.text?.let { text ->
            val resolved = resolveMiniMessage(text)
            meta.displayName(resolved)
        }

        // Apply color override
        override.color?.let { colorHex ->
            val resolved = resolveMiniMessage("<color:$colorHex>")
            meta.displayName(resolved)
        }

        item.itemMeta = meta
        return slot.copy(item = item)
    }

    private fun resolveMiniMessage(text: String): net.kyori.adventure.text.Component {
        return runCatching {
            MiniMessage.miniMessage().deserialize(text)
        }.getOrDefault(net.kyori.adventure.text.Component.text(text))
    }
}
