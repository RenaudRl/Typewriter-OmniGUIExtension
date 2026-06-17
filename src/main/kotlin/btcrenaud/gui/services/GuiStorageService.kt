package btcrenaud.gui.services

import btcrenaud.gui.api.StorageGuiSlot
import btcrenaud.gui.entries.GuiSettingEntry
import btcrenaud.gui.entries.GuiStorageEntry
import btcrenaud.gui.entries.StorageInteractionConfig
import btcrenaud.gui.entries.StorageClickType
import btcrenaud.gui.loadGroupData
import btcrenaud.gui.saveGroupData
import com.google.gson.JsonObject
import com.typewritermc.core.entries.Query
import com.typewritermc.core.interaction.context
import com.typewritermc.engine.paper.entry.triggerEntriesFor
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock

object GuiStorageService {

    private const val KEY_ACCUMULATED = "_accumulated"

    // ─── Memory cache layer ───────────────────────────────────────────
    private val dataCache = ConcurrentHashMap<String, JsonObject>()

    private val cacheLock = ReentrantReadWriteLock()
    private val readLock = cacheLock.readLock()
    private val writeLock = cacheLock.writeLock()

    private fun cacheKey(entry: GuiStorageEntry, groupKey: String): String =
        "${entry.id}:$groupKey"

    private fun loadCached(entry: GuiStorageEntry, groupKey: String): JsonObject {
        val key = cacheKey(entry, groupKey)
        readLock.lock()
        try {
            dataCache[key]?.let { return it }
        } finally {
            readLock.unlock()
        }
        writeLock.lock()
        try {
            dataCache[key]?.let { return it }
            val data = entry.loadGroupData(groupKey)
            dataCache[key] = data
            return data
        } finally {
            writeLock.unlock()
        }
    }

    private fun saveCached(entry: GuiStorageEntry, groupKey: String, data: JsonObject) {
        val key = cacheKey(entry, groupKey)
        writeLock.lock()
        try {
            dataCache[key] = data
            entry.saveGroupData(groupKey, data)
        } finally {
            writeLock.unlock()
        }
    }

    // ─── Serialization ─────────────────────────────────────────

    private fun itemToBase64(item: ItemStack): String {
        val bytes = item.serializeAsBytes()
        return Base64.getEncoder().encodeToString(bytes)
    }

    private fun base64ToItem(data: String): ItemStack? = try {
        val bytes = Base64.getDecoder().decode(data)
        ItemStack.deserializeBytes(bytes)
    } catch (_: Throwable) { null }

    // ─── Artifact I/O ─────────────────────────────────────────────────────────

    fun getItem(entry: GuiStorageEntry, groupKey: String, slotIndex: Int): ItemStack? {
        val data = loadCached(entry, groupKey)
        val b64 = data[slotIndex.toString()]?.asString ?: return null
        return base64ToItem(b64)?.takeIf { it.type != Material.AIR }
    }

    fun setItem(entry: GuiStorageEntry, groupKey: String, slotIndex: Int, item: ItemStack?) {
        val data = loadCached(entry, groupKey)
        if (item == null || item.type == Material.AIR) {
            data.remove(slotIndex.toString())
        } else {
            data.addProperty(slotIndex.toString(), itemToBase64(item))
        }
        saveCached(entry, groupKey, data)
    }

    fun clearAll(entry: GuiStorageEntry, groupKey: String) {
        val empty = JsonObject()
        saveCached(entry, groupKey, empty)
    }

    // ─── Accumulation ─────────────────────────────────────────────────────────

    fun getAccumulated(entry: GuiStorageEntry, groupKey: String, slotIndex: Int): Int {
        val data = loadCached(entry, groupKey)
        return data["${slotIndex}_$KEY_ACCUMULATED"]?.asInt ?: 0
    }

    fun setAccumulated(entry: GuiStorageEntry, groupKey: String, slotIndex: Int, amount: Int) {
        val data = loadCached(entry, groupKey)
        if (amount <= 0) {
            data.remove("${slotIndex}_$KEY_ACCUMULATED")
        } else {
            data.addProperty("${slotIndex}_$KEY_ACCUMULATED", amount)
        }
        saveCached(entry, groupKey, data)
    }

    // ─── Config resolution ────────────────────────────────────────────────────

    @Volatile
    private var cachedConfig: StorageInteractionConfig? = null
    @Volatile
    private var configResolved = false

    private fun config(): StorageInteractionConfig? {
        if (configResolved) return cachedConfig
        configResolved = true
        cachedConfig = try {
            Query.find<GuiSettingEntry>().firstOrNull()?.storageInteraction
        } catch (_: Exception) { null }
        return cachedConfig
    }

    fun invalidateConfig() {
        configResolved = false
        cachedConfig = null
    }

    private fun matchesClick(configClick: StorageClickType?, actualClick: ClickType, defaultClick: StorageClickType): Boolean {
        val target = configClick ?: defaultClick
        return actualClick == target.bukkit
    }

    // ─── Click handling ───────────────────────────────────────────────────────

    fun handleClick(player: Player, slot: StorageGuiSlot, clickType: ClickType, onRerender: () -> Unit) {
        if (slot.requiredAmount > 0) {
            handleAccumulationClick(player, slot, clickType, onRerender)
        } else {
            handleStorageClick(player, slot, clickType, onRerender)
        }
    }

    private fun matchesRequiredItem(slot: StorageGuiSlot, cursor: ItemStack): Boolean {
        val required = slot.requiredItem ?: return true
        return cursor.type == required.type
    }

    /**
     * Dispatches storage clicks based on [StorageInteractionConfig].
     *
     * Priority order (first match wins):
     *   1. PLACE_ONE  — cursor has matching item
     *   2. TAKE_ALL   — slot has items, cursor empty or same type
     *   3. TAKE_ONE   — slot has items
     *   4. PLACE_ALL  — cursor has matching item
     *   5. TAKE_STACK — slot has items (or fill from inventory if slot empty + cursor matches)
     *   6. FILL_FROM_INV — fills from player inventory (cursor content is moved to slot too)
     *   7. DROP_ALL   — slot has items
     */
    private fun handleStorageClick(player: Player, slot: StorageGuiSlot, clickType: ClickType, onRerender: () -> Unit) {
        val stored = getItem(slot.entry, slot.groupKey, slot.slotIndex)
        val cursorRaw = player.itemOnCursor
        val cursor = if (cursorRaw.type == Material.AIR) null else cursorRaw
        val cfg = config()

        // 1. PLACE_ONE
        if (matchesClick(cfg?.placeOneClick, clickType, StorageClickType.LEFT)) {
            val c = cursor
            if (c != null && matchesRequiredItem(slot, c)) {
                placeOne(player, slot, stored, c, onRerender)
                return
            }
        }
        // 2. TAKE_ALL
        if (matchesClick(cfg?.takeAllClick, clickType, StorageClickType.LEFT)) {
            if (stored != null) {
                val c = cursor
                if (c == null || c.isSimilar(stored)) {
                    takeAll(player, slot, stored, onRerender)
                    return
                }
            }
        }
        // 3. TAKE_ONE
        if (matchesClick(cfg?.takeOneClick, clickType, StorageClickType.RIGHT)) {
            if (stored != null) {
                takeOne(player, slot, stored, onRerender)
                return
            }
        }
        // 4. PLACE_ALL
        if (matchesClick(cfg?.placeAllClick, clickType, StorageClickType.SHIFT_LEFT)) {
            val c = cursor
            if (c != null && matchesRequiredItem(slot, c)) {
                placeAll(player, slot, stored, c, onRerender)
                return
            }
        }
        // 5. TAKE_STACK / fillFromInventory
        if (matchesClick(cfg?.takeStackClick, clickType, StorageClickType.SHIFT_RIGHT)) {
            if (stored != null) {
                takeStack(player, slot, stored, onRerender)
                return
            } else {
                // Slot empty: fill from inventory (cursor + full inventory scan)
                fillFromInventory(player, slot, stored, cursor, onRerender)
                return
            }
        }
        // 6. FILL_FROM_INV
        if (matchesClick(cfg?.fillFromInvClick, clickType, StorageClickType.SWAP_OFFHAND)) {
            fillFromInventory(player, slot, stored, cursor, onRerender)
            return
        }
        // 7. DROP_ALL
        if (matchesClick(cfg?.dropAllClick, clickType, StorageClickType.DROP)) {
            if (stored != null) {
                dropAll(player, slot, stored, onRerender)
                return
            }
        }
    }

    // ─── Click handlers ────────────────────────────────────────────────────

    /** Place ONE item from cursor into slot. Saves to artifact before clearing cursor. */
    private fun placeOne(player: Player, slot: StorageGuiSlot, stored: ItemStack?, cursor: ItemStack, onRerender: () -> Unit) {
        val current = stored?.amount ?: 0
        if (current >= slot.maxStack && slot.maxStack > 0) return
        val newAmount = if (slot.forceStorage && cursor.maxStackSize <= 1) {
            // Non-stackable item: store as single unit regardless of maxStack
            current + 1
        } else {
            current + 1
        }
        // Step 1: Persist the item BEFORE touching the cursor
        val newStored = (stored ?: cursor).clone().apply { amount = newAmount }
        setItem(slot.entry, slot.groupKey, slot.slotIndex, newStored)
        // Step 2: Update cursor (only after persistence succeeded)
        val leftover = cursor.amount - 1
        player.setItemOnCursor(if (leftover > 0) cursor.clone().apply { amount = leftover } else air())
        // Step 3: Fire fill trigger if slot was empty
        if (current == 0) fireFill(player, slot)
        onRerender()
    }

    /** Place ALL items from cursor into slot (up to maxAmount). */
    private fun placeAll(player: Player, slot: StorageGuiSlot, stored: ItemStack?, cursor: ItemStack, onRerender: () -> Unit) {
        val current = stored?.amount ?: 0
        val max = if (slot.maxStack > 0) slot.maxStack else 64
        if (current >= max) return
        val toPlace = minOf(cursor.amount, max - current)
        if (toPlace <= 0) return
        val newAmount = current + toPlace
        val newStored = (stored ?: cursor).clone().apply { amount = newAmount }
        setItem(slot.entry, slot.groupKey, slot.slotIndex, newStored)
        val leftover = cursor.amount - toPlace
        player.setItemOnCursor(if (leftover > 0) cursor.clone().apply { amount = leftover } else air())
        if (current == 0) fireFill(player, slot)
        onRerender()
    }

    /** Take ONE item from slot to cursor. */
    private fun takeOne(player: Player, slot: StorageGuiSlot, stored: ItemStack, onRerender: () -> Unit) {
        if (stored.amount <= 0) return
        val cursor = player.itemOnCursor
        if (cursor.type != Material.AIR && !cursor.isSimilar(stored)) return
        if (cursor.type != Material.AIR && cursor.amount >= cursor.maxStackSize) return
        
        val newCursorAmount = (if (cursor.type == Material.AIR) 0 else cursor.amount) + 1
        player.setItemOnCursor(stored.clone().apply { amount = newCursorAmount })
        val remaining = stored.amount - 1
        if (remaining <= 0) {
            setItem(slot.entry, slot.groupKey, slot.slotIndex, null)
            fireEmpty(player, slot)
        } else {
            setItem(slot.entry, slot.groupKey, slot.slotIndex, stored.clone().apply { amount = remaining })
        }
        onRerender()
    }

    /** Take ALL items from slot to cursor. */
    private fun takeAll(player: Player, slot: StorageGuiSlot, stored: ItemStack, onRerender: () -> Unit) {
        val cursor = player.itemOnCursor
        if (cursor.type != Material.AIR && !cursor.isSimilar(stored)) {
            // Swap: put cursor into slot, take stored to cursor
            val max = if (slot.maxStack > 0) slot.maxStack else 64
            if (cursor.amount <= max && matchesRequiredItem(slot, cursor)) {
                setItem(slot.entry, slot.groupKey, slot.slotIndex, cursor.clone())
                player.setItemOnCursor(stored.clone())
                fireFill(player, slot)
                onRerender()
            }
            return
        }
        if (cursor.type != Material.AIR && cursor.amount >= cursor.maxStackSize) return
        val total = (if (cursor.type == Material.AIR) 0 else cursor.amount) + stored.amount
        val newCursorAmount = minOf(total, cursor.maxStackSize.coerceAtLeast(64))
        player.setItemOnCursor(stored.clone().apply { amount = newCursorAmount })
        val leftover = total - newCursorAmount
        if (leftover <= 0) {
            setItem(slot.entry, slot.groupKey, slot.slotIndex, null)
            fireEmpty(player, slot)
        } else {
            setItem(slot.entry, slot.groupKey, slot.slotIndex, stored.clone().apply { amount = leftover })
        }
        onRerender()
    }

    /** Take one STACK (64) from slot to player inventory. */
    private fun takeStack(player: Player, slot: StorageGuiSlot, stored: ItemStack, onRerender: () -> Unit) {
        val toTake = minOf(stored.amount, stored.maxStackSize.coerceAtMost(64))
        val toGive = stored.clone().apply { amount = toTake }
        val overflow = player.inventory.addItem(toGive)
        val actuallyTaken = toTake - (overflow.values.firstOrNull()?.amount ?: 0)
        if (actuallyTaken <= 0) return
        val remaining = stored.amount - actuallyTaken
        if (remaining <= 0) {
            setItem(slot.entry, slot.groupKey, slot.slotIndex, null)
            fireEmpty(player, slot)
        } else {
            setItem(slot.entry, slot.groupKey, slot.slotIndex, stored.clone().apply { amount = remaining })
        }
        onRerender()
    }

    /** Drop ALL items from slot, with safety: excess goes to ground not void. */
    private fun dropAll(player: Player, slot: StorageGuiSlot, stored: ItemStack, onRerender: () -> Unit) {
        val overflow = player.inventory.addItem(stored.clone())
        val leftover = overflow.values.firstOrNull()
        if (leftover != null) {
            player.world.dropItemNaturally(player.location, leftover)
        }
        setItem(slot.entry, slot.groupKey, slot.slotIndex, null)
        fireEmpty(player, slot)
        onRerender()
    }

    /**
     * Fill slot from player inventory (up to maxAmount).
     * First moves matching cursor items, then scans the full inventory.
     * If the slot is empty, the first matching item found becomes the reference type.
     */
    private fun fillFromInventory(player: Player, slot: StorageGuiSlot, stored: ItemStack?, cursor: ItemStack?, onRerender: () -> Unit) {
        val current = stored?.amount ?: 0
        val max = if (slot.maxStack > 0) slot.maxStack else 64
        if (current >= max) return

        val space = max - current
        var remaining = space

        // Save a reference clone BEFORE mutating any items
        var referenceItem: ItemStack? = (stored ?: cursor)?.clone()

        // Phase 1: consume cursor item first if it matches
        if (cursor != null && cursor.type != Material.AIR && matchesRequiredItem(slot, cursor)) {
            if (referenceItem == null) referenceItem = cursor.clone()
            val take = minOf(cursor.amount, remaining)
            cursor.amount -= take
            remaining -= take
            player.setItemOnCursor(if (cursor.amount > 0) cursor else air())
        }

        // Phase 2: scan inventory for matching items
        if (referenceItem != null && remaining > 0) {
            for (invSlot in player.inventory.contents) {
                if (invSlot == null || invSlot.type == Material.AIR) continue
                if (!invSlot.isSimilar(referenceItem)) continue
                if (!matchesRequiredItem(slot, invSlot)) continue

                val take = minOf(invSlot.amount, remaining)
                invSlot.amount -= take
                remaining -= take
                if (remaining <= 0) break
            }
        }

        val placed = space - remaining
        if (placed <= 0) return

        val newStored = (referenceItem ?: return).clone().apply { amount = current + placed }
        setItem(slot.entry, slot.groupKey, slot.slotIndex, newStored)
        if (current == 0) fireFill(player, slot)
        onRerender()
    }

    // ─── Accumulation handlers ─────────────────────────────────────────────────

    private fun handleAccumulationClick(player: Player, slot: StorageGuiSlot, clickType: ClickType, onRerender: () -> Unit) {
        val cursor = player.itemOnCursor
        if (cursor.type == Material.AIR) {
            if (clickType == ClickType.SWAP_OFFHAND || clickType == ClickType.RIGHT) {
                handleAccumulationWithdraw(player, slot, onRerender)
            }
            return
        }
        if (clickType == ClickType.LEFT || clickType == ClickType.SHIFT_LEFT || clickType == ClickType.SHIFT_RIGHT || clickType == ClickType.SWAP_OFFHAND) {
            handleAccumulationDeposit(player, slot, cursor, clickType, onRerender)
        } else if (clickType == ClickType.RIGHT) {
            handleAccumulationWithdraw(player, slot, onRerender)
        }
    }

    private fun handleAccumulationDeposit(player: Player, slot: StorageGuiSlot, cursor: ItemStack, clickType: ClickType, onRerender: () -> Unit) {
        val required = slot.requiredItem ?: return
        if (!cursor.isSimilar(required)) return
        val currentAccumulated = getAccumulated(slot.entry, slot.groupKey, slot.slotIndex)
        val remaining = slot.requiredAmount - currentAccumulated
        if (remaining <= 0) return
        val depositAmount = minOf(cursor.amount, remaining)
        if (depositAmount <= 0) return
        val newAccumulated = currentAccumulated + depositAmount
        setAccumulated(slot.entry, slot.groupKey, slot.slotIndex, newAccumulated)
        if (currentAccumulated == 0) {
            val singleItem = cursor.clone().apply { amount = 1 }
            setItem(slot.entry, slot.groupKey, slot.slotIndex, singleItem)
        }
        val leftover = cursor.amount - depositAmount
        player.setItemOnCursor(if (leftover > 0) cursor.clone().apply { amount = leftover } else air())
        if (newAccumulated >= slot.requiredAmount) {
            slot.onReachRequired.triggerEntriesFor(player, context())
            if (slot.consumeItems) {
                setAccumulated(slot.entry, slot.groupKey, slot.slotIndex, 0)
                setItem(slot.entry, slot.groupKey, slot.slotIndex, null)
            } else {
                setAccumulated(slot.entry, slot.groupKey, slot.slotIndex, slot.requiredAmount)
            }
        }
        onRerender()
    }

    private fun handleAccumulationWithdraw(player: Player, slot: StorageGuiSlot, onRerender: () -> Unit) {
        val accumulated = getAccumulated(slot.entry, slot.groupKey, slot.slotIndex)
        if (accumulated <= 0) return
        val stored = getItem(slot.entry, slot.groupKey, slot.slotIndex) ?: return
        val toGive = stored.clone().apply { amount = 1 }
        val overflow = player.inventory.addItem(toGive)
        if (overflow.isNotEmpty()) return
        val newAccumulated = accumulated - 1
        setAccumulated(slot.entry, slot.groupKey, slot.slotIndex, newAccumulated)
        if (newAccumulated <= 0) {
            setItem(slot.entry, slot.groupKey, slot.slotIndex, null)
        }
        onRerender()
    }

    // ─── Utilities ─────────────────────────────────────────────────────────────

    private fun fireFill(player: Player, slot: StorageGuiSlot) {
        if (slot.onFill.isNotEmpty()) slot.onFill.triggerEntriesFor(player, context())
        // Only clear if explicitly temporary and NOT permanent storage
        if (slot.temporary && !slot.forceStorage) {
            setItem(slot.entry, slot.groupKey, slot.slotIndex, null)
        }
    }

    private fun fireEmpty(player: Player, slot: StorageGuiSlot) {
        if (slot.onEmpty.isNotEmpty()) slot.onEmpty.triggerEntriesFor(player, context())
    }

    private fun air() = ItemStack(Material.AIR)
}
