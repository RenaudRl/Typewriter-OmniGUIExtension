package btcrenaud.gui

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.inventory.BeaconInventory
import org.bukkit.inventory.CartographyInventory
import org.bukkit.inventory.EnchantingInventory
import org.bukkit.inventory.GrindstoneInventory
import org.bukkit.inventory.AnvilInventory
import org.bukkit.inventory.SmithingInventory
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.Merchant
import org.bukkit.inventory.MenuType
import org.bukkit.inventory.MerchantRecipe
import org.bukkit.inventory.meta.BookMeta
import org.bukkit.plugin.Plugin
import org.bukkit.Material
import org.bukkit.event.inventory.InventoryType

/**
 * Central entry point used to open GUIs. Callers provide a [GuiDefinition] containing the type and any
 * additional data. Internally the implementation dispatches to the correct inventory creation logic.
 */
object GuiFactory {

    fun open(player: Player, definition: GuiDefinition) {
        val plugin: Plugin = Bukkit.getPluginManager().getPlugin("Typewriter") ?: return
        val action = Runnable {
            when (definition.type) {
                GuiType.BOOK -> openBook(player, definition)
                GuiType.VILLAGER_TRADE, GuiType.MERCHANT -> openVillager(player, definition)
                GuiType.CUSTOM -> openSized(player, definition)
                else -> openTyped(player, definition)
            }
        }
        player.scheduler.run(plugin, { _ -> action.run() }, null)
    }

    private fun openBook(player: Player, definition: GuiDefinition) {
        val book = ItemStack(Material.WRITTEN_BOOK)
        val meta = book.itemMeta as? BookMeta
        if (meta != null) {
            val pages = if (definition.bookPages.isNotEmpty()) {
                definition.bookPages
            } else {
                listOf(net.kyori.adventure.text.Component.text(" "))
            }
            meta.pages(pages)
            book.itemMeta = meta
        }
        player.openBook(book)
    }

    private fun openVillager(player: Player, definition: GuiDefinition) {
        val merchant = player.server.createMerchant()
        if (definition.villagerTrades.isNotEmpty()) {
            merchant.recipes = definition.villagerTrades.map { MerchantRecipe(it) }
        }
        player.openMerchantView(merchant, definition.title)
    }

    fun update(player: Player, definition: GuiDefinition) {
        val top = player.openInventory.topInventory
        if (top.type == definition.type.inventoryType || (definition.type == GuiType.CUSTOM && top.size == definition.size?.slots)) {
            applySlotsDiff(top, definition)
            applyTitle(player, definition)
            player.updateInventory()
        } else {
            open(player, definition)
        }
    }

    /**
     * Live title update on an already-open view. Reactive/scrolled menus recompile
     * their title (viewport shift tags, placeholders) on every render; without this
     * the client keeps the stale title until the inventory is reopened.
     */
    private fun applyTitle(player: Player, definition: GuiDefinition) {
        val title = definition.title ?: return
        runCatching {
            val view = player.openInventory
            val serialized = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(title)
            if (view.title != serialized) {
                view.setTitle(serialized)
            }
        }
        // Views without a mutable title (e.g. player inventory) throw — ignore.
    }

    /**
     * Applies only the slots whose content actually changed. A full clear() + rewrite
     * forces the client to redraw every stack and produces a visible flicker on
     * fast-refreshing reactive menus. Non-grid containers keep the legacy full apply.
     */
    private fun applySlotsDiff(inventory: Inventory, definition: GuiDefinition) {
        val isSimpleGrid = when (inventory.type) {
            InventoryType.CHEST, InventoryType.BARREL, InventoryType.SHULKER_BOX,
            InventoryType.HOPPER, InventoryType.DISPENSER, InventoryType.DROPPER -> true
            else -> definition.type == GuiType.CUSTOM
        }
        if (!isSimpleGrid) {
            applySlots(inventory, definition)
            return
        }

        val target = arrayOfNulls<ItemStack>(inventory.size)
        definition.slots.forEach { slot ->
            val index = slot.y * 9 + slot.x
            if (index in 0 until inventory.size) target[index] = slot.item
        }

        for (index in 0 until inventory.size) {
            val current = inventory.getItem(index)
            val wanted = target[index]
            val unchanged = when {
                current == null && wanted == null -> true
                current == null || wanted == null -> false
                else -> current == wanted
            }
            if (!unchanged) {
                inventory.setItem(index, wanted)
            }
        }
    }

    private fun openTyped(player: Player, definition: GuiDefinition) {
        val type = requireNotNull(definition.type.inventoryType) { "No inventory type for ${definition.type}" }
        val title = definition.title ?: ComponentHolder.none()

        val view = when (definition.type) {
            GuiType.GRINDSTONE -> player.openGrindstoneCompat(null)
            GuiType.CARTOGRAPHY_TABLE -> player.openCartographyTableCompat(null)
            GuiType.ENCHANTING_TABLE -> player.openEnchantingCompat(null)
            GuiType.ANVIL -> player.openAnvilCompat(null)
            GuiType.LOOM -> player.openLoomCompat(null)
            GuiType.SMITHING_TABLE -> player.openSmithingCompat(null)
            GuiType.STONECUTTER -> player.openStonecutterCompat(null)
            else -> null
        }

        val inventory: Inventory = view?.topInventory
            ?: Bukkit.createInventory(null, type, title).also { player.openInventory(it) }

        applySlots(inventory, definition)
        player.updateInventory()
    }

    private fun openSized(player: Player, definition: GuiDefinition) {
        val size = definition.size?.slots ?: error("${definition.type} GUI requires a size")
        val inventory = Bukkit.createInventory(null, size, definition.title ?: ComponentHolder.none())
        player.openInventory(inventory)
        applySlots(inventory, definition)
        player.updateInventory()
    }

    private fun applySlots(inventory: Inventory, definition: GuiDefinition) {
        // Clear inventory before applying new slots to avoid ghost items
        when (inventory.type) {
            InventoryType.CHEST, InventoryType.PLAYER, InventoryType.ENDER_CHEST, InventoryType.BARREL, InventoryType.SHULKER_BOX, InventoryType.HOPPER, InventoryType.DISPENSER, InventoryType.DROPPER -> {
                inventory.clear()
            }
            InventoryType.GRINDSTONE, InventoryType.CARTOGRAPHY, InventoryType.ENCHANTING, InventoryType.LOOM, InventoryType.FURNACE, InventoryType.BLAST_FURNACE, InventoryType.SMOKER -> {
                for (i in 0 until inventory.size) inventory.setItem(i, null)
            }
            InventoryType.BEACON -> inventory.setItem(0, null)
            InventoryType.LECTERN -> inventory.setItem(0, null)
            else -> {
                // For CUSTOM or others, clearing everything is usually safest if we manage the whole size
                if (definition.type == GuiType.CUSTOM) {
                    inventory.clear()
                }
            }
        }

        definition.slots.forEach { slot ->
            val index = slot.y * 9 + slot.x
            when (inventory) {
                is org.bukkit.inventory.GrindstoneInventory -> {
                    if (index in 0..2) {
                        inventory.setItem(index, slot.item)
                        when (index) {
                            0 -> inventory.upperItem = slot.item
                            1 -> inventory.lowerItem = slot.item
                            2 -> inventory.result = slot.item
                        }
                    }
                }
                is org.bukkit.inventory.CartographyInventory -> {
                    if (index in 0..2) {
                        if (index == 2) {
                            inventory.result = slot.item
                        }
                        inventory.setItem(index, slot.item)
                    }
                }
                is org.bukkit.inventory.EnchantingInventory -> {
                    if (index in 0..1) {
                        inventory.setItem(index, slot.item)
                        when (index) {
                            0 -> inventory.item = slot.item
                            1 -> inventory.secondary = slot.item
                        }
                    }
                }
                is org.bukkit.inventory.AnvilInventory -> {
                    if (index in 0..2) {
                        inventory.setItem(index, slot.item)
                        when (index) {
                            0 -> inventory.firstItem = slot.item
                            1 -> inventory.secondItem = slot.item
                            2 -> inventory.result = slot.item
                        }
                    }
                }
                is org.bukkit.inventory.SmithingInventory -> {
                    if (index in 0..3) {
                        inventory.setItem(index, slot.item)
                    }
                }
                is org.bukkit.inventory.BeaconInventory -> {
                    if (index == 0) {
                        inventory.item = slot.item
                        inventory.setItem(0, slot.item)
                    }
                }
                is org.bukkit.inventory.LoomInventory -> {
                    if (index in 0..3) {
                        inventory.setItem(index, slot.item)
                    }
                }
                is org.bukkit.inventory.LecternInventory -> {
                    if (index == 0) {
                        inventory.book = slot.item
                        inventory.setItem(0, slot.item)
                    }
                }
                is org.bukkit.inventory.FurnaceInventory -> {
                    if (index in 0..2) {
                        inventory.setItem(index, slot.item)
                    }
                }
                else -> if (index in 0 until inventory.size) {
                    inventory.setItem(index, slot.item)
                }
            }
        }
    }
}

private object ComponentHolder {
    @JvmStatic
    fun none(): net.kyori.adventure.text.Component = net.kyori.adventure.text.Component.empty()
}

private object InventoryOpenBridge {
    private val reasonClass: Class<*>? = runCatching {
        Class.forName("org.bukkit.event.inventory.InventoryOpenEvent\$Reason")
    }.getOrNull()

    private val pluginReason: Any? = reasonClass?.let { clazz ->
        @Suppress("UNCHECKED_CAST")
        (clazz as? Class<out Enum<*>>)?.let { enumClass -> java.lang.Enum.valueOf(enumClass, "PLUGIN") }
    }

    private val merchantMethod = reasonClass?.let { clazz ->
        runCatching { Player::class.java.getMethod("openMerchant", Merchant::class.java, clazz) }.getOrNull()
    }

    private val grindstoneMethod = reasonClass?.let { clazz ->
        runCatching { Player::class.java.getMethod("openGrindstone", Location::class.java, clazz) }.getOrNull()
    }

    private val cartographyMethod = reasonClass?.let { clazz ->
        runCatching { Player::class.java.getMethod("openCartographyTable", Location::class.java, clazz) }.getOrNull()
    }

    private val enchantingMethod = reasonClass?.let { clazz ->
        runCatching { Player::class.java.getMethod("openEnchanting", Location::class.java, clazz) }.getOrNull()
    }

    private val anvilMethod = reasonClass?.let { clazz ->
        runCatching { Player::class.java.getMethod("openAnvil", Location::class.java, clazz) }.getOrNull()
    }

    private val loomMethod = reasonClass?.let { clazz ->
        runCatching { Player::class.java.getMethod("openLoom", Location::class.java, clazz) }.getOrNull()
    }

    private val smithingMethod = reasonClass?.let { clazz ->
        runCatching { Player::class.java.getMethod("openSmithingTable", Location::class.java, clazz) }.getOrNull()
    }

    private val stonecutterMethod = reasonClass?.let { clazz ->
        runCatching { Player::class.java.getMethod("openStonecutter", Location::class.java, clazz) }.getOrNull()
    }

    fun Player.openMerchantCompat(merchant: Merchant): InventoryView? {
        return invokeWithReason(merchantMethod, merchant) {
            @Suppress("DEPRECATION")
            openMerchant(merchant, true)
        }
    }

    fun Player.openGrindstoneCompat(location: Location?): InventoryView? {
        return invokeWithReason(grindstoneMethod, location) {
            @Suppress("DEPRECATION")
            openGrindstone(location, true)
        }
    }

    fun Player.openCartographyTableCompat(location: Location?): InventoryView? {
        return invokeWithReason(cartographyMethod, location) {
            @Suppress("DEPRECATION")
            openCartographyTable(location, true)
        }
    }

    fun Player.openEnchantingCompat(location: Location?): InventoryView? {
        return invokeWithReason(enchantingMethod, location) {
            @Suppress("DEPRECATION")
            openEnchanting(location, true)
        }
    }

    fun Player.openAnvilCompat(location: Location?): InventoryView? {
        return invokeWithReason(anvilMethod, location) {
            @Suppress("DEPRECATION")
            openAnvil(location, true)
        }
    }

    fun Player.openLoomCompat(location: Location?): InventoryView? {
        return invokeWithReason(loomMethod, location) {
            @Suppress("DEPRECATION")
            openLoom(location, true)
        }
    }

    fun Player.openSmithingCompat(location: Location?): InventoryView? {
        return invokeWithReason(smithingMethod, location) {
            @Suppress("DEPRECATION")
            openSmithingTable(location, true)
        }
    }

    fun Player.openStonecutterCompat(location: Location?): InventoryView? {
        return invokeWithReason(stonecutterMethod, location) {
            @Suppress("DEPRECATION")
            openStonecutter(location, true)
        }
    }

    private fun Player.invokeWithReason(method: java.lang.reflect.Method?, arg: Any?, fallback: () -> InventoryView?): InventoryView? {
        val reason = pluginReason ?: return fallback()
        val targetMethod = method ?: return fallback()
        return runCatching { targetMethod.invoke(this, arg, reason) as? InventoryView }
            .getOrElse { fallback() }
    }
}

private fun Player.openMerchantCompat(merchant: Merchant): InventoryView? = InventoryOpenBridge.run {
    openMerchantCompat(merchant)
}

private fun Player.openGrindstoneCompat(location: Location?): InventoryView? = InventoryOpenBridge.run {
    openGrindstoneCompat(location)
}

private fun Player.openCartographyTableCompat(location: Location?): InventoryView? = InventoryOpenBridge.run {
    openCartographyTableCompat(location)
}

private fun Player.openEnchantingCompat(location: Location?): InventoryView? = InventoryOpenBridge.run {
    openEnchantingCompat(location)
}

private fun Player.openAnvilCompat(location: Location?): InventoryView? = InventoryOpenBridge.run {
    openAnvilCompat(location)
}

private fun Player.openLoomCompat(location: Location?): InventoryView? = InventoryOpenBridge.run {
    openLoomCompat(location)
}

private fun Player.openSmithingCompat(location: Location?): InventoryView? = InventoryOpenBridge.run {
    openSmithingCompat(location)
}

private fun Player.openStonecutterCompat(location: Location?): InventoryView? = InventoryOpenBridge.run {
    openStonecutterCompat(location)
}

private fun Player.openMerchantView(merchant: Merchant, title: net.kyori.adventure.text.Component?) {
    val builder = MenuType.MERCHANT.builder()
        .merchant(merchant)
        .checkReachable(false)
    if (title != null) {
        builder.title(title)
    }
    val view = builder.build(this)
    openInventory(view)
}

