package btcrenaud.gui

import btcrenaud.gui.services.*
import btcrenaud.gui.editor.api.GuiStateProvider
import btcrenaud.gui.editor.api.GuiStateRegistry
import com.typewritermc.core.extension.Initializable
import com.typewritermc.core.extension.annotations.Singleton
import com.typewritermc.engine.paper.logger
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import org.koin.java.KoinJavaComponent
import btcrenaud.gui.migration.OpenGuiPageMigrator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
object Initializer : Initializable {
    override suspend fun initialize() {
        val plugin: Plugin = Bukkit.getPluginManager().getPlugin("Typewriter")
            ?: return

        // Typewriter loads its Library before extension initializers, while StagingManager
        // is loaded afterwards. The serializer handles the first in-memory read; this async,
        // atomic migration canonicalizes both published and staging files before editing starts.
        val migratedPages = withContext(Dispatchers.IO) {
            OpenGuiPageMigrator.migrate(plugin.dataFolder, logger)
        }
        if (migratedPages > 0) {
            // Library is loaded before extension initializers in beta-175. Reload once, after
            // command registration has completed, so the already-migrated pages replace the
            // short-lived v1 in-memory objects. The schema marker makes this strictly one-shot.
            plugin.server.globalRegionScheduler.runDelayed(plugin, { _ ->
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "typewriter reload")
            }, 20L)
        }

        // Layout types are auto-discovered by the engine's AlgebraicSerializationFactory
        // No explicit registration needed.

        MenuSessionService.initialize(plugin)
        DragAndDropService.initialize(plugin)

        // Register GUI state providers (discovered via Koin)
        try {
            val stateProviders = KoinJavaComponent.getKoin().getAll<GuiStateProvider>()
            stateProviders.forEach { provider ->
                GuiStateRegistry.register(provider)
                logger.info("[GUI] Registered state provider: ${provider.providerId}")
            }
        } catch (_: Exception) { }

        // Note: WebEditorInitializer is auto-discovered by the engine
        // via @Singleton + Initializable — do NOT call it here.
    }

    override suspend fun shutdown() {
        MenuSessionService.shutdown()
        // Unregister too, or every extension reload leaves a stale listener from the old,
        // closed classloader — which then NoClassDefFoundErrors on every inventory event.
        DragAndDropService.shutdown()
    }
}
