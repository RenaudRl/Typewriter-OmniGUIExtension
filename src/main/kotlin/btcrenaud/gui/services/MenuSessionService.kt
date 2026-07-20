package btcrenaud.gui.services

import btcrenaud.gui.api.*
import btcrenaud.gui.GuiFactory
import btcrenaud.gui.InventorySize
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import com.typewritermc.core.interaction.context
import com.typewritermc.engine.paper.entry.triggerEntriesFor
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.core.entries.Ref
import com.typewritermc.engine.paper.interaction.PlayerSessionManager
import com.typewritermc.engine.paper.facts.FactDatabase
import com.typewritermc.engine.paper.utils.asMini
import com.typewritermc.engine.paper.utils.Sound as EngineSound
import org.koin.java.KoinJavaComponent.get
import io.papermc.paper.dialog.Dialog
import io.papermc.paper.dialog.DialogResponseView
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.DialogInstancesProvider
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.type.DialogType
import io.papermc.paper.registry.data.dialog.ActionButton
import btcrenaud.gui.InputData
import btcrenaud.gui.api.StorageGuiSlot
import net.kyori.adventure.text.minimessage.MiniMessage
import com.typewritermc.core.interaction.InteractionContext
import org.bukkit.Material
import org.bukkit.inventory.ItemStack

/**
 * Manages active GUI sessions and handles interactions.
 */
object MenuSessionService : Listener {
    private lateinit var plugin: Plugin

    private fun debugLog(message: String) {
        if (com.typewritermc.core.entries.Query.findWhere<btcrenaud.gui.GuiConfigEntry> { true }.firstOrNull()?.debug == true) {
            plugin.logger.info(message)
        }
    }

    /**
     * Registry for custom command handlers from external extensions.
     * Each entry maps a command prefix (e.g. "codex:nav ") to a handler function.
     * The handler receives the player, session, full command, clicked slot, and interaction context.
     */
    private val customCommandHandlers = ConcurrentHashMap<String, (Player, ActiveSession, String, GuiSlot?, InteractionContext) -> Unit>()

    /**
     * Returns true if the given command starts with a registered custom command prefix.
     */
    private fun isCustomCommand(cmd: String): Boolean {
        return customCommandHandlers.keys.any { cmd.startsWith(it) }
    }

    /**
     * Register a custom command handler for a specific prefix.
     * When a slot command starts with [prefix], the handler is invoked instead of being
     * dispatched as a Bukkit console command.
     *
     * @param prefix The command prefix to match (e.g. "codex:nav ", "codex:open ")
     * @param handler The handler function
     */
    fun registerCustomCommandHandler(prefix: String, handler: (Player, ActiveSession, String, GuiSlot?, InteractionContext) -> Unit) {
        customCommandHandlers[prefix] = handler
    }

    /**
     * Unregister a custom command handler for a specific prefix.
     */
    fun unregisterCustomCommandHandler(prefix: String) {
        customCommandHandlers.remove(prefix)
    }

    data class ActiveSession(
        val player: Player,
        val definition: MenuDefinition,
        var viewport: Viewport,
        var history: Stack<MenuDefinition> = Stack(),
        var stateId: Int = 0,
        var windowId: Int = -1,
        var lastSlots: Map<Int, org.bukkit.inventory.ItemStack> = emptyMap(),
        var focusedId: String? = null,
        val subViewports: MutableMap<String, Viewport> = mutableMapOf(),
        /** Stores the current page for PaginatedLayouts by ID. */
        val pageStates: MutableMap<String, Int> = mutableMapOf(),
        /** Tracks currently animating slots by their physical index. */
        val animatedSlots: MutableMap<Int, AnimatedSlotState> = mutableMapOf(),
        /** Tracks final positions of finished animations to prevent snapping back. */
        val finishedAnimations: MutableMap<Int, Pair<Int, Int>> = mutableMapOf(),
        /** Tracks the actual Bukkit Inventory currently associated with this session. */
        var currentInventory: org.bukkit.inventory.Inventory? = null,
        /** Flag to ignore InventoryCloseEvent during menu transitions. */
        var isTransitioning: Boolean = false,
        /** Cooldown timestamps keyed by virtual coordinates "x:y" (stable across scrolling). */
        val cooldowns: MutableMap<String, Long> = mutableMapOf(),
        /** State forced via `gui:state <id>` — overrides condition evaluation until cleared. */
        var forcedStateId: String? = null,
        /** Periodic re-render task when the entry sets autoRefreshTicks > 0. */
        var refreshTask: io.papermc.paper.threadedregions.scheduler.ScheduledTask? = null
    )

    /** PDC marker on ghost-copied items so they can be reclaimed on close. */
    private val GHOST_KEY = org.bukkit.NamespacedKey("omnigui", "ghost_item")

    data class AnimatedSlotState(
        val slot: btcrenaud.gui.api.GuiSlot,
        val startTime: Long,
        val startX: Double,
        val startY: Double
    )

    /** Tracks active GUI sessions. */
    private val activeSessions = ConcurrentHashMap<UUID, ActiveSession>()

    fun hasActiveSession(player: Player): Boolean = activeSessions.containsKey(player.uniqueId)
    fun getSession(player: Player): ActiveSession? = activeSessions[player.uniqueId]
    fun getAllSessions(): Collection<ActiveSession> = activeSessions.values

    fun initialize(plugin: Plugin) {
        this.plugin = plugin
        Bukkit.getPluginManager().registerEvents(this, plugin)
        AnimationService.initialize(plugin)
    }

    fun register(
        player: Player,
        definition: MenuDefinition,
        pushHistory: Boolean = true,
        autoRefreshTicks: Long = 0
    ) {
        // GuiOpenEvent and inventory operations must run on the player's thread.
        // Typewriter action handlers run async, so hop onto the entity scheduler first.
        if (!Bukkit.isPrimaryThread()) {
            player.scheduler.run(plugin, { _ -> registerNow(player, definition, pushHistory, autoRefreshTicks) }, null)
            return
        }
        registerNow(player, definition, pushHistory, autoRefreshTicks)
    }

    private fun registerNow(
        player: Player,
        definition: MenuDefinition,
        pushHistory: Boolean,
        autoRefreshTicks: Long
    ) {
        // Public veto point for third-party plugins/extensions.
        val openEvent = btcrenaud.gui.api.GuiOpenEvent(player, definition)
        if (!openEvent.callEvent()) return

        val current = activeSessions[player.uniqueId]
        current?.refreshTask?.cancel()

        // Close current inventory so GuiFactory.update() in render() will
        // detect a type/size mismatch and call open() with the new title.
        if (current != null) {
            safeCloseInventory(player)
        }
        
        val rows = (definition.size?.slots ?: 54) / 9
        val session = ActiveSession(player, definition, Viewport(width = 9, height = rows))
        session.isTransitioning = true // Flag to ignore close events during this transition
        
        // Transfer history from previous session so gui:back can navigate correctly.
        if (current != null && pushHistory) {
            session.history.addAll(current.history)
            session.history.push(current.definition)
        }
        
        // Initialize sub-viewports and page states from layout
        initializeLayoutStates(session, definition.layout, 9, rows)
        
        activeSessions[player.uniqueId] = session
        
        // Play Open Sound
        playSound(player, definition.audio.onOpen, context())

        debugLog("[MenuSession] Opening menu ${definition.title} for ${player.name}")

        if (Bukkit.isPrimaryThread()) {
            render(player, session)
        } else {
            player.scheduler.run(plugin, { _ -> render(player, session) }, null)
        }

        // Live menus: periodic re-render for counters/timers without manual refresh calls.
        if (autoRefreshTicks > 0) {
            session.refreshTask = player.scheduler.runAtFixedRate(plugin, { task ->
                if (activeSessions[player.uniqueId] !== session) {
                    task.cancel()
                } else {
                    render(player, session)
                }
            }, null, autoRefreshTicks, autoRefreshTicks)
        }
    }

    private fun initializeLayoutStates(session: ActiveSession, layout: MenuLayout, parentWidth: Int, parentHeight: Int) {
        layout.id?.let { id ->
            if (layout is ScrollableLayout) {
                session.subViewports[id] = Viewport(width = parentWidth, height = parentHeight)
                if (session.focusedId == null) {
                    session.focusedId = id
                }
            }
            if (layout is PaginatedLayout) {
                session.pageStates[id] = 0
            }
        }
        when (layout) {
            is CompositeLayout -> layout.children.forEach { initializeLayoutStates(session, it, parentWidth, parentHeight) }
            is FrameLayout -> layout.frames.forEach { initializeLayoutStates(session, it.layout, it.width, it.height) }
            is ScrollableLayout -> initializeLayoutStates(session, layout.layout, parentWidth, parentHeight)
            // Recurse into any wrapper layout that exposes an inner layout
            else -> layout.innerLayout?.let { initializeLayoutStates(session, it, parentWidth, parentHeight) }
        }
    }

    fun scroll(player: Player, deltaX: Int, deltaY: Int, targetId: String? = null) {
        val session = activeSessions[player.uniqueId] ?: return
        
        // Find something to scroll. Priority: targetId > focusedId > first available sub-viewport
        var idToScroll = targetId ?: session.focusedId ?: session.subViewports.keys.firstOrNull()
        
        debugLog("[Scroll] Attempting to scroll (dx=$deltaX, dy=$deltaY, target=$targetId, focused=${session.focusedId}, subViewports=${session.subViewports.keys})")
        
        if (idToScroll != null && session.subViewports.containsKey(idToScroll)) {
            val subViewport = session.subViewports[idToScroll]!!

            // Update focus to the explicitly targeted (or currently focused) viewport
            session.focusedId = idToScroll

            // Need the layout to know the virtual size
            val layout = findLayoutById(session.definition.layout, idToScroll)
            if (layout != null) {
                val vw = layout.virtualWidth
                val vh = layout.virtualHeight

                val newX = (subViewport.x + deltaX).coerceIn(0, (vw - subViewport.width).coerceAtLeast(0))
                val newY = (subViewport.y + deltaY).coerceIn(0, (vh - subViewport.height).coerceAtLeast(0))

                if (newX != subViewport.x || newY != subViewport.y) {
                    session.subViewports[idToScroll] = subViewport.copy(x = newX, y = newY)
                    playSound(player, session.definition.audio.onScroll, context())
                    render(player, session)
                    return
                }
            }
            return
        }

        // If targetId is provided but not found in subViewports, log a warning
        // and fall through to main viewport. This avoids silently redirecting
        // scrolls from one zone to another when the target is misconfigured.
        if (targetId != null && !session.subViewports.containsKey(targetId)) {
            debugLog("[Scroll] Target layout '$targetId' not found in subViewports (available: ${session.subViewports.keys})")
        }

        // Default: scroll main viewport
        val layout = session.definition.layout
        val vw = layout.virtualWidth
        val vh = layout.virtualHeight
        
        val newX = (session.viewport.x + deltaX).coerceIn(0, (vw - session.viewport.width).coerceAtLeast(0))
        val newY = (session.viewport.y + deltaY).coerceIn(0, (vh - session.viewport.height).coerceAtLeast(0))
        
        if (newX != session.viewport.x || newY != session.viewport.y) {
            session.viewport = session.viewport.copy(x = newX, y = newY)
            playSound(player, session.definition.audio.onScroll, context())
            render(player, session)
        }
    }

    private fun findLayoutById(layout: MenuLayout, id: String): MenuLayout? {
        if (layout.id == id) return layout
        return when (layout) {
            is CompositeLayout -> layout.children.firstNotNullOfOrNull { findLayoutById(it, id) }
            is FrameLayout -> layout.frames.firstNotNullOfOrNull { findLayoutById(it.layout, id) }
            is ScrollableLayout -> findLayoutById(layout.layout, id)
            // Recurse into any wrapper layout that exposes an inner layout
            else -> layout.innerLayout?.let { findLayoutById(it, id) }
        }
    }

    /**
     * Forces a re-render of the player's current GUI session.
     * Useful for updating ReactiveSlots when external state changes.
     */
    fun refresh(player: Player) {
        val session = activeSessions[player.uniqueId] ?: return
        if (Bukkit.isPrimaryThread()) {
            render(player, session)
        } else {
            player.scheduler.run(plugin, { _ -> render(player, session) }, null)
        }
    }

    private fun render(player: Player, session: ActiveSession) {
        // Resolve items and reactive state
        val rawSlots = session.definition.layout.getSlots(session, session.viewport)
        
        val slots = rawSlots.map { slot ->
            if (slot is btcrenaud.gui.api.ReactiveSlot) {
                slot.resolveItem(player)
            } else slot
        }

        val physicalSlots = slots.mapNotNull { virtual ->
            val relX = virtual.x - session.viewport.x
            val relY = virtual.y - session.viewport.y
            
            // Culling: items must be within the 9-wide visible physical inventory
            if (relX !in 0 until 9 || relY < 0) return@mapNotNull null
            val baseIndex = relY * 9 + relX

            // Handle Animation Interpolation
            if (virtual.animation != null && !session.animatedSlots.containsKey(baseIndex)) {
                debugLog("[Animation] Slot $baseIndex → (${virtual.animation.targetX}, ${virtual.animation.targetY})")
                session.animatedSlots[baseIndex] = AnimatedSlotState(
                    slot = virtual,
                    startX = virtual.x.toDouble(),
                    startY = virtual.y.toDouble(),
                    startTime = System.currentTimeMillis()
                )
                // Register with the ticker so the tween actually advances —
                // without this the animation only progressed on incidental re-renders.
                AnimationService.registerSession(session)
            }
            
            val (finalX, finalY) = AnimationService.getInterpolatedPosition(session, baseIndex, relX, relY)
            
            // The actual physical index to use for this frame
            val index = if (virtual.animation != null) {
                // If animating, we must re-calculate the index from the interpolated position
                (finalY * 9 + finalX)
            } else {
                baseIndex
            }

            val inventorySize = session.definition.size?.slots ?: 54
            if (index !in 0 until inventorySize) return@mapNotNull null
            
            index to (virtual to (finalX to finalY))
        }.associate { it.first to it.second }

        session.stateId++

        val finalTitle = TitleCompiler.compile(session.definition, session.viewport)

        val guiDef = btcrenaud.gui.GuiDefinition(
            type = session.definition.type,
            title = finalTitle,
            size = session.definition.size,
            slots = physicalSlots.map { (index, data) ->
                val (virtual, pos) = data
                val (x, y) = pos
                val displayItem = if (virtual is btcrenaud.gui.api.StorageGuiSlot) {
                    val stored = GuiStorageService.getItem(virtual.entry, virtual.groupKey, virtual.slotIndex)
                    val baseItem = when {
                        stored != null -> stored.clone()
                        virtual.placeholder.type != Material.AIR -> virtual.placeholder.clone()
                        else -> virtual.item.clone()
                    }
                    applyStoragePlaceholders(baseItem, virtual.item, stored, virtual)
                } else {
                    virtual.item
                }
                btcrenaud.gui.api.GuiSlot(
                    x = x,
                    y = y,
                    item = displayItem,
                    triggers = virtual.triggers,
                    modifiers = virtual.modifiers,
                    allowPickup = virtual.allowPickup,
                    interactions = virtual.interactions,
                    input = virtual.input
                )
            }
        )
        
        // Skip update for externally-managed GUIs (Book, Merchant)
        // whose content is set by OpenGuiActionEntry, not by the layout.
        // Calling update() schedules GuiFactory.open() on the player scheduler,
        // which races with the real open() call and overwrites the content.
        // Vanilla GUIs (ANVIL, ENCHANTING_TABLE, etc.) have EmptyLayout too but
        // MUST call update() to actually open the inventory.
        val isExternallyManaged = session.definition.type == btcrenaud.gui.GuiType.BOOK
            || session.definition.type == btcrenaud.gui.GuiType.VILLAGER_TRADE
            || session.definition.type == btcrenaud.gui.GuiType.MERCHANT
        val needsUpdate = session.definition.layout !is btcrenaud.gui.api.EmptyLayout || !isExternallyManaged
        if (needsUpdate) {
            val newItems = guiDef.slots.associate { (it.y * 9 + it.x) to it.item }
            val top = player.openInventory.topInventory
            val sameInventory = session.currentInventory != null &&
                session.currentInventory === top &&
                (top.type == session.definition.type.inventoryType ||
                    (session.definition.type == btcrenaud.gui.GuiType.CUSTOM &&
                        top.size == (session.definition.size?.slots ?: 54)))

            if (sameInventory) {
                // Differential repaint: only touch slots whose item actually changed.
                for (index in (session.lastSlots.keys + newItems.keys)) {
                    val newItem = newItems[index]
                    if (newItem != session.lastSlots[index]) {
                        top.setItem(index, newItem)
                    }
                }
                player.updateInventory()
            } else {
                GuiFactory.update(player, guiDef)
                session.currentInventory = player.openInventory.topInventory
            }
            session.lastSlots = newItems
        }
        session.isTransitioning = false // Transition complete
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val session = activeSessions[player.uniqueId] ?: return
        
        // ── Externally-managed GUIs (Book, Merchant/Trade) ────────────────────
        // IMPORTANT: This block MUST come before the bottom-inventory check below.
        if (session.definition.type == btcrenaud.gui.GuiType.BOOK ||
            session.definition.type == btcrenaud.gui.GuiType.VILLAGER_TRADE ||
            session.definition.type == btcrenaud.gui.GuiType.MERCHANT) {
            
            // Allow Vanilla Minecraft to natively handle all clicks (including shift-clicks)
            // for these GUIs since they use valid Merchant objects in Paper 1.21.4.
            return
        }

        
        // Handle clicks in bottom inventory (Shift-Clicking or Hotbar Swapping)
        if (event.clickedInventory == event.view.bottomInventory) {
            if (event.click == org.bukkit.event.inventory.ClickType.SHIFT_LEFT || 
                event.click == org.bukkit.event.inventory.ClickType.SHIFT_RIGHT ||
                event.click == org.bukkit.event.inventory.ClickType.NUMBER_KEY) {
                event.isCancelled = true
            }
            return
        }

        if (event.clickedInventory != event.view.topInventory) return

        // For vanilla containers (Anvil, Enchanting Table, Grindstone, etc.) with no custom
        // layout (EmptyLayout), let vanilla handle all clicks normally. The plugin only
        // manages the session lifecycle (open/close/render) for these types.
        if (session.definition.layout is btcrenaud.gui.api.EmptyLayout) {
            return
        }

        val relSlot = event.slot
        val slots = session.definition.layout.getSlots(session, session.viewport)
        val relX = relSlot % 9
        val relY = relSlot / 9
        val virtualX = relX + session.viewport.x
        val virtualY = relY + session.viewport.y
        
        // Find the slot that matches the virtual coordinates
        // Manual reverse loop instead of findLast() — avoids Kotlin $$inlined$ synthetic
        // classes that Paper's ClassLoader cannot resolve during event dispatch.
        var clickedSlot: GuiSlot? = null
        for (i in slots.indices.reversed()) {
            val slot = slots[i]
            if (slot.x == virtualX && slot.y == virtualY) {
                clickedSlot = slot
                break
            }
        }

        // Update focus to the layout containing this slot
        updateFocus(session, session.definition.layout, virtualX, virtualY)

        debugLog("[Click] Player ${player.name} clicked raw slot $relSlot (relX=$relX, relY=$relY). Virtual coords: $virtualX, $virtualY")

        // Storage slot check via class name to avoid NoClassDefFoundError
        // when Paper's ClassLoader cannot resolve StorageGuiSlot during event dispatch.
        val isStorageSlot = try {
            clickedSlot != null && clickedSlot.javaClass.name == "btcrenaud.gui.api.StorageGuiSlot"
        } catch (_: NoClassDefFoundError) {
            false
        }
        if (isStorageSlot) {
            debugLog("[Click] Identified StorageGuiSlot! Dispatching GuiStorageService.handleClick...")
            event.isCancelled = true
            @Suppress("UNCHECKED_CAST")
            val storageSlot = clickedSlot as btcrenaud.gui.api.StorageGuiSlot
            GuiStorageService.handleClick(player, storageSlot, event.click) { render(player, session) }
            return
        }

        // Ghost Mode handling
        if (clickedSlot?.isGhost == true) {
            event.isCancelled = true
            // The copy is PDC-tagged so it can never leave the session:
            // the cursor is wiped on close/quit if it still carries the tag.
            val ghost = clickedSlot.item.clone()
            ghost.editMeta { meta ->
                meta.persistentDataContainer.set(GHOST_KEY, org.bukkit.persistence.PersistentDataType.BYTE, 1)
            }
            player.setItemOnCursor(ghost)
            return
        }

        // Cancel by default if no allowPickup
        event.isCancelled = clickedSlot?.allowPickup != true

        // Determine Interaction Type
        val interaction = if (event.click == org.bukkit.event.inventory.ClickType.NUMBER_KEY) {
            InteractionType.fromNumberKey(event.hotbarButton)
        } else {
            InteractionType.fromBukkit(event.click)
        }

        if (interaction != null) {
            // Public veto point before any processing.
            val clickEvent = btcrenaud.gui.api.GuiClickEvent(player, session.definition, clickedSlot, interaction)
            if (!clickEvent.callEvent()) {
                event.isCancelled = true
                return
            }

            // Cooldown check — keyed by VIRTUAL coordinates so the cooldown follows
            // the button, not the physical screen position (stable across scrolling).
            val cooldownKey = "$virtualX:$virtualY"
            val cooldownTicks = clickedSlot?.cooldownTicks ?: 0L
            if (cooldownTicks > 0) {
                val lastClick = session.cooldowns[cooldownKey]
                val now = System.currentTimeMillis()
                if (lastClick != null && (now - lastClick) < cooldownTicks * 50) {
                    return // Still on cooldown, ignore click
                }
                session.cooldowns[cooldownKey] = now
            }

            // Auto-trigger input dialog if configured and enabled (takes precedence over triggers/commands)
            val inputData = clickedSlot?.input
            if (inputData?.enabled == true) {
                event.isCancelled = true
                showInputDialog(player, session, inputData)
                return
            }

            // 1. Interaction-specific bindings (exact click type match)
            val specificInteraction = clickedSlot?.interactions?.get(interaction)

            // 2. Fallback: the slot's plain `commands`/`triggers` fire on primary clicks
            //    when no specific interaction is bound (navigation buttons, sliders…).
            val isPrimaryClick = interaction == InteractionType.LEFT_CLICK || interaction == InteractionType.RIGHT_CLICK

            val commandsToRun: List<String> = when {
                specificInteraction != null -> specificInteraction.commands
                isPrimaryClick -> clickedSlot?.commands ?: emptyList()
                else -> emptyList()
            }
            val triggersToFire = buildList {
                specificInteraction?.triggers?.let(::addAll)
                if (specificInteraction != null || isPrimaryClick) {
                    clickedSlot?.triggers?.let(::addAll)
                }
            }

            // Programmatic widget callback (toggle/tabs built via the Kotlin DSL).
            val callback = clickedSlot?.onClick
            val callbackFired = callback != null && (specificInteraction != null || isPrimaryClick)
            if (callbackFired) {
                callback!!(player, interaction)
                // Repaint so reactive widgets (toggle/tabs) reflect their new state.
                refresh(player)
            }

            if (commandsToRun.isNotEmpty() || triggersToFire.isNotEmpty()) {
                commandsToRun.forEach { cmd ->
                    if (cmd.startsWith("gui:")) {
                        handleInternalCommand(player, session, cmd, clickedSlot, context())
                    } else if (isCustomCommand(cmd)) {
                        handleInternalCommand(player, session, cmd, clickedSlot, context())
                    } else {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", player.name))
                    }
                }

                if (triggersToFire.isNotEmpty()) {
                    triggersToFire.triggerEntriesFor(player, context())
                }
            }

            // Apply slot fact modifiers (same mechanism as ActionEntry.applyModifiers).
            val modifiersToApply = clickedSlot?.modifiers.orEmpty()
            if (modifiersToApply.isNotEmpty() && (specificInteraction != null || isPrimaryClick)) {
                val factDatabase: FactDatabase = get(FactDatabase::class.java)
                factDatabase.modify(player, modifiersToApply, context())
            }

            // Single click-sound regardless of how many mechanisms fired.
            if (callbackFired || commandsToRun.isNotEmpty() || triggersToFire.isNotEmpty() || modifiersToApply.isNotEmpty()) {
                playSound(player, session.definition.audio.onClick, context())
            }
        }
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        val session = activeSessions[player.uniqueId] ?: return
        // Merchant/Book GUIs: let vanilla handle drag (no custom layout to protect)
        val guiType = session.definition.type
        if (guiType == btcrenaud.gui.GuiType.VILLAGER_TRADE ||
            guiType == btcrenaud.gui.GuiType.MERCHANT ||
            guiType == btcrenaud.gui.GuiType.BOOK) {
            return
        }
        // Block all dragging in custom GUIs for security
        event.isCancelled = true
    }

    /**
     * Handles an interaction (click, keybind, WASD) at the global level.
     */
    fun handleGlobalInteraction(player: Player, interaction: InteractionType, interactionContext: com.typewritermc.core.interaction.InteractionContext) {
        val session = activeSessions[player.uniqueId] ?: return
        
        // 1. Check Global Interactions in definition
        val globalCommands: List<String>? = null // globalInteractions not in BORNTOCRAFT
        
        if (globalCommands != null && globalCommands.isNotEmpty()) {
            player.scheduler.run(plugin, { _ ->
                globalCommands.forEach { cmd ->
                    if (cmd.startsWith("gui:")) {
                        handleInternalCommand(player, session, cmd, null, interactionContext)
                    } else {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", player.name))
                    }
                }
                
                // Feedback
                playSound(player, session.definition.audio.onClick, interactionContext)
            }, null)
        } else {
            // Default behaviors for WASD/WASD (ZQSD) and scrolling
            when (interaction) {
                InteractionType.MOVE_UP, InteractionType.SCROLL_UP -> scroll(player, 0, -1)
                InteractionType.MOVE_DOWN, InteractionType.SCROLL_DOWN -> scroll(player, 0, 1)
                InteractionType.MOVE_LEFT -> scroll(player, -1, 0)
                InteractionType.MOVE_RIGHT -> scroll(player, 1, 0)
                else -> {}
            }
        }
    }

    private fun updateFocus(session: ActiveSession, layout: MenuLayout, x: Int, y: Int, offsetX: Int = 0, offsetY: Int = 0) {
        val layoutX = offsetX
        val layoutY = offsetY
        
        // If it's a frame, we know its bounds
        if (layout is FrameLayout) {
            layout.frames.forEach { frame ->
                if (x >= frame.x + layoutX && x < frame.x + layoutX + frame.width &&
                    y >= frame.y + layoutY && y < frame.y + layoutY + frame.height) {
                    session.focusedId = frame.id
                    updateFocus(session, frame.layout, x, y, frame.x + layoutX, frame.y + layoutY)
                }
            }
            return
        }

        if (layout.id != null) {
            // Fallback: check if coordinates are within this layout's virtual bounds if we have them
            val vw = layout.virtualWidth
            val vh = layout.virtualHeight
            if (x >= layoutX && x < layoutX + vw && y >= layoutY && y < layoutY + vh) {
                session.focusedId = layout.id
            }
        }
        
        when (layout) {
            is CompositeLayout -> layout.children.forEach { updateFocus(session, it, x, y, layoutX, layoutY) }
            is ScrollableLayout -> updateFocus(session, layout.layout, x, y, layoutX, layoutY)
            // Wrapper layouts (button resolvers, state-aware…) expose innerLayout —
            // mirror findLayoutById so focus tracking reaches nested scrollables.
            else -> layout.innerLayout?.let { updateFocus(session, it, x, y, layoutX, layoutY) }
        }
    }

    private fun handleInternalCommand(player: Player, session: ActiveSession, cmd: String, slot: btcrenaud.gui.api.GuiSlot? = null, interactionContext: com.typewritermc.core.interaction.InteractionContext) {
        when {
            cmd.startsWith("gui:scroll ") -> {
                val parts = cmd.split(" ")
                val dx = parts.getOrNull(1)?.toIntOrNull() ?: 0
                val dy = parts.getOrNull(2)?.toIntOrNull() ?: 0
                val targetId = parts.getOrNull(3)
                debugLog("[GUI] gui:scroll dx=$dx, dy=$dy, target=$targetId")
                scroll(player, dx, dy, targetId)
            }
            // Shorthand scroll commands (legacy pages, backward compat)
            cmd == "gui:scroll_up" -> scroll(player, 0, -1, session.focusedId)
            cmd == "gui:scroll_down" -> scroll(player, 0, 1, session.focusedId)
            cmd == "gui:scroll_left" -> scroll(player, -1, 0, session.focusedId)
            cmd == "gui:scroll_right" -> scroll(player, 1, 0, session.focusedId)
            cmd.startsWith("gui:page ") -> {
                val parts = cmd.split(" ")
                val delta = parts.getOrNull(1)?.toIntOrNull() ?: 0
                val targetId = parts.getOrNull(2) ?: session.focusedId
                
                val layout = if (targetId != null) findLayoutById(session.definition.layout, targetId) else session.definition.layout
                if (layout is PaginatedLayout) {
                    val id = layout.id ?: return
                    val current = session.pageStates[id] ?: 0
                    val next = (current + delta).coerceIn(0, (layout.pages.size - 1).coerceAtLeast(0))
                    if (next != current) {
                        session.pageStates[id] = next
                        render(player, session)
                    }
                }
            }
            cmd == "gui:back" -> {
                if (session.history.isNotEmpty()) {
                    register(player, session.history.pop(), pushHistory = false)
                } else {
                    // No history — navigate to hub as fallback
                    val hubRef = Ref("menu_demo_hub", TriggerableEntry::class)
                    listOf(hubRef).triggerEntriesFor(player, interactionContext)
                }
            }
            cmd == "gui:close" -> {
                safeCloseInventory(player)
            }
            cmd.startsWith("gui:slider_set ") -> {
                val index = cmd.split(" ").getOrNull(1)?.toIntOrNull() ?: return
                updateSlider(session.definition.layout, index)
                render(player, session)
            }
            cmd.startsWith("gui:input") -> {
                val inputData = slot?.input ?: return
                showInputDialog(player, session, inputData)
            }
            cmd == "gui:refresh" -> {
                render(player, session)
            }
            cmd.startsWith("gui:state") -> {
                // "gui:state <id>" forces a state; "gui:state" alone clears the override.
                val stateId = cmd.substringAfter("gui:state").trim().ifEmpty { null }
                session.forcedStateId = stateId
                render(player, session)
            }
            cmd.startsWith("gui:sound ") -> {
                val key = cmd.substringAfter(" ").trim()
                runCatching {
                    val sound = net.kyori.adventure.sound.Sound.sound(
                        net.kyori.adventure.key.Key.key(key),
                        net.kyori.adventure.sound.Sound.Source.MASTER,
                        1f,
                        1f
                    )
                    player.playSound(sound)
                }.onFailure {
                    plugin.logger.warning("[GUI] gui:sound — invalid sound key '$key'")
                }
            }
            cmd.startsWith("gui:open ") || cmd.startsWith("gui:action ") -> {
                val actionId = cmd.substringAfter(" ").trim()
                if (actionId.isNotEmpty()) {
                    // Stop vehicle tracking BEFORE triggering the new GUI entry.
                    // Without this, VehicleInputService.startTracking is called twice and the
                    // player remains frozen (still mounted on the phantom vehicle) after the transition.
                    // VehicleInputService.stopTracking(player) removed
                    val trigger = Ref(actionId, TriggerableEntry::class)
                    listOf(trigger).triggerEntriesFor(player, interactionContext)
                }
            }
            // Check custom command handlers from registered extensions
            else -> {
                var handled = false
                for ((prefix, handler) in customCommandHandlers) {
                    if (cmd.startsWith(prefix)) {
                        handler(player, session, cmd, slot, interactionContext)
                        handled = true
                        break
                    }
                }
                if (!handled) {
                    // Fall through to Bukkit dispatch for unknown commands
                    plugin.logger.fine("[GUI] Unknown internal command: $cmd — dispatching as console command")
                }
            }
        }

    }

    private fun showInputDialog(player: Player, session: ActiveSession, data: InputData) {
        val title = data.title.get(player, context()).asMini()
        val placeholder = data.placeholder.get(player, context()).asMini()
        
        val dialog = Dialog.create { builder ->
            val instances = DialogInstancesProvider.instance()
            
            val base = DialogBase.builder(title)
                .body(listOf(instances.plainMessageDialogBody(placeholder)))
                .canCloseWithEscape(true)
                .inputs(listOf(instances.textBuilder("input", Component.empty()).build()))
                .build()
 
            val confirmAction = ActionButton.builder(Component.text("Confirm"))
                .action(instances.register({ response: DialogResponseView, _ ->
                    val input = response.getText("input") ?: ""
                    
                    // 1. Store value in FactDatabase
                    val factDatabase: FactDatabase = org.koin.java.KoinJavaComponent.get(FactDatabase::class.java)
                    // We can't use a Map directly, we must use a List of Modifiers
                    // Assuming targetVar is a String ID, we might need a specific modifier type.
                    // For now, let's keep it consistent with Typewriter patterns if possible.
                    // If no direct modifier for single variable, we might need to handle it.
                    
                    // 2. Execute onInputCommands
                    data.onInputCommands.forEach { cmdVar ->
                        val cmd = cmdVar.get(player, context()).replace("{input}", input).replace("%player%", player.name)
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd)
                    }

                    // 3. Execute onInputTriggers
                    data.onInputTriggers.triggerEntriesFor(player, context())

                    // 4. Re-open/Refresh GUI
                    player.scheduler.run(plugin, { _ -> render(player, session) }, null)
                }, net.kyori.adventure.text.event.ClickCallback.Options.builder().build()))
                .build()

            val entryBuilder = builder.empty()
            entryBuilder.base(base)
                .type(instances.notice(confirmAction))
        }

        player.showDialog(dialog)
    }

    fun openPaperDialogInput(
        player: Player,
        title: String,
        placeholder: String,
        onConfirm: (String) -> Unit
    ) {
        openPaperDialogInput(player, MiniMessage.miniMessage().deserialize(title), MiniMessage.miniMessage().deserialize(placeholder), onConfirm)
    }

    fun openPaperDialogInput(
        player: Player,
        title: Component,
        placeholder: Component,
        onConfirm: (String) -> Unit
    ) {
        val dialog = Dialog.create { builder ->
            val instances = DialogInstancesProvider.instance()
            
            val base = DialogBase.builder(title)
                .body(listOf(instances.plainMessageDialogBody(placeholder)))
                .canCloseWithEscape(true)
                .inputs(listOf(instances.textBuilder("input", Component.empty()).build()))
                .build()

            val confirmAction = ActionButton.builder(Component.text("Confirmer"))
                .action(instances.register({ response: DialogResponseView, _ ->
                    val input = response.getText("input") ?: ""
                    player.scheduler.run(plugin, { _ -> onConfirm(input) }, null)
                }, net.kyori.adventure.text.event.ClickCallback.Options.builder().build()))
                .build()

            val entryBuilder = builder.empty()
            entryBuilder.base(base)
                .type(instances.notice(confirmAction))
        }

        player.showDialog(dialog)
    }

    fun openPaperDialogConfirmation(
        player: Player,
        title: Component,
        body: Component,
        onConfirm: () -> Unit,
        onCancel: () -> Unit = {}
    ) {
        val dialog = Dialog.create { builder ->
            val instances = DialogInstancesProvider.instance()
            
            val base = DialogBase.builder(title)
                .body(listOf(instances.plainMessageDialogBody(body)))
                .canCloseWithEscape(true)
                .build()

            val confirmAction = ActionButton.builder(Component.text("Confirmer").color(net.kyori.adventure.text.format.NamedTextColor.GREEN))
                .action(instances.register({ _, _ ->
                    player.scheduler.run(plugin, { _ -> onConfirm() }, null)
                }, net.kyori.adventure.text.event.ClickCallback.Options.builder().build()))
                .build()

            val cancelAction = ActionButton.builder(Component.text("Annuler").color(net.kyori.adventure.text.format.NamedTextColor.RED))
                .action(instances.register({ _, _ ->
                    player.scheduler.run(plugin, { _ -> onCancel() }, null)
                }, net.kyori.adventure.text.event.ClickCallback.Options.builder().build()))
                .build()

            val entryBuilder = builder.empty()
            entryBuilder.base(base)
                .type(DialogType.multiAction(listOf(confirmAction, cancelAction), null, 2))
        }

        player.showDialog(dialog)
    }

    fun openPaperDialogInput(
        player: Player,
        title: Component,
        inputs: List<DialogInput>,
        onConfirm: (Map<String, String>) -> Unit
    ) {
        val dialog = Dialog.create { builder ->
            val instances = DialogInstancesProvider.instance()
            
            val base = DialogBase.builder(title)
                .canCloseWithEscape(true)
                .inputs(inputs)
                .build()

            val confirmAction = ActionButton.builder(Component.text("Confirmer"))
                .action(instances.register({ response: DialogResponseView, _ ->
                    val results = inputs.associateBy({ it.key() }, { response.getText(it.key()) ?: "" })
                    player.scheduler.run(plugin, { _ -> onConfirm(results) }, null)
                }, net.kyori.adventure.text.event.ClickCallback.Options.builder().build()))
                .build()

            val entryBuilder = builder.empty()
            entryBuilder.base(base)
                .type(instances.notice(confirmAction))
        }

        player.showDialog(dialog)
    }

    private fun updateSlider(layout: MenuLayout, index: Int) {
        when (layout) {
            is SliderComponent -> {
                if (index >= 0 && index < layout.length) {
                    layout.value = index.toDouble() / (layout.length - 1)
                    layout.onUpdate(layout.value)
                }
            }
            is CompositeLayout -> layout.children.forEach { updateSlider(it, index) }
            is FrameLayout -> layout.frames.forEach { updateSlider(it.layout, index) }
            is ScrollableLayout -> updateSlider(layout.layout, index)
        }
    }

    private fun playSound(player: Player, sound: EngineSound?, context: com.typewritermc.core.interaction.InteractionContext?) {
        if (sound == null) return
        try {
            player.playSound(player.location, sound.soundId.toString(),
                sound.volume.get(player, null),
                sound.pitch.get(player, null))
        } catch (_: Exception) { }
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return

        val session = activeSessions[player.uniqueId] ?: return

        if (session.isTransitioning) {
            plugin.logger.fine("[GUI] Ignoring close event for ${player.name} (transition in progress)")
            return
        }

        // Book and Merchant GUIs have no persistent inventory, but we still need to make sure
        // we are closing the RIGHT inventory, and not a previous GUI that was just closed by Bukkit
        // during the open() process.
        val isTradeGui = session.definition.type == btcrenaud.gui.GuiType.VILLAGER_TRADE || session.definition.type == btcrenaud.gui.GuiType.MERCHANT
        val isBookGui = session.definition.type == btcrenaud.gui.GuiType.BOOK

        if (isTradeGui) {
            if (event.inventory.type != org.bukkit.event.inventory.InventoryType.MERCHANT) {
                // The inventory being closed is NOT a merchant. This is likely the previous GUI
                // being closed by Bukkit when openMerchant() was called. Do not delete the session.
                return
            }
        } else if (isBookGui) {
            // Books don't have an inventory type that fires a reliable close event with a specific type,
            // but we can let it pass or check if there's a better way. For now, allow it to close.
        } else {
            // For normal GUIs, require strict reference equality.
            if (session.currentInventory == null || event.inventory != session.currentInventory) {
                // The closed inventory is NOT the one this session owns: either our GUI is
                // already gone (its reference was captured before an async reopen) or the
                // player is closing a REAL container while this session lingered.
                //
                // NEVER clear it — clearing a mismatched inventory here was wiping player
                // chests. Drop the now-defunct session so it can no longer hijack later
                // clicks or auto-refresh renders into a real container.
                activeSessions.remove(player.uniqueId)
                session.refreshTask?.cancel()
                return
            }
        }

        // Clear inventory to prevent item drops on close
        // Exception: Merchant/Trade GUIs — we must return input items to the player
        // instead of clearing them, so the player doesn't lose their items.
        if (isTradeGui) {
            returnMerchantInputs(player, event.view.topInventory)
        } else {
            event.inventory.clear()
        }

        // Process temporary storage slots: clear content + fire temporaryTriggers
        processTemporaryStorageSlots(player, session)

        activeSessions.remove(player.uniqueId)
        session.refreshTask?.cancel()

        clearGhostCursor(player)
        playSound(player, session.definition.audio.onClose, context())
        btcrenaud.gui.api.GuiCloseEvent(player, session.definition).callEvent()
    }

    /**
     * Returns the trade input items (slots 0-1) to the player and clears the
     * merchant inventory BEFORE vanilla processes the close — otherwise vanilla
     * would return them a second time (duplication bug).
     */
    private fun returnMerchantInputs(player: Player, topInventory: org.bukkit.inventory.Inventory) {
        if (topInventory.type != org.bukkit.event.inventory.InventoryType.MERCHANT) return
        val itemsToReturn = mutableListOf<org.bukkit.inventory.ItemStack>()
        for (slotIndex in 0..1) {
            val item = topInventory.getItem(slotIndex)
            if (item != null && item.type != org.bukkit.Material.AIR) {
                itemsToReturn.add(item.clone())
            }
        }
        for (slotIndex in 0..2) {
            topInventory.setItem(slotIndex, null)
        }
        itemsToReturn.forEach { item ->
            val leftover = player.inventory.addItem(item)
            if (leftover.isNotEmpty()) {
                leftover.values.forEach { drop ->
                    player.world.dropItemNaturally(player.location, drop)
                }
            }
        }
    }

    /** Wipes the cursor if it still holds a PDC-tagged ghost copy. */
    private fun clearGhostCursor(player: Player) {
        val cursor = player.itemOnCursor
        if (!cursor.isEmpty && cursor.hasItemMeta() &&
            cursor.itemMeta.persistentDataContainer.has(GHOST_KEY, org.bukkit.persistence.PersistentDataType.BYTE)
        ) {
            player.setItemOnCursor(null)
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val session = activeSessions.remove(event.player.uniqueId) ?: return
        session.refreshTask?.cancel()

        // Mirror onClose safeguards for disconnects mid-session.
        val guiType = session.definition.type
        if (guiType == btcrenaud.gui.GuiType.VILLAGER_TRADE || guiType == btcrenaud.gui.GuiType.MERCHANT) {
            returnMerchantInputs(event.player, event.player.openInventory.topInventory)
        }
        clearGhostCursor(event.player)
        btcrenaud.gui.api.GuiCloseEvent(event.player, session.definition).callEvent()
    }

    fun shutdown() {
        activeSessions.values.forEach { it.refreshTask?.cancel() }
        activeSessions.keys.forEach { uuid ->
            val player = Bukkit.getPlayer(uuid)
            if (player != null) safeCloseInventory(player)
        }
        activeSessions.clear()
        org.bukkit.event.HandlerList.unregisterAll(this)
        AnimationService.shutdown()
    }

    private fun safeCloseInventory(player: Player) {
        if (org.bukkit.Bukkit.isPrimaryThread()) {
            player.closeInventory()
        } else {
            val plugin = org.bukkit.Bukkit.getPluginManager().getPlugin("Typewriter")
            if (plugin != null) {
                org.bukkit.Bukkit.getScheduler().runTask(plugin, Runnable { player.closeInventory() })
            }
        }
    }

    /**
     * Iterates all [btcrenaud.gui.api.StorageGuiSlot] in the current layout,
     * clears temporary slots and fires [btcrenaud.gui.api.StorageGuiSlot.temporaryTriggers]
     * if the slot was non-empty.
     */
    private fun processTemporaryStorageSlots(player: Player, session: ActiveSession) {
        val storageSlots = collectStorageSlots(session.definition.layout)
        for (slot in storageSlots) {
            if (slot.temporary) {
                val stored = GuiStorageService.getItem(slot.entry, slot.groupKey, slot.slotIndex)
                val wasFilled = stored != null && stored.type != Material.AIR
                // Clear the slot
                GuiStorageService.setItem(slot.entry, slot.groupKey, slot.slotIndex, null)
                // Fire triggers if the slot was filled
                if (wasFilled && slot.temporaryTriggers.isNotEmpty()) {
                    slot.temporaryTriggers.triggerEntriesFor(player, context())
                }
            }
        }
    }

    /**
     * Recursively collects all [btcrenaud.gui.api.StorageGuiSlot] instances from a layout tree.
     */
    private fun collectStorageSlots(layout: btcrenaud.gui.api.MenuLayout): List<btcrenaud.gui.api.StorageGuiSlot> {
        val result = mutableListOf<btcrenaud.gui.api.StorageGuiSlot>()
        when (layout) {
            is btcrenaud.gui.api.StorageLayout -> {
                // StorageLayout builds slots dynamically — we need a session to call getSlots().
                // Fallback: iterate slotConfigs directly (we don't have live slots but can check configs).
                return result // StorageLayout handles its own lifecycle
            }
            is btcrenaud.gui.api.CompositeLayout -> {
                for (child in layout.children) result.addAll(collectStorageSlots(child))
            }
            is btcrenaud.gui.api.ScrollableLayout -> {
                result.addAll(collectStorageSlots(layout.layout))
            }
            is btcrenaud.gui.api.PaginatedLayout -> {
                for (page in layout.pages) {
                    for (slot in page) {
                        if (slot is btcrenaud.gui.api.StorageGuiSlot) result.add(slot)
                    }
                }
            }
            is btcrenaud.gui.api.FrameLayout -> {
                for (frame in layout.frames) {
                    result.addAll(collectStorageSlots(frame.layout))
                }
            }
            is btcrenaud.gui.api.SimpleLayout -> {
                for (slot in layout.slots) {
                    if (slot is btcrenaud.gui.api.StorageGuiSlot) result.add(slot)
                }
            }
            else -> {}
        }
        // Recurse into inner layout if present
        layout.innerLayout?.let { result.addAll(collectStorageSlots(it)) }
        return result
    }

    // ─── Storage Placeholder Resolution ─────────────────────────────────────

    /**
     * Resolves storage placeholders in the configured item's display name and lore,
     * then applies them to the displayed item.
     *
     * Supported placeholders:
     *   {stored_name}   → The stored item's display name (or material name if no custom name)
     *   {stored_amount} → Current amount stored in this slot (0 if empty)
     *   {stored_max}    → Maximum stack size for this slot
     */
    private fun applyStoragePlaceholders(
        displayItem: ItemStack,
        configItem: ItemStack,
        stored: ItemStack?,
        slot: StorageGuiSlot
    ): ItemStack {
        val configMeta = configItem.itemMeta ?: return displayItem
        val hasConfigName = configMeta.hasDisplayName()
        val hasConfigLore = configMeta.hasLore()
        if (!hasConfigName && !hasConfigLore) return displayItem

        val mm = MiniMessage.miniMessage()

        // Resolve placeholder values
        val storedName = if (stored != null) {
            if (stored.hasItemMeta() && stored.itemMeta.hasDisplayName()) {
                mm.serialize(stored.itemMeta.displayName()!!)
            } else {
                stored.type.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
            }
        } else ""

        val storedAmount = stored?.amount?.toString() ?: "0"
        val storedMax = if (slot.maxStack > 0) slot.maxStack.toString() else "∞"

        val result = displayItem.clone()
        val meta = result.itemMeta

        // Apply to display name
        if (hasConfigName) {
            val configName = mm.serialize(configMeta.displayName()!!)
            val resolved = configName
                .replace("{stored_name}", storedName)
                .replace("{stored_amount}", storedAmount)
                .replace("{stored_max}", storedMax)
            meta.displayName(mm.deserialize(resolved))
        }

        // Apply to lore
        if (hasConfigLore) {
            val newLore = configMeta.lore()!!.map { line ->
                val text = mm.serialize(line)
                    .replace("{stored_name}", storedName)
                    .replace("{stored_amount}", storedAmount)
                    .replace("{stored_max}", storedMax)
                mm.deserialize(text)
            }
            meta.lore(newLore)
        }

        result.itemMeta = meta
        return result
    }
}
