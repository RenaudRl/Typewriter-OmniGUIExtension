package btcrenaud.gui.editor.states

import btcrenaud.gui.editor.api.GuiStateRegistry
import com.typewritermc.engine.paper.logger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Loads menu states from multiple sources:
 * 1. Entry data JSON (`_gui_states` key)
 * 2. External providers registered via [GuiStateRegistry]
 */
object MenuStateLoader {

    private val gson = Gson()

    /**
     * Resolves all states for a menu, merging entry-defined states
     * with provider-defined states. Entry states take precedence.
     */
    fun loadStates(menuId: String, entryStatesJson: String?): Map<String, MenuStateDefinition> {
        val entryStates = parseEntryStates(entryStatesJson)
        return GuiStateRegistry.getMergedStates(menuId, entryStates)
    }

    /**
     * Parses the `_gui_states` JSON from an entry's data map.
     */
    private fun parseEntryStates(json: String?): Map<String, MenuStateDefinition> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, Map<String, Any>>>() {}.type
            val raw: Map<String, Map<String, Any>> = gson.fromJson(json, type)
            raw.mapValues { (_, v) -> parseState(v) }
        } catch (e: Exception) {
            logger.warning("[GUI-Editor] Failed to parse menu states: ${e.message}")
            emptyMap()
        }
    }

    private fun parseState(map: Map<String, Any>): MenuStateDefinition {
        val id = map["id"] as? String ?: ""
        val name = map["name"] as? String ?: id
        val description = map["description"] as? String ?: ""
        val parentId = map["parentId"] as? String
        val priority = (map["priority"] as? Number)?.toInt() ?: 0

        @Suppress("UNCHECKED_CAST")
        val conditions = (map["conditions"] as? List<Map<String, Any>>)
            ?.mapNotNull { parseCondition(it) } ?: emptyList()

        @Suppress("UNCHECKED_CAST")
        val overrides = (map["layerOverrides"] as? Map<String, Map<String, Any>>)
            ?.mapValues { (_, v) -> parseLayerOverride(v) } ?: emptyMap()

        @Suppress("UNCHECKED_CAST")
        val subStates = (map["subStates"] as? List<Map<String, Any>>)
            ?.mapNotNull { parseSubState(it) } ?: emptyList()

        return MenuStateDefinition(
            id = id, name = name, description = description,
            parentId = parentId, priority = priority,
            conditions = conditions, layerOverrides = overrides,
            subStates = subStates
        )
    }

    private fun parseSubState(map: Map<String, Any>): SubStateDefinition {
        val id = map["id"] as? String ?: ""
        val name = map["name"] as? String ?: id

        @Suppress("UNCHECKED_CAST")
        val conditions = (map["conditions"] as? List<Map<String, Any>>)
            ?.mapNotNull { parseCondition(it) } ?: emptyList()

        @Suppress("UNCHECKED_CAST")
        val overrides = (map["layerOverrides"] as? Map<String, Map<String, Any>>)
            ?.mapValues { (_, v) -> parseLayerOverride(v) } ?: emptyMap()

        return SubStateDefinition(id = id, name = name, conditions = conditions, layerOverrides = overrides)
    }

    private fun parseCondition(map: Map<String, Any>): StateCondition? {
        val type = map["type"] as? String ?: return null
        return when (type) {
            "fact" -> {
                val factKey = map["factKey"] as? String ?: return null
                val operator = StateCondition.Operator.fromKey(map["operator"] as? String ?: "EQ")
                val value = (map["value"] as? Number)?.toInt() ?: 0
                StateCondition.FactCondition(factKey, operator, value)
            }
            "and" -> {
                @Suppress("UNCHECKED_CAST")
                val children = (map["conditions"] as? List<Map<String, Any>>)
                    ?.mapNotNull { parseCondition(it) } ?: emptyList()
                StateCondition.AndCondition(children)
            }
            "or" -> {
                @Suppress("UNCHECKED_CAST")
                val children = (map["conditions"] as? List<Map<String, Any>>)
                    ?.mapNotNull { parseCondition(it) } ?: emptyList()
                StateCondition.OrCondition(children)
            }
            "not" -> {
                @Suppress("UNCHECKED_CAST")
                val inner = (map["condition"] as? Map<String, Any>)
                val innerCondition = inner?.let { parseCondition(it) }
                if (innerCondition != null) StateCondition.NotCondition(innerCondition) else null
            }
            "always" -> StateCondition.AlwaysTrue
            else -> null
        }
    }

    private fun parseLayerOverride(map: Map<String, Any>): LayerOverride {
        return LayerOverride(
            visible = map["visible"] as? Boolean,
            src = map["src"] as? String,
            unicode = map["unicode"] as? String,
            text = map["text"] as? String,
            color = map["color"] as? String,
            x = (map["x"] as? Number)?.toDouble(),
            y = (map["y"] as? Number)?.toDouble(),
            width = (map["width"] as? Number)?.toDouble(),
            height = (map["height"] as? Number)?.toDouble(),
            opacity = (map["opacity"] as? Number)?.toDouble()
        )
    }
}
