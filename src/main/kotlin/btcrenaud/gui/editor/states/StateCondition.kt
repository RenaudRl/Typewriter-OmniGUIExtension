package btcrenaud.gui.editor.states

/**
 * Conditions that determine which menu state is active.
 *
 * Conditions are evaluated against a player at render time.
 * Complex conditions can be composed using And/Or/Not combinators.
 */
sealed interface StateCondition {

    /** The operator for comparing a fact value against a threshold. */
    enum class Operator(val symbol: String) {
        EQ("="), NEQ("!="), GT(">"), LT("<"),
        GTE(">="), LTE("<="),
        CONTAINS("contains"), HAS_PERMISSION("has_permission");

        companion object {
            fun fromKey(key: String): Operator {
                val found = entries.find { it.name.equals(key, ignoreCase = true) || it.symbol == key }
                if (found == null && key.isNotBlank()) {
                    com.typewritermc.engine.paper.logger.warning(
                        "[GUI-Editor] Unknown operator '$key', defaulting to EQ"
                    )
                }
                return found ?: EQ
            }
        }
    }

    /**
     * Compares a player fact against a value.
     *
     * Fact values are integers in Typewriter's fact system.
     */
    data class FactCondition(
        val factKey: String,       // e.g. "player.level", "global.coins"
        val operator: Operator,
        val value: Int
    ) : StateCondition

    /** All child conditions must be true. */
    data class AndCondition(
        val conditions: List<StateCondition>
    ) : StateCondition

    /** At least one child condition must be true. */
    data class OrCondition(
        val conditions: List<StateCondition>
    ) : StateCondition

    /** Negates the inner condition. */
    data class NotCondition(
        val condition: StateCondition
    ) : StateCondition

    /** Always true — used as a default/fallback state. */
    data object AlwaysTrue : StateCondition
}
