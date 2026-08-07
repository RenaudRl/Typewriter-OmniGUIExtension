package btcrenaud.gui

import btcrenaud.gui.services.*
import btcrenaud.gui.editor.api.ButtonTypeRegistry
import btcrenaud.gui.editor.api.GuiButtonTypeProvider
import btcrenaud.gui.editor.api.GuiStateProvider
import btcrenaud.gui.editor.api.GuiStateRegistry
import btcrenaud.gui.editor.validation.StartupMenuValidation
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

        MenuSessionService.initialize(plugin)
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
        try {
            KoinJavaComponent.getKoin().getAll<GuiButtonTypeProvider>().forEach { provider ->
                provider.contributions().forEach { ButtonTypeRegistry.register(it) }
            }
        } catch (_: Exception) { }

        withContext(Dispatchers.IO) {
            StartupMenuValidation.run(plugin.dataFolder, logger)
        }
    }

    override suspend fun shutdown() {
        MenuSessionService.shutdown()
        // Unregister too, or every extension reload leaves a stale listener from the old,
        // closed classloader — which then NoClassDefFoundErrors on every inventory event.
        DragAndDropService.shutdown()
        ScrollInputService.shutdown()
    }
}
