package btcrenaud.gui

import btcrenaud.gui.services.*
import btcrenaud.gui.editor.api.ButtonTypeRegistry
import btcrenaud.gui.editor.api.GuiButtonTypeProvider
import btcrenaud.gui.editor.api.GuiStateProvider
import btcrenaud.gui.editor.api.GuiStateRegistry
import btcrenaud.gui.editor.validation.StartupMenuValidation
import btcrenaud.gui.inventory.ExtendedInventoryPacketService
import com.typewritermc.core.extension.Initializable
import com.typewritermc.core.extension.annotations.Singleton
import com.typewritermc.engine.paper.logger
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import org.koin.java.KoinJavaComponent
import btcrenaud.gui.migration.OpenGuiPageMigrator
import btcrenaud.gui.entries.GuiLeaderboardEntry
import btcrenaud.gui.entries.GuiLeaderboardPopulationEntry
import com.typewritermc.core.entries.Query
import com.typewritermc.engine.paper.entry.AssetManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Singleton
object Initializer : Initializable {
    override suspend fun initialize() {
        val plugin: Plugin = Bukkit.getPluginManager().getPlugin("Typewriter")
            ?: return

        // One-shot conversion of pages authored before schema v2. Installations that have already
        // been converted carry a marker, so this costs a single file check and nothing else.
        val migratedPages = withContext(Dispatchers.IO) {
            OpenGuiPageMigrator.migrateOnce(plugin.dataFolder, logger)
        }
        if (migratedPages > 0) {
            // Typewriter loads its Library before extension initializers, so the objects held in
            // memory right now were built from the pre-migration files. Asking for the reload is
            // the operator's call: dispatching it here reloaded every other extension as a side
            // effect of ours, on a path that only ever runs once.
            logger.warning(
                "[OmniGUI] Converted $migratedPages page file(s) to schema v2 after Typewriter had " +
                    "already read them. Run `/typewriter reload` once so the converted menus replace " +
                    "the versions currently held in memory.",
            )
        }

        // Layout types are auto-discovered by the engine's AlgebraicSerializationFactory
        // No explicit registration needed.

        LeaderboardPopulationStore.initialize(
            plugin = plugin,
            assetManager = KoinJavaComponent.get(AssetManager::class.java),
            entries = Query.find<GuiLeaderboardEntry>().toList(),
            artifacts = Query.find<GuiLeaderboardPopulationEntry>().toList(),
        )

        MenuSessionService.initialize(plugin)
        ExtendedInventoryPacketService.initialize()
        DragAndDropService.initialize(plugin)
        ScrollInputService.initialize(plugin)

        // Register GUI state providers (discovered via Koin)
        try {
            val stateProviders = KoinJavaComponent.getKoin().getAll<GuiStateProvider>()
            stateProviders.forEach { provider ->
                GuiStateRegistry.register(provider)
                logger.info("[GUI] Registered state provider: ${provider.providerId}")
            }
        } catch (_: Exception) { }

        // Button types are declared by the extensions that handle them (Koin singletons), so the
        // validation pass below can tell an unknown type from one it simply cannot judge.
        collectButtonTypeProviders()

        // Deferred by one tick, and the button-type sweep is REDONE right before. Other extensions
        // contribute theirs from THEIR own initializer, in an order that is not ours to assume: the
        // sweep above only sees those already initialized. Deferring the validation alone changed
        // nothing — the registry stayed frozen on that partial snapshot, and the pass cried
        // "unknown type" over hundreds of perfectly valid slots.
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, { _ ->
            collectButtonTypeProviders()
            CoroutineScope(Dispatchers.IO).launch {
                StartupMenuValidation.run(plugin.dataFolder, logger)
            }
        }, 1L)
    }

    /**
     * Sweeps the button-type contributions present in Koin AT THIS INSTANT.
     *
     * Safe to call repeatedly: registering the same contribution twice is a no-op, which is what
     * lets a later call pick up the extensions initialized after this one.
     */
    private fun collectButtonTypeProviders() {
        try {
            KoinJavaComponent.getKoin().getAll<GuiButtonTypeProvider>().forEach { provider ->
                provider.contributions().forEach { ButtonTypeRegistry.register(it) }
            }
        } catch (_: Exception) { }
    }

    override suspend fun shutdown() {
        LeaderboardPopulationStore.shutdown()
        MenuSessionService.shutdown()
        ExtendedInventoryPacketService.shutdown()
        // Unregister too, or every extension reload leaves a stale listener from the old,
        // closed classloader — which then NoClassDefFoundErrors on every inventory event.
        DragAndDropService.shutdown()
        ScrollInputService.shutdown()
    }
}
