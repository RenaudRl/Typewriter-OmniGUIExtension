package btcrenaud.gui.editor.states

import com.typewritermc.engine.paper.entry.entries.ReadableFactEntry
import com.typewritermc.engine.paper.entry.entries.GroupId
import com.typewritermc.engine.paper.logger
import com.typewritermc.core.entries.Query
import org.bukkit.entity.Player
import java.util.WeakHashMap

/**
 * Evaluates [StateCondition] trees against a player.
 *
 * Uses Typewriter's [FactDatabase] to resolve fact values
 * and Bukkit's permission system for permission checks.
 */
class StateEvaluator {

    /** Cache player -> groupId mappings. WeakHashMap allows GC when player logs out. */
    private val playerGroupCache = WeakHashMap<Player, GroupId>()

    /**
     * Resolves the active state for a menu.
     *
     * Evaluates all states in priority order (highest first).
     * Returns the first state whose conditions are all satisfied.
     * Falls back to the state with [StateCondition.AlwaysTrue] or the first state.
     */
    fun resolveActiveState(
        states: Map<String, MenuStateDefinition>,
        player: Player,
        entryId: String
    ): MenuStateDefinition? {
        val groupId = resolvePlayerGroupId(player)

        // Sort by priority descending, then evaluate
        val sorted = states.values.sortedByDescending { it.priority }
        for (state in sorted) {
            // Empty conditions are treated as AlwaysTrue (state always matches)
            if (state.conditions.isEmpty()) return state
            if (evaluateAll(state.conditions, player, entryId, groupId)) {
                return state
            }
        }

        // Fallback: find the state with AlwaysTrue, then the default, then the first
        return states.values.find { state ->
            state.conditions.any { it is StateCondition.AlwaysTrue }
        } ?: states["default"] ?: states.values.firstOrNull()
    }

    /**
     * Resolves active sub-states for the given parent state.
     */
    fun resolveActiveSubStates(
        state: MenuStateDefinition,
        player: Player,
        entryId: String
    ): List<SubStateDefinition> {
        val groupId = resolvePlayerGroupId(player)
        return state.subStates.filter { sub ->
            sub.conditions.isNotEmpty() && evaluateAll(sub.conditions, player, entryId, groupId)
        }
    }

    /**
     * Evaluates a list of conditions — all must be true (AND logic).
     */
    fun evaluateAll(
        conditions: List<StateCondition>,
        player: Player,
        entryId: String,
        groupId: GroupId
    ): Boolean {
        return conditions.all { evaluate(it, player, entryId, groupId) }
    }

    /**
     * Evaluates a single condition recursively.
     */
    fun evaluate(
        condition: StateCondition,
        player: Player,
        entryId: String,
        groupId: GroupId
    ): Boolean {
        return when (condition) {
            is StateCondition.FactCondition -> evaluateFact(condition, player, entryId, groupId)
            is StateCondition.AndCondition -> condition.conditions.all { evaluate(it, player, entryId, groupId) }
            is StateCondition.OrCondition -> condition.conditions.any { evaluate(it, player, entryId, groupId) }
            is StateCondition.NotCondition -> !evaluate(condition.condition, player, entryId, groupId)
            is StateCondition.AlwaysTrue -> true
        }
    }

    private fun evaluateFact(
        condition: StateCondition.FactCondition,
        player: Player,
        entryId: String,
        groupId: GroupId
    ): Boolean {
        return runCatching {
            when (condition.operator) {
                StateCondition.Operator.HAS_PERMISSION -> {
                    player.hasPermission(condition.factKey)
                }
                StateCondition.Operator.CONTAINS -> {
                    val stringValue = getStringFact(player, entryId, groupId, condition.factKey)
                    stringValue?.contains(condition.value.toString()) == true
                }
                else -> {
                    val factValue = getFactValue(player, entryId, groupId, condition.factKey)
                    compareValues(factValue, condition.operator, condition.value)
                }
            }
        }.onFailure {
            logger.warning("[GUI-Editor] Failed to evaluate fact '${condition.factKey}': ${it.message}")
        }.getOrDefault(false)
    }

    private fun getFactValue(player: Player, entryId: String, groupId: GroupId, factKey: String): Int {
        val factEntry = Query.findById<ReadableFactEntry>(factKey)
        return factEntry?.readForPlayersGroup(player)?.value ?: 0
    }

    private fun getStringFact(player: Player, entryId: String, groupId: GroupId, factKey: String): String? {
        val factEntry = Query.findById<ReadableFactEntry>(factKey)
        return factEntry?.readForPlayersGroup(player)?.value?.toString()
    }

    private fun compareValues(actual: Int, operator: StateCondition.Operator, expected: Int): Boolean {
        return when (operator) {
            StateCondition.Operator.EQ -> actual == expected
            StateCondition.Operator.NEQ -> actual != expected
            StateCondition.Operator.GT -> actual > expected
            StateCondition.Operator.LT -> actual < expected
            StateCondition.Operator.GTE -> actual >= expected
            StateCondition.Operator.LTE -> actual <= expected
            else -> false
        }
    }

    private fun resolvePlayerGroupId(player: Player): GroupId {
        playerGroupCache[player]?.let { return it }

        return runCatching {
            val sessionClass = Class.forName("com.typewritermc.engine.paper.interaction.PlayerSessionManager")
            val instanceMethod = try {
                sessionClass.getMethod("getInstance")
            } catch (_: NoSuchMethodException) {
                sessionClass.getMethod("get")
            }
            val instance = instanceMethod.invoke(null)
            val getSessionMethod = sessionClass.getMethod("getSession", Player::class.java)
            val session = getSessionMethod.invoke(instance, player)
            val getGroupMethod = session!!.javaClass.getMethod("getGroupId")
            val groupId = getGroupMethod.invoke(session) as? GroupId ?: GroupId("global")
            playerGroupCache[player] = groupId
            groupId
        }.getOrDefault(GroupId("global").also {
            playerGroupCache[player] = it
        })
    }
}
