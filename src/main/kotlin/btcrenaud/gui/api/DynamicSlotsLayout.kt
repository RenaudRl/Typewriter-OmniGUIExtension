package btcrenaud.gui.api

import btcrenaud.gui.services.MenuSessionService

/**
 * Superimposes slots produced at **render time** on top of [inner].
 *
 * This is the layout the `decorate` hook of [MenuViewSupport.resolve] is meant to return, and the
 * point is the laziness. With the view system, the active view is only known once
 * [MenuViewSupport.resolve] returns — but the content resolvers are handed to it *during* the
 * resolution, through that hook. Building the dynamic slots eagerly there would capture a view id
 * that is still null.
 *
 * [slots] is therefore invoked on every render, after the active view has been recorded, so a list
 * of members, friends or profiles reflects the screen actually being shown — and refreshes on its
 * own when the underlying data changes.
 */
class DynamicSlotsLayout(
    private val inner: MenuLayout,
    private val slots: () -> List<GuiSlot>,
    override val id: String? = null,
) : MenuLayout {

    override val innerLayout: MenuLayout get() = inner
    override val virtualWidth: Int get() = inner.virtualWidth
    override val virtualHeight: Int get() = inner.virtualHeight

    override fun getSlots(
        session: MenuSessionService.ActiveSession,
        viewport: Viewport,
    ): List<GuiSlot> {
        val base = inner.getSlots(session, viewport)
        val dynamic = slots()
        if (dynamic.isEmpty()) return base
        // Dynamic content wins on a shared coordinate: the page's placeholder is what it
        // replaces. Filtering here rather than letting both through avoids two items
        // fighting for one slot, which renders unpredictably depending on order.
        val taken = dynamic.mapTo(HashSet()) { it.x to it.y }
        return base.filterNot { (it.x to it.y) in taken } + dynamic
    }
}
