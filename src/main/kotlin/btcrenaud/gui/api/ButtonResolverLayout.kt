package btcrenaud.gui.api

import btcrenaud.gui.services.MenuSessionService
import org.bukkit.entity.Player

/**
 * A generic decorator layout that resolves tagged button placeholders at render time.
 *
 * Scans all slots from the inner layout for tagged placeholders whose tag starts with
 * the configured [prefix] (e.g. `"dungeon_button:"`, `"btcsky_button:"`, `"rpg_button:"`)
 * and replaces them with dynamically resolved slots via the [resolver] function.
 * The resolver receives the original tag type and the slot data so it can preserve
 * the user-configured item appearance while adding dynamic interactions.
 *
 * Usage pattern:
 * ```
 * val layout = GenericButtonResolverLayout(baseLayout, prefix = "btcsky_button") { type, player, slot ->
 *     when (type) {
 *         "ISLAND_INFO" -> slot.copy(interactions = ...)
 *         else -> null
 *     }
 * }
 * ```
 *
 * @param inner        the base layout whose tagged slots will be resolved
 * @param prefix       the tag prefix to match (e.g. `"dungeon_button:"`). Slots whose
 *                     `tag` starts with this prefix are candidates for replacement.
 * @param resolver     function that maps a button type string (the part after the prefix)
 *                     and the original [GuiSlot] to a replacement [GuiSlot]. Return `null`
 *                     to leave the placeholder unchanged (the user-configured item is shown).
 * @param id           optional layout id for viewport/focus tracking
 */
class GenericButtonResolverLayout(
    val inner: MenuLayout,
    val prefix: String = "button:",
    val resolver: (type: String, player: Player, slot: GuiSlot) -> GuiSlot?,
    override val id: String? = null,
) : MenuLayout {
    override val innerLayout: MenuLayout? get() = inner

    override fun getSlots(session: MenuSessionService.ActiveSession, viewport: Viewport): List<GuiSlot> {
        val slots = inner.getSlots(session, viewport)
        return slots.map { slot ->
            val tag = slot.tag
            if (tag != null && tag.startsWith(prefix)) {
                val type = tag.removePrefix(prefix)
                val replacement = resolver(type, session.player, slot)
                replacement ?: slot
            } else slot
        }
    }

    override val virtualWidth: Int get() = inner.virtualWidth
    override val virtualHeight: Int get() = inner.virtualHeight

    companion object {
        /**
         * Tag prefix used by the GUI extension's [GuiSlotBuilder] for items with
         * [btcrenaud.gui.GuiItemData.buttonType].
         */
        const val GUI_TAG_PREFIX = "dungeon_button:"
    }
}

/**
 * Registry of button resolvers shared by ALL menus.
 *
 * An extension that builds its own screen wraps its layout in its own
 * [GenericButtonResolverLayout]: its markers are then only resolved on THAT screen. The problem
 * appears as soon as the same button is wanted in a generic `open_gui` menu — a shared Settings
 * tab exposing another extension's toggle, for example: the resolver isn't installed there, the
 * marker stays a dead item, and the setting has no visual feedback.
 *
 * An extension registers its prefix here once, from its initializer, and its markers become
 * usable from any page.
 *
 * Resolvers that need per-screen state (open profession, open category, current island) stay
 * local — this registry can't supply them that context. It is meant for markers whose data only
 * depends on the player.
 */
object GlobalButtonResolvers {

    private val resolvers = java.util.concurrent.ConcurrentHashMap<String,
        (type: String, player: Player, slot: GuiSlot) -> GuiSlot?>()

    /**
     * Declares [resolver] for every marker whose tag starts with [prefix] (e.g. `"audio_button:"`).
     * Re-registering the same prefix replaces the previous one, which makes the call safe on
     * extension reload.
     */
    fun register(prefix: String, resolver: (type: String, player: Player, slot: GuiSlot) -> GuiSlot?) {
        resolvers[prefix] = resolver
    }

    fun unregister(prefix: String) {
        resolvers.remove(prefix)
    }

    fun prefixes(): Set<String> = resolvers.keys.toSet()

    /**
     * Wraps [layout] with one decorator per registered prefix.
     *
     * No resolver registered ⇒ the layout is returned unchanged, at no extra cost.
     */
    fun decorate(layout: MenuLayout): MenuLayout =
        resolvers.entries.fold(layout) { acc, (prefix, resolver) ->
            GenericButtonResolverLayout(inner = acc, prefix = prefix, resolver = resolver)
        }
}
