package btcrenaud.gui.api

import btcrenaud.gui.entries.GuiStorageEntry
import btcrenaud.gui.services.GuiStorageService
import btcrenaud.gui.services.MenuSessionService
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.engine.paper.entry.TriggerableEntry
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

data class StorageSlotConfig(
    val x: Int,
    val y: Int,
    val slotIndex: Int,
    val maxStack: Int = 64,
    val temporary: Boolean = false,
    val temporaryTriggers: List<Ref<TriggerableEntry>> = emptyList(),
    val onFill: List<Ref<TriggerableEntry>> = emptyList(),
    val onEmpty: List<Ref<TriggerableEntry>> = emptyList(),
    val placeholder: ItemStack = ItemStack(Material.AIR),
    val requiredItem: ItemStack? = null,
    val requiredAmount: Int = 0,
    val onReachRequired: List<Ref<TriggerableEntry>> = emptyList(),
    val consumeItems: Boolean = true,
    @Help("If true, non-stackable items (swords, tools) can be stored. Default: true")
    val forceStorage: Boolean = true
)

/**
 * A GuiSlot backed by a persistent storage artifact.
 * Click interactions are intercepted by MenuSessionService and delegated to GuiStorageService.
 */
class StorageGuiSlot(
    x: Int, y: Int, item: ItemStack,
    val entry: GuiStorageEntry,
    val groupKey: String,
    val slotIndex: Int,
    val maxStack: Int,
    val temporary: Boolean,
    val temporaryTriggers: List<Ref<TriggerableEntry>> = emptyList(),
    val onFill: List<Ref<TriggerableEntry>>,
    val onEmpty: List<Ref<TriggerableEntry>>,
    val placeholder: ItemStack,
    val requiredItem: ItemStack? = null,
    val requiredAmount: Int = 0,
    val onReachRequired: List<Ref<TriggerableEntry>> = emptyList(),
    val consumeItems: Boolean = true,
    val forceStorage: Boolean = true,
    val accumulated: Int = 0
) : GuiSlot(x, y, item, allowPickup = false)

/**
 * A layout that renders a set of storage slots backed by a GuiStorageEntry artifact.
 *
 * @param entry         The artifact that persists the stored items.
 * @param slotConfigs   Slot definitions (position, maxStack, triggers, etc.)
 * @param groupKeyProvider  Maps a player to a storage group key.
 *                          Default: player UUID (individual storage per player).
 *                          Pass `{ "island:${islandId}" }` for shared group storage.
 */
class StorageLayout(
    val entry: GuiStorageEntry,
    val slotConfigs: List<StorageSlotConfig>,
    val groupKeyProvider: (Player) -> String = { it.uniqueId.toString() },
    override val id: String? = null
) : MenuLayout {

    override fun getSlots(session: MenuSessionService.ActiveSession, viewport: Viewport): List<GuiSlot> {
        val groupKey = groupKeyProvider(session.player)
        return slotConfigs.map { config ->
            val stored = GuiStorageService.getItem(entry, groupKey, config.slotIndex)
            val accumulated = if (config.requiredAmount > 0) {
                GuiStorageService.getAccumulated(entry, groupKey, config.slotIndex)
            } else 0

            val displayItem = if (config.requiredAmount > 0 && accumulated > 0) {
                val progressItem = (stored ?: config.placeholder).clone()
                val meta = progressItem.itemMeta
                val progressBar = buildProgressBar(accumulated, config.requiredAmount)
                meta.lore(listOf(
                    net.kyori.adventure.text.Component.text()
                        .append(net.kyori.adventure.text.Component.text("Progress: ", net.kyori.adventure.text.format.NamedTextColor.GRAY))
                        .append(net.kyori.adventure.text.Component.text("$accumulated/${config.requiredAmount} ", net.kyori.adventure.text.format.NamedTextColor.WHITE))
                        .append(progressBar)
                        .build()
                ))
                progressItem.itemMeta = meta
                progressItem
            } else {
                stored ?: config.placeholder
            }

            StorageGuiSlot(
                x = config.x,
                y = config.y,
                item = displayItem,
                entry = entry,
                groupKey = groupKey,
                slotIndex = config.slotIndex,
                maxStack = config.maxStack,
                temporary = config.temporary,
                temporaryTriggers = config.temporaryTriggers,
                onFill = config.onFill,
                onEmpty = config.onEmpty,
                placeholder = config.placeholder,
                requiredItem = config.requiredItem,
                requiredAmount = config.requiredAmount,
                onReachRequired = config.onReachRequired,
                consumeItems = config.consumeItems,
                forceStorage = config.forceStorage,
                accumulated = accumulated
            )
        }
    }

    private fun buildProgressBar(current: Int, max: Int): net.kyori.adventure.text.Component {
        val barLength = 10
        val filled = (current.toDouble() / max * barLength).toInt().coerceIn(0, barLength)
        return net.kyori.adventure.text.Component.text()
            .append(net.kyori.adventure.text.Component.text("■".repeat(filled), net.kyori.adventure.text.format.NamedTextColor.GREEN))
            .append(net.kyori.adventure.text.Component.text("■".repeat(barLength - filled), net.kyori.adventure.text.format.NamedTextColor.GRAY))
            .build()
    }
}
