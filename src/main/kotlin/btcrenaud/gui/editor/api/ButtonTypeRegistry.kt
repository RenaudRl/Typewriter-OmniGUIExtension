package btcrenaud.gui.editor.api

import java.util.concurrent.ConcurrentHashMap

/**
 * Declarative registry of the button types contributed by extensions
 * (QuestCodex, BTCSky, Vehicle, …). The web editor queries it so a slot's
 * `buttonType` dropdown only ever offers types that are valid for the menu
 * being edited, and [btcrenaud.gui.editor.validation.MenuValidationService]
 * uses it to reject unknown button types.
 *
 * Discovery mirrors [GuiStateProvider]/[GuiStateRegistry]: extensions expose a
 * [GuiButtonTypeProvider] as a Koin `@Singleton`, and the GUI extension's
 * initializer registers their contributions here. Extensions may also call
 * [register] directly from their own initializer.
 */
object ButtonTypeRegistry {

    private val contributions = ConcurrentHashMap<String, ButtonTypeContribution>()

    fun register(contribution: ButtonTypeContribution) {
        contributions[contribution.extensionId] = contribution
    }

    fun unregister(extensionId: String) {
        contributions.remove(extensionId)
    }

    fun all(): List<ButtonTypeContribution> = contributions.values.toList()

    /** All contributions whose [ButtonTypeContribution.appliesTo] accepts the menu. */
    fun contributionsFor(context: MenuEditContext): List<ButtonTypeContribution> =
        contributions.values.filter { runCatching { it.appliesTo(context) }.getOrDefault(false) }

    /**
     * Whether [buttonType] is a known type for [context].
     *
     * @param buttonPrefix when non-null, restricts the match to the contribution
     *        with that prefix (the exact extension the slot targets); when null,
     *        any applicable contribution may match.
     * @return true/false when at least one contribution applies to the menu,
     *         null when none applies (the editor cannot judge — treated as
     *         non-blocking by the validator).
     */
    fun isKnownButtonType(buttonType: String, buttonPrefix: String?, context: MenuEditContext): Boolean? {
        val applicable = contributionsFor(context)
        if (applicable.isEmpty()) return null
        // Indexed markers ("QUEST_SLOT#3") and parameterised types ("DIMENSION:skills")
        // match on their base type id.
        val baseType = buttonType.substringBefore('#').substringBefore(':')

        // A type can only be called UNKNOWN when its NAMESPACE is itself known.
        //
        // Declaring button types stays optional: plenty of extensions resolve their markers
        // without ever registering anything here. Judging a type on the mere presence of other
        // contributions condemned everything nobody had bothered to describe — hundreds of
        // perfectly working slots reported on every startup. The answerable question is narrower:
        // "this prefix belongs to an extension that described its types — is this one among them?"
        if (buttonPrefix != null) {
            val owners = applicable.filter { it.prefix == buttonPrefix }
            if (owners.isEmpty()) return null
            // Free-form namespaces (config-driven ids, e.g. btcsky_tab:) accept any id.
            return owners.any { it.freeForm || it.types.any { type -> type.id == baseType } }
        }

        // With no prefix the type cannot be attributed to any extension: a match can be
        // confirmed, an absence can never be concluded.
        val matched = applicable.any { contribution ->
            contribution.freeForm || contribution.types.any { it.id == baseType }
        }
        return if (matched) true else null
    }
}

/**
 * A set of button types contributed by a single extension.
 *
 * @param extensionId stable unique id (e.g. "QuestCodex").
 * @param displayName human-readable name for the editor UI.
 * @param prefix the tag prefix these types are emitted with (e.g. "codex_button:").
 * @param types the button types offered.
 * @param appliesTo predicate deciding whether these types are offerable for a
 *        given menu. Return true to always offer them; gate on the menu context
 *        otherwise.
 * @param freeForm true when this prefix accepts ARBITRARY type ids (config-driven
 *        namespaces like `btcsky_tab:`); [types] then only lists suggestions and
 *        validation never rejects an id under this prefix.
 */
data class ButtonTypeContribution(
    val extensionId: String,
    val displayName: String,
    val prefix: String,
    val types: List<ButtonTypeInfo>,
    val appliesTo: (MenuEditContext) -> Boolean = { true },
    val freeForm: Boolean = false,
)

/**
 * Describes a single button type for the editor.
 *
 * @param id the type id stored in `GuiItemData.buttonType` (e.g. "QUEST_SLOT").
 * @param label short human-readable label.
 * @param description longer explanation shown in the editor.
 * @param icon optional asset/material id previewed in the editor.
 * @param repeatable true when the type may be placed on several slots.
 * @param indexed true when the runtime uses indexed markers ("TYPE#n").
 * @param parameterized true when the type carries an argument after a colon
 *        ("DIMENSION:skills") — the editor offers a parameter field.
 * @param category grouping key for the editor UI.
 */
data class ButtonTypeInfo(
    val id: String,
    val label: String,
    val description: String = "",
    val icon: String? = null,
    val repeatable: Boolean = false,
    val indexed: Boolean = false,
    val parameterized: Boolean = false,
    val category: String = "general",
)

/**
 * Read-only view of the menu entry being edited, passed to
 * [ButtonTypeContribution.appliesTo]. Avoids coupling providers to the raw
 * staging JSON representation.
 */
interface MenuEditContext {
    val entryId: String
    val entryType: String

    /** Reads a top-level string field of the entry, or null if absent/non-string. */
    fun stringField(name: String): String?
}

/**
 * Extensions implement this and expose it as a Koin `@Singleton` to have their
 * button types discovered automatically by the GUI extension initializer.
 */
interface GuiButtonTypeProvider {
    fun contributions(): List<ButtonTypeContribution>
}
