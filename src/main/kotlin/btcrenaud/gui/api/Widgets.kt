package btcrenaud.gui.api

import btcrenaud.gui.services.MenuSessionService
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import java.util.concurrent.atomic.AtomicReference

/**
 * Reusable, state-driven GUI widgets built on top of [ReactiveSlot].
 *
 * All widgets repaint automatically: their visual comes from a [StateSource]
 * resolved on every render, and interaction callbacks trigger a refresh. They
 * are pure builder helpers — no new engine concept, no hardcoded content.
 */

/** Wraps a plain lambda as a [StateSource]. */
fun <T> stateOf(provider: (Player) -> T): StateSource<T> = object : StateSource<T> {
    override fun get(player: Player): T = provider(player)
}

/**
 * A boolean toggle. Shows [onItem] when the state reads true, [offItem] otherwise;
 * a primary click invokes [onToggle] with the *new* value.
 *
 * The caller owns the persisted state (fact, PDC, config...); the widget only
 * reads it via [state] and reports the requested flip via [onToggle].
 */
fun MenuBuilder.toggle(
    x: Int,
    y: Int,
    state: StateSource<Boolean>,
    onItem: ItemStack,
    offItem: ItemStack,
    onToggle: (Player, Boolean) -> Unit,
) = apply {
    addSlot(
        ReactiveSlot(
            x = x,
            y = y,
            source = stateOf { player -> if (state.get(player)) onItem else offItem },
            onClick = { player, _ -> onToggle(player, !state.get(player)) }
        )
    )
}

/**
 * A horizontal progress bar spanning [width] slots starting at ([x],[y]).
 * [progress] returns a 0.0–1.0 fraction; fully covered cells show [filled],
 * empty cells show [empty], and the single boundary cell shows [partial] when
 * provided (otherwise it rounds to filled/empty).
 */
fun MenuBuilder.progressBar(
    x: Int,
    y: Int,
    width: Int,
    progress: StateSource<Double>,
    filled: ItemStack,
    empty: ItemStack,
    partial: ItemStack? = null,
) = apply {
    require(width >= 1) { "progressBar width must be >= 1" }
    for (i in 0 until width) {
        val cellIndex = i
        addSlot(
            ReactiveSlot(
                x = x + i,
                y = y,
                source = stateOf { player ->
                    val p = progress.get(player).coerceIn(0.0, 1.0)
                    val exact = p * width
                    val full = exact.toInt()
                    when {
                        cellIndex < full -> filled
                        cellIndex == full && partial != null && exact - full > 0.001 -> partial
                        cellIndex == full && partial == null && exact - full >= 0.5 -> filled
                        else -> empty
                    }
                }
            )
        )
    }
}

/** One tab in a [tabs] widget: the items shown when active vs inactive. */
data class TabSpec(val activeItem: ItemStack, val inactiveItem: ItemStack)

/**
 * A horizontal tab strip starting at ([x],[y]). [active] returns the selected
 * index; each tab shows its active or inactive item accordingly, and clicking a
 * tab invokes [onSelect] with its index.
 */
fun MenuBuilder.tabs(
    x: Int,
    y: Int,
    tabs: List<TabSpec>,
    active: StateSource<Int>,
    onSelect: (Player, Int) -> Unit,
) = apply {
    tabs.forEachIndexed { index, tab ->
        addSlot(
            ReactiveSlot(
                x = x + index,
                y = y,
                source = stateOf { player -> if (active.get(player) == index) tab.activeItem else tab.inactiveItem },
                onClick = { player, _ -> onSelect(player, index) }
            )
        )
    }
}

/**
 * Builds an [ItemStack] off the main thread (heavy NBT, custom heads, remote
 * lookups) via [builder], then applies it to the slot at ([x],[y]) on the
 * player's region thread once ready. [placeholder] shows immediately.
 *
 * Use for expensive per-item construction that would otherwise stall the tick
 * loop on large paginated menus.
 */
fun MenuBuilder.asyncSlot(
    x: Int,
    y: Int,
    placeholder: ItemStack,
    player: Player,
    builder: () -> ItemStack,
) = apply {
    val cache = AtomicReference(placeholder)
    addSlot(ReactiveSlot(x = x, y = y, source = stateOf { cache.get() }))

    val plugin: Plugin = Bukkit.getPluginManager().getPlugin("Typewriter") ?: return@apply
    Bukkit.getAsyncScheduler().runNow(plugin) { _ ->
        val built = runCatching(builder).getOrNull() ?: return@runNow
        cache.set(built)
        // Repaint on the player's region thread once the item is ready.
        player.scheduler.run(plugin, { _ -> MenuSessionService.refresh(player) }, null)
    }
}
