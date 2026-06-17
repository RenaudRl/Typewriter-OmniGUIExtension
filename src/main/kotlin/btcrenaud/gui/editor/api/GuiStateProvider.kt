package btcrenaud.gui.editor.api

import btcrenaud.gui.editor.states.MenuStateDefinition
import org.bukkit.entity.Player
import java.util.concurrent.ConcurrentHashMap

/**
 * Public API for extensions to register dynamic menu states.
 *
 * Extensions that use the GUI engine can implement this interface
 * to provide custom states and conditions for specific menus.
 * Discovered automatically via Koin.
 */
interface GuiStateProvider {
    /** Unique identifier for this provider. */
    val providerId: String

    /**
     * Returns the states this provider contributes for the given menu.
     * Called each time a menu is rendered. Can return different states
     * based on current server/game state.
     */
    fun getStates(menuId: String): List<MenuStateDefinition>

    /**
     * Register a dynamic state that persists until removed.
     * Useful for temporary states (e.g. during an event).
     */
    fun registerDynamicState(menuId: String, state: MenuStateDefinition)

    /**
     * Remove a previously registered dynamic state.
     */
    fun removeDynamicState(menuId: String, stateId: String)

    /**
     * Custom condition evaluators beyond the built-in fact/permission checks.
     * Key: condition name, Value: (player, value) -> Boolean
     */
    fun getCustomConditions(): Map<String, (Player, String) -> Boolean>
}

/**
 * Central registry for [GuiStateProvider] instances.
 *
 * Merges states from all registered providers, with static states
 * (from entry data) taking precedence over dynamic ones.
 */
object GuiStateRegistry {
    private val providers = ConcurrentHashMap<String, GuiStateProvider>()

    fun register(provider: GuiStateProvider) {
        providers[provider.providerId] = provider
    }

    fun unregister(providerId: String) {
        providers.remove(providerId)
    }

    fun getProvider(providerId: String): GuiStateProvider? = providers[providerId]

    fun getAllProviders(): Collection<GuiStateProvider> = providers.values.toList()

    /**
     * Merges all states for a menu from all providers.
     * Provider-registered states are overridden by entry-defined states.
     */
    fun getMergedStates(
        menuId: String,
        entryStates: Map<String, MenuStateDefinition>
    ): Map<String, MenuStateDefinition> {
        val merged = mutableMapOf<String, MenuStateDefinition>()

        // First, collect from all providers (lower priority)
        for (provider in providers.values) {
            for (state in provider.getStates(menuId)) {
                merged[state.id] = state
            }
        }

        // Then overlay entry-defined states (higher priority)
        merged.putAll(entryStates)

        return merged
    }

    /**
     * Evaluates all custom conditions from registered providers.
     */
    fun evaluateCustomCondition(
        conditionName: String,
        player: Player,
        value: String
    ): Boolean {
        for (provider in providers.values) {
            val conditions = provider.getCustomConditions()
            conditions[conditionName]?.let { evaluator ->
                return evaluator(player, value)
            }
        }
        return false
    }
}
