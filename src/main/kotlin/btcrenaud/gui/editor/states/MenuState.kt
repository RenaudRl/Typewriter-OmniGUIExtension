package btcrenaud.gui.editor.states

/**
 * Complete state definition for a menu.
 *
 * A menu has a set of states, each with conditions that determine
 * when it is active. States can inherit from a parent, overriding
 * only the layers that differ. Sub-states refine a parent state
 * without replacing it entirely.
 */
data class MenuStateDefinition(
    val id: String,                              // "default", "hovered", "locked", "premium"
    val name: String,                            // Display name for the editor UI
    val description: String = "",
    val parentId: String? = null,                // Inherit from another state
    val priority: Int = 0,                       // Higher = checked first
    val conditions: List<StateCondition> = emptyList(),
    val layerOverrides: Map<String, LayerOverride> = emptyMap(),
    val subStates: List<SubStateDefinition> = emptyList()
)

/**
 * Sub-state that refines a parent state with additional overrides.
 */
data class SubStateDefinition(
    val id: String,
    val name: String,
    val conditions: List<StateCondition> = emptyList(),
    val layerOverrides: Map<String, LayerOverride> = emptyMap()
)

/**
 * Visual overrides for a single layer within a state.
 *
 * Only the non-null fields are applied — null means "keep the default value."
 */
data class LayerOverride(
    val visible: Boolean? = null,
    val src: String? = null,           // Swap the image/glyph URL
    val unicode: String? = null,       // Swap the unicode character
    val text: String? = null,          // Change text content
    val color: String? = null,         // Change color (hex)
    val x: Double? = null,             // Reposition
    val y: Double? = null,
    val width: Double? = null,         // Resize
    val height: Double? = null,
    val opacity: Double? = null        // 0.0 to 1.0
)

/**
 * Optional layout-level overrides for a state.
 */
data class LayoutOverrides(
    val visibleSlots: List<Int>? = null,   // Only show these slot indices
    val hiddenSlots: List<Int>? = null,    // Hide these slot indices
    val activePage: Int? = null            // Force a specific page
)
