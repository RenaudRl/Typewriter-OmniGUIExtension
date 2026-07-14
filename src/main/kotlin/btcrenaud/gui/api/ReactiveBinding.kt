package btcrenaud.gui.api

import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * Interface for sources of data that can change over time.
 */
interface StateSource<T> {
    fun get(player: Player): T
}

/**
 * A slot that updates itself when its bound state changes.
 */
class ReactiveSlot(
    x: Int,
    y: Int,
    val source: StateSource<ItemStack>,
    allowPickup: Boolean = false,
    commands: List<String> = emptyList(),
    triggers: List<com.typewritermc.core.entries.Ref<com.typewritermc.engine.paper.entry.TriggerableEntry>> = emptyList(),
    modifiers: List<com.typewritermc.engine.paper.entry.Modifier> = emptyList(),
    onClick: ((Player, InteractionType) -> Unit)? = null
) : GuiSlot(x = x, y = y, item = ItemStack(org.bukkit.Material.AIR), allowPickup = allowPickup, commands = commands, triggers = triggers, modifiers = modifiers, onClick = onClick) {

    fun resolveItem(player: Player): GuiSlot {
        return copy(item = source.get(player))
    }
}
