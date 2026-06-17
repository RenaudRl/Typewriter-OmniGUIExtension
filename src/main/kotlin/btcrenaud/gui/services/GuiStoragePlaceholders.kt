package btcrenaud.gui.services

import btcrenaud.gui.entries.GuiStorageEntry
import com.typewritermc.core.entries.Query
import com.typewritermc.core.extension.annotations.Singleton
import com.typewritermc.engine.paper.extensions.placeholderapi.PlaceholderHandler
import org.bukkit.entity.Player

/**
 * Registers PAPI placeholders for GUI storage data.
 *
 * ## Placeholders
 * - `%typewriter_gui_storage_amount_<artifactId>_<slotIndex>%` — item amount in slot
 * - `%typewriter_gui_storage_has_<artifactId>_<slotIndex>%` — true/false if slot has items
 */
@Singleton
class GuiStoragePlaceholders : PlaceholderHandler {
    override fun onPlaceholderRequest(player: Player?, params: String): String? {
        val p = player ?: return null
        val key = params.lowercase()
        
        if (!key.startsWith("gui_storage_")) return null
        
        // Parse: gui_storage_amount_<artifactId>_<slotIndex>
        //    or: gui_storage_has_<artifactId>_<slotIndex>
        val parts = key.removePrefix("gui_storage_").split("_")
        if (parts.size < 3) return null
        
        val mode = parts[0] // "amount" or "has"
        // The artifactId is everything between mode and the last part (slotIndex)
        val slotIndexStr = parts.last()
        val slotIndex = slotIndexStr.toIntOrNull() ?: return null
        val artifactId = parts.drop(1).dropLast(1).joinToString("_")
        if (artifactId.isBlank()) return null
        
        val entry = Query.findById<GuiStorageEntry>(artifactId) ?: return null
        val groupKey = p.uniqueId.toString()
        
        return when (mode) {
            "amount" -> {
                val item = GuiStorageService.getItem(entry, groupKey, slotIndex)
                item?.amount?.toString() ?: "0"
            }
            "has" -> {
                val item = GuiStorageService.getItem(entry, groupKey, slotIndex)
                if (item != null && !item.type.isAir) "true" else "false"
            }
            else -> null
        }
    }
}
