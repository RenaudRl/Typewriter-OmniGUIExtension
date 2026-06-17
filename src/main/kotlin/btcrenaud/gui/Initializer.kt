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

@Singleton
object Initializer : Initializable {
    override suspend fun initialize() {
        val plugin: Plugin = Bukkit.getPluginManager().getPlugin("Typewriter")
            ?: return

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
    }
}
