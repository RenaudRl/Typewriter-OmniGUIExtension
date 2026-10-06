package btcrenaud.gui.api

import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

/**
 * The single owner of the rule "who wins when two slots share the same coordinates".
 *
 * The defect fixed here: both the render pass and the click pass kept the LAST slot found at
 * (x,y). A decorative item with no tag and no interaction, declared after a marker (a quest
 * slot, a pagination button...), therefore replaced it both on screen AND on click, silently:
 * the button became invisible and unreachable.
 *
 * Rule: at equal coordinates, a BEARING slot (tag, trigger, command, interaction, input,
 * callback, pickup, ghost, storage) wins over an INERT one, whatever the declaration order.
 * Between two slots of the same nature the last declared one wins — that is the normal
 * "filled background, buttons on top" pattern.
 *
 * Render and click both call [collapse]: they can no longer disagree.
 */
object SlotOverlay {

    /** Tag of the default scroll arrows, the only slots that give way instead of competing. */
    const val DEFAULT_ARROW_TAG = "omnigui:default_arrow"

    /** Overlaps already reported, so a re-render does not flood the console. */
    private val reported: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Memory guard: past this many distinct overlaps, reporting stops. */
    private const val MAX_REPORTED = 2048

    /**
     * A slot is BEARING as soon as it carries something an inert slot could not replace:
     * identity (tag) or behaviour (click, input, pickup, storage).
     */
    fun isBearing(slot: GuiSlot): Boolean =
        slot.tag != null ||
            slot.triggers.isNotEmpty() ||
            slot.commands.isNotEmpty() ||
            slot.interactions.isNotEmpty() ||
            slot.modifiers.isNotEmpty() ||
            slot.input != null ||
            slot.storage != null ||
            slot.onClick != null ||
            slot.allowPickup ||
            slot.isGhost ||
            slot is StorageGuiSlot

    /**
     * Reduces [slots] to a single slot per coordinate, applying the priority rule.
     *
     * @param menuId the menu id, used when logging an overlap
     * @param logger where warnings go; `null` resolves silently
     */
    fun collapse(allSlots: List<GuiSlot>, menuId: String?, logger: Logger? = null): List<GuiSlot> {
        if (allSlots.size < 2) return allSlots
        // A default scroll arrow never covers anything: it only exists on a free cell.
        val slots = dropYielding(
            allSlots,
            xOf = { it.x },
            yOf = { it.y },
            yields = { it.tag == DEFAULT_ARROW_TAG },
            occupies = { it is ReactiveSlot || !it.item.type.isAir },
        )
        if (slots.size < 2) return slots

        val winners = LinkedHashMap<Long, GuiSlot>(slots.size)
        for (slot in slots) {
            val key = coordKey(slot.x, slot.y)
            val current = winners[key]
            if (current == null) {
                winners[key] = slot
                continue
            }

            val incomingBears = isBearing(slot)
            val currentBears = isBearing(current)
            // The bearing slot wins; at equal nature the last declared one wins (historic behaviour).
            val keepIncoming = incomingBears || !currentBears
            if (keepIncoming) winners[key] = slot

            report(menuId, slot.x, slot.y, kept = if (keepIncoming) slot else current, logger)
        }

        return if (winners.size == slots.size) slots else winners.values.toList()
    }

    /**
     * Drops every slot that [yields] when another slot that [occupies] its cell is present.
     * The yielding slot is the one that gives way — never the content or the button it would
     * have covered — and nothing is reported: a default arrow with no free cell is simply absent.
     * Generic so the rule is unit-testable without Bukkit items.
     */
    internal fun <T> dropYielding(
        slots: List<T>,
        xOf: (T) -> Int,
        yOf: (T) -> Int,
        yields: (T) -> Boolean,
        occupies: (T) -> Boolean,
    ): List<T> {
        if (slots.none(yields)) return slots
        val taken = slots.filter { !yields(it) && occupies(it) }.mapTo(HashSet()) { coordKey(xOf(it), yOf(it)) }
        return slots.filter { !yields(it) || coordKey(xOf(it), yOf(it)) !in taken }
    }

    private fun report(menuId: String?, x: Int, y: Int, kept: GuiSlot, logger: Logger?) {
        if (logger == null) return
        val menu = menuId ?: "<unknown>"
        val key = "$menu@$x,$y"
        if (reported.size >= MAX_REPORTED) return
        if (!reported.add(key)) return
        logger.log(
            Level.WARNING,
            "[OmniGUI] Slot overlap in menu '$menu' at (x=$x, y=$y): several slots share these " +
                "coordinates. Kept the ${if (isBearing(kept)) "interactive" else "inert"} one. " +
                "Move one of them, or remove it from the layout. If one of them is repeated, " +
                "check its 'gap': it is a STEP, not a spacing — 1 places adjacent slots, " +
                "2 leaves one empty slot between each."
        )
    }

    /** Collision-free coordinate key, negative coordinates included. */
    private fun coordKey(x: Int, y: Int): Long =
        (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)
}
