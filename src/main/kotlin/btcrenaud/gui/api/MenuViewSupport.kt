package btcrenaud.gui.api

import btcrenaud.gui.FrameLayoutData
import btcrenaud.gui.GuiType
import btcrenaud.gui.LayoutData
import btcrenaud.gui.StorageSlotData
import com.typewritermc.core.extension.annotations.Colored
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.extension.annotations.Placeholder
import com.typewritermc.core.interaction.InteractionContext
import com.typewritermc.engine.paper.entry.Criteria
import com.typewritermc.engine.paper.entry.matches
import com.typewritermc.engine.paper.entry.entries.ConstVar
import com.typewritermc.engine.paper.entry.entries.Var
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import kotlinx.serialization.Serializable

/**
 * Generic, data-driven view system for GUI menus.
 *
 * A *view* is one addressable screen of a menu: a domain tab, a sub-view of that domain,
 * or a leaf detail. Instead of one entry per screen (which forced every menu to duplicate
 * its whole chrome â€” tab strip, navigation row, dividers), a single entry declares its
 * [MenuViewData] list and the shell resolves the right layouts per frame at open time.
 *
 * ### How a frame picks its layout
 *
 * A [btcrenaud.gui.FrameData] whose `layoutId` is [VIEW_FRAME_PLACEHOLDER] (`"@view"`) is
 * resolved against the active view, in this order:
 *
 * 1. `view.frames[frameId]` â€” explicit mapping on the view;
 * 2. `"${view.id}_$frameId"` â€” the naming convention (frame `content` + view `island`
 *    resolves `island_content`);
 * 3. the same two lookups walked up [MenuViewData.parentId], so a sub-view inherits its
 *    parent's tab strip and action row without repeating them.
 *
 * A frame that resolves to nothing renders **empty** rather than disappearing: a silently
 * dropped content frame is the kind of mute failure that is impossible to diagnose in game.
 *
 * ### How a tab knows it is active
 *
 * Slots tagged `view:<viewId>` (i.e. `buttonPrefix = "view:"`, `buttonType = "<viewId>"`)
 * are wired automatically: [ViewTabResolverLayout] appends `gui:view <viewId>` to their
 * commands, hides the tabs whose view is gated off, and applies [ViewActiveStyle] to the
 * tab of the active view and of each of its ancestors â€” so a leaf keeps its domain tab lit.
 */
object MenuViewSupport {

    /** Frame `layoutId` placeholder resolved against the active view. */
    const val VIEW_FRAME_PLACEHOLDER = "@view"

    /** Slot tag prefix that turns a slot into a view tab. */
    const val VIEW_TAB_PREFIX = "view:"

    /** Suffix marking the ten-row projection of a root layout. */
    private const val EXTENDED_SUFFIX = "_extended"

    /**
     * Promotes a root layout to its ten-row variant when the resolved pool provides one.
     *
     * The extended projection is chosen from the pool rather than from a hard-coded id, so a menu
     * that declares its own shell (`codex_root`, `shop_root`, …) gets its extended variant exactly
     * like a menu inheriting the shared one. Matching a literal `"shell_root_extended"` instead
     * loses the bottom band — and every control placed in it — as soon as a root is renamed.
     *
     * @param requestedRootId the root id resolved by [inherit]; blank or null is returned as is.
     */
    fun extendedRootLayoutId(inherited: InheritedMenu, requestedRootId: String?): String? {
        val requested = requestedRootId?.takeIf { it.isNotBlank() } ?: return requestedRootId
        inherited.pool.keys.firstOrNull { it == "${requested}$EXTENDED_SUFFIX" }?.let { return it }
        // Fallback: an id absent from the resolved pool must not yield an empty menu. Rendering
        // the inherited shell beats rendering nothing at all.
        if (!inherited.pool.containsKey(requested)) {
            inherited.pool.keys.firstOrNull { it == "shell_root$EXTENDED_SUFFIX" }?.let { return it }
            if (inherited.pool.containsKey("shell_root")) return "shell_root"
        }
        return requested
    }

    /**
     * Whether [rootLayoutId] designates an extended projection — the test to use before setting
     * `extendToPlayerInventory`, rather than comparing to a literal layout id.
     */
    fun isExtendedRoot(rootLayoutId: String?): Boolean =
        rootLayoutId != null && rootLayoutId.endsWith(EXTENDED_SUFFIX)

    /**
     * Resolves the active view, its layout tree and its title.
     *
     * @param targetViewId the view asked for (via `gui:view`), or null to use [defaultViewId].
     * @param decorate hook for extension-specific resolver layouts, applied around the
     *   resolved tree before the tab resolver wraps it.
     */
    fun resolve(
        player: Player,
        context: InteractionContext,
        guiType: GuiType,
        totalSize: Int,
        pool: Map<String, LayoutData>,
        mainLayoutId: String?,
        views: List<MenuViewData>,
        defaultViewId: String?,
        targetViewId: String?,
        baseTitle: String,
        breadcrumbSeparator: String = DEFAULT_BREADCRUMB_SEPARATOR,
        storagePool: Map<String, StorageSlotData> = emptyMap(),
        decorate: (MenuLayout) -> MenuLayout = { it },
    ): ResolvedMenuView {
        val declared = views.filter { it.id.isNotBlank() }
        val byId = declared.associateBy { it.id }

        // A view whose criteria or permission fail is unreachable: its tab is hidden and
        // `gui:view` on it falls back to the default rather than opening a forbidden screen.
        val hidden = declared.filterNot { it.isVisibleTo(player, context) }.map { it.id }.toSet()

        // A requested view that simply does not exist used to fall through to the default in
        // complete silence â€” which is exactly how a whole family of navigation bugs stayed
        // indistinguishable from each other in game. An unknown id is a configuration mistake and
        // says so; a *hidden* id is not, it is the criteria doing their job.
        if (targetViewId != null && targetViewId.isNotBlank() && targetViewId !in byId) {
            org.bukkit.Bukkit.getLogger().warning(
                "[GUI] Unknown view '$targetViewId' requested for player ${player.name}; " +
                    "falling back to '${defaultViewId ?: declared.firstOrNull()?.id}'. " +
                    "Declared views: ${declared.joinToString(", ") { it.id }}"
            )
        }

        val active = sequenceOf(targetViewId, defaultViewId, declared.firstOrNull()?.id)
            .filterNotNull()
            .filter { it.isNotBlank() }
            .mapNotNull { byId[it] }
            .firstOrNull { it.id !in hidden }

        val chain = ancestry(active, byId)
        val breadcrumb = chain.map { it.label() }

        val rawTitle = (active?.title?.get(player, context)?.takeIf { it.isNotBlank() } ?: baseTitle)
            .replace("{breadcrumb}", breadcrumb.joinToString(breadcrumbSeparator))
            .replace("{view}", active?.label() ?: "")
            .replace("{root}", chain.firstOrNull()?.label() ?: "")

        val root = mainLayoutId?.let { pool[it] }
        val base = buildLayout(player, context, guiType, totalSize, pool, root, active, byId, storagePool)

        val activeIds = chain.map { it.id }.toSet()
        val layout = ViewTabResolverLayout(
            inner = decorate(base),
            activeViewIds = activeIds,
            styles = declared.associate { it.id to it.activeStyle },
            knownViewIds = byId.keys,
            hiddenViewIds = hidden,
            id = "view_tabs",
        )

        return ResolvedMenuView(
            activeViewId = active?.id,
            breadcrumb = breadcrumb,
            rawTitle = rawTitle,
            layout = layout,
        )
    }

    /**
     * Origine (x, y) de chaque layout du pool tel qu'il est effectivement posÃ© dans le chÃ¢ssis,
     * pour la vue active.
     *
     * Une extension qui injecte ses propres slots (tÃªtes de profil, cartes d'amis, membres d'un
     * groupe) doit dÃ©caler leurs coordonnÃ©es de l'origine de la frame qui les contient, sinon
     * elle dessine en coordonnÃ©es absolues et recouvre les bandeaux du menu.
     *
     * Indexer naÃ¯vement `frame.layoutId -> frame.x/y` ne suffit pas : dans le chÃ¢ssis, trois
     * frames sur quatre portent le layoutId littÃ©ral `"@view"`, elles s'Ã©crasent donc l'une
     * l'autre sous une seule clÃ© et le vrai layout de contenu se retrouve sans origine â€” c'est
     * ce qui faisait dessiner les profils en pleine fenÃªtre, par-dessus les onglets. La
     * rÃ©solution passe donc par [resolveFrameLayoutId], exactement comme le rendu.
     */
    fun frameOrigins(
        mainLayoutId: String?,
        pool: Map<String, LayoutData>,
        views: List<MenuViewData>,
        activeViewId: String?,
        defaultViewId: String?,
    ): Map<String, Pair<Int, Int>> {
        val frameLayout = mainLayoutId?.let { pool[it] } as? FrameLayoutData ?: return emptyMap()
        val declared = views.filter { it.id.isNotBlank() }
        val byId = declared.associateBy { it.id }
        val active = sequenceOf(activeViewId, defaultViewId, declared.firstOrNull()?.id)
            .filterNotNull().filter { it.isNotBlank() }.mapNotNull { byId[it] }.firstOrNull()

        val origins = mutableMapOf<String, Pair<Int, Int>>()
        for (frame in frameLayout.frames) {
            val declaredId = frame.layoutId ?: continue
            val effective = if (declaredId == VIEW_FRAME_PLACEHOLDER) {
                resolveFrameLayoutId(active, frame.id, pool, byId)
            } else declaredId
            if (effective != null) origins[effective] = frame.x to frame.y
        }
        return origins
    }

    /**
     * Default MiniMessage separator between breadcrumb segments.
     *
     * Menu titles sit on the dark vanilla chrome, where `dark_gray` is barely legible: the
     * separator follows the BTC palette's separator colour so the trail reads at a glance.
     * See `BTC-Serveur-Documentation/Directives/couleurs.md`.
     */
    const val DEFAULT_BREADCRUMB_SEPARATOR = "<#B76BFF> â–¸ </#B76BFF>"

    /**
     * Merges a menu with the shared shell it inherits from.
     *
     * Both the layout pool and the view list merge **under** the caller's own: an id declared
     * on both sides is won by the child. That is what lets one `_shell` entry own the domain
     * strip, the navigation anchors and the nine domain views, while each extension's menu
     * only declares what is its own.
     *
     * Lives here rather than in each entry because every menu-bearing extension needs the exact
     * same merge, and three divergent copies of it is how the tab mechanism got duplicated in
     * the first place.
     */
    fun inherit(
        baseMenuId: String,
        ownPool: List<LayoutData>,
        ownViews: List<MenuViewData>,
        ownMainLayoutId: String?,
        ownDefaultViewId: String?,
    ): InheritedMenu {
        // The chain is walked to its root, so a menu can inherit a domain template that itself
        // inherits the global shell. Resolving a single level would silently drop the shell's
        // domain strip two levels down â€” a failure that only shows up in game.
        val chain = mutableListOf<btcrenaud.gui.OpenGuiActionEntry>()
        val seen = mutableSetOf<String>()
        var currentId = baseMenuId
        while (currentId.isNotBlank() && seen.add(currentId)) {
            val entry = com.typewritermc.core.entries.Ref(
                currentId, btcrenaud.gui.OpenGuiActionEntry::class
            ).get() ?: break
            chain.add(entry)
            currentId = entry.baseMenuId
        }

        // Outermost ancestor first: each descendant merges over it, so the caller wins.
        val ancestors = chain.asReversed()

        val pool = (ancestors.flatMap { it.layoutPool } + ownPool)
            .filterNotNull()
            .associateBy { it.id }

        val views = (ancestors.flatMap { it.views } + ownViews)
            .filter { it.id.isNotBlank() }
            .associateBy { it.id }
            .values
            .toList()

        return InheritedMenu(
            pool = pool,
            views = views,
            // `takeIf { isNotBlank() }` as for defaultViewId below: a menu that inherits its whole
            // chassis leaves this field empty. Without the filter the empty string outranked the
            // inheritance, no pool layout answered that id, and the menu rendered EMPTY — so no
            // button existed to receive the click.
            mainLayoutId = ownMainLayoutId?.takeIf { it.isNotBlank() }
                ?: chain.firstNotNullOfOrNull { it.mainLayoutId?.takeIf { id -> id.isNotBlank() } },
            defaultViewId = ownDefaultViewId?.takeIf { it.isNotBlank() }
                ?: chain.firstNotNullOfOrNull { it.defaultViewId?.takeIf { id -> id.isNotBlank() } },
            size = chain.firstNotNullOfOrNull { it.size },
        )
    }

    /**
     * Parses [root], resolving [VIEW_FRAME_PLACEHOLDER] frames against [view].
     *
     * Only the top-level frame layout carries view placeholders â€” nesting them deeper would
     * make a menu's structure depend on render order, which is exactly the kind of implicit
     * coupling the pool is meant to avoid.
     */
    private fun buildLayout(
        player: Player,
        context: InteractionContext,
        guiType: GuiType,
        totalSize: Int,
        pool: Map<String, LayoutData>,
        root: LayoutData?,
        view: MenuViewData?,
        views: Map<String, MenuViewData>,
        storagePool: Map<String, StorageSlotData>,
    ): MenuLayout {
        if (root == null) return EmptyLayout
        if (root !is FrameLayoutData) {
            return btcrenaud.gui.api.LayoutParser.parse(player, context, guiType, totalSize, pool, root, storagePool = storagePool)
        }

        val frames = root.frames.map { frame ->
            val layoutId = if (frame.layoutId == VIEW_FRAME_PLACEHOLDER) {
                resolveFrameLayoutId(view, frame.id, pool, views)
            } else {
                frame.layoutId
            }
            val inner = layoutId
                ?.let { pool[it] }
                ?.let {
                    btcrenaud.gui.api.LayoutParser.parse(
                        player, context, guiType, totalSize, pool, it,
                        nested = true, width = frame.width,
                        storagePool = storagePool,
                    )
                }
                ?: EmptyLayout
            MenuFrame(frame.id, frame.x, frame.y, frame.width, frame.height, inner)
        }
        return FrameLayout(frames, root.id)
    }

    /**
     * Finds which pool layout fills [frameId] for [view], walking up the parent chain.
     * The [seen] guard makes a mis-authored `parentId` cycle render an empty frame instead
     * of hanging the render thread.
     */
    private fun resolveFrameLayoutId(
        view: MenuViewData?,
        frameId: String,
        pool: Map<String, LayoutData>,
        views: Map<String, MenuViewData>,
    ): String? {
        var current = view
        val seen = mutableSetOf<String>()
        while (current != null && seen.add(current.id)) {
            current.frames[frameId]?.takeIf { pool.containsKey(it) }?.let { return it }
            "${current.id}_$frameId".takeIf { pool.containsKey(it) }?.let { return it }
            current = current.parentId.takeIf { it.isNotBlank() }?.let { views[it] }
        }
        return null
    }

    /** The active view and its ancestors, outermost first â€” the breadcrumb order. */
    private fun ancestry(view: MenuViewData?, views: Map<String, MenuViewData>): List<MenuViewData> {
        if (view == null) return emptyList()
        val chain = mutableListOf<MenuViewData>()
        var current: MenuViewData? = view
        val seen = mutableSetOf<String>()
        while (current != null && seen.add(current.id)) {
            chain.add(current)
            current = current.parentId.takeIf { it.isNotBlank() }?.let { views[it] }
        }
        return chain.reversed()
    }
}

/** Outcome of [MenuViewSupport.inherit]: a menu merged with the shell it inherits from. */
data class InheritedMenu(
    val pool: Map<String, LayoutData>,
    val views: List<MenuViewData>,
    val mainLayoutId: String?,
    val defaultViewId: String?,
    val size: btcrenaud.gui.InventorySize?,
)

/** Outcome of [MenuViewSupport.resolve]: everything the caller needs to build a definition. */
data class ResolvedMenuView(
    val activeViewId: String?,
    val breadcrumb: List<String>,
    val rawTitle: String,
    val layout: MenuLayout,
)

/**
 * One addressable screen of a menu.
 *
 * The [frames] map and the `"${id}_$frameId"` convention are interchangeable â€” use the
 * convention for the common case and the map when a view reuses another view's layout.
 */
@Serializable
data class MenuViewData(
    @Help("Identifier used by `gui:view <id>` and by the `view:<id>` slot tags.")
    val id: String = "",
    @Help("Display name used in the breadcrumb and by the {view} title token. Falls back to the id.")
    val name: String = "",
    @Help("Title for this view. Supports {breadcrumb}, {view} and {root} tokens. Empty = the menu title.")
    @Placeholder @Colored
    val title: Var<String>? = null,
    @Help("Parent view. Builds the breadcrumb and keeps the parent's tab highlighted on sub-views.")
    val parentId: String = "",
    @Help("Explicit frame id -> pool layout id mapping. Takes precedence over the '{viewId}_{frameId}' convention.")
    val frames: Map<String, String> = emptyMap(),
    @Help("The view is unreachable and its tab hidden when these do not match.")
    val criteria: List<Criteria> = emptyList(),
    @Help("Permission required to reach this view. Without it the tab is hidden.")
    val viewPermission: String? = null,
    @Help("How this view's tab is rendered while it is the active one.")
    val activeStyle: ViewActiveStyle = ViewActiveStyle(),
    @Help("Internal web-editor id. Managed automatically â€” do not edit.")
    val editorId: String? = null,
) {
    fun label(): String = name.ifBlank { id }

    fun isVisibleTo(player: Player, context: InteractionContext): Boolean {
        if (!viewPermission.permits(player)) return false
        return criteria.matches(player, context)
    }
}

/**
 * Visual treatment of the tab belonging to the active view.
 *
 * Every field is opt-in so a page decides how "active" looks â€” the previous hard-coded
 * enchantment glint was invisible to page authors and impossible to restyle.
 */
@Serializable
data class ViewActiveStyle(
    @Help("Add the enchantment glint to the active tab.")
    val glint: Boolean = true,
    @Help("Replace the active tab's material. Empty = keep the configured one.")
    val material: Material? = null,
    @Help("custom_model_data forced on the active tab. 0 = leave untouched.")
    val customModelData: Int = 0,
    @Help("Name of the active tab. '{name}' is replaced by the configured name. Empty = keep it.")
    @Colored
    val nameFormat: String = "",
    @Help("Lore lines appended to the active tab.")
    @Colored
    val loreSuffix: List<String> = emptyList(),
) {
    /** Applies this style to [stack], returning a copy. */
    fun apply(stack: ItemStack): ItemStack {
        val out = stack.clone()
        material?.let { out.type = it }
        val meta = out.itemMeta ?: return out
        if (glint) meta.setEnchantmentGlintOverride(true)
        if (customModelData > 0) {
            @Suppress("DEPRECATION")
            meta.setCustomModelData(customModelData)
        }
        if (nameFormat.isNotBlank()) {
            val original = meta.displayName()?.let { MiniMessage.miniMessage().serialize(it) } ?: ""
            meta.displayName(nameFormat.replace("{name}", original).asUprightMini())
        }
        if (loreSuffix.isNotEmpty()) {
            meta.lore((meta.lore() ?: emptyList()) + loreSuffix.map { it.asUprightMini() })
        }
        out.itemMeta = meta
        return out
    }

    private fun String.asUprightMini(): Component =
        runCatching { MiniMessage.miniMessage().deserialize(this) }
            .getOrElse { Component.text(this) }
            .decoration(TextDecoration.ITALIC, false)
}

/**
 * Wires and styles the `view:<id>` tagged slots of [inner].
 *
 * Runs outermost so it sees the slots produced by every nested layout â€” including the
 * scrollable tab strips, whose visible window changes between renders.
 */
class ViewTabResolverLayout(
    private val inner: MenuLayout,
    private val activeViewIds: Set<String>,
    private val styles: Map<String, ViewActiveStyle>,
    private val knownViewIds: Set<String>,
    private val hiddenViewIds: Set<String>,
    override val id: String? = null,
) : MenuLayout {

    override val innerLayout: MenuLayout get() = inner
    override val virtualWidth: Int get() = inner.virtualWidth
    override val virtualHeight: Int get() = inner.virtualHeight

    override fun getSlots(
        session: btcrenaud.gui.services.MenuSessionService.ActiveSession,
        viewport: Viewport,
    ): List<GuiSlot> = inner.getSlots(session, viewport).mapNotNull { slot ->
        val tag = slot.tag
        if (tag == null || !tag.startsWith(MenuViewSupport.VIEW_TAB_PREFIX)) return@mapNotNull slot

        val viewId = tag.removePrefix(MenuViewSupport.VIEW_TAB_PREFIX)
        if (viewId.isEmpty() || viewId !in knownViewIds) return@mapNotNull slot
        if (viewId in hiddenViewIds) return@mapNotNull null

        // Auto-wiring is a convenience, not a rule: a tab that already carries its own
        // navigation keeps it. This is what lets the shared domain strip highlight the active
        // domain via its `view:` tag while actually jumping to another extension's entry with
        // `gui:open` â€” appending `gui:view` there would fire both navigations at once.
        val wired = if (slot.declaresNavigation()) slot
        else slot.copy(commands = slot.commands + "gui:view $viewId")

        if (viewId !in activeViewIds) wired
        else wired.copy(item = (styles[viewId] ?: ViewActiveStyle()).apply(wired.item))
    }

    /**
     * True when the slot already routes somewhere on its own â€” whether on the slot's own
     * command list (scroll/pagination wiring) or inside one of its per-click interactions,
     * which is where a page's `interactionList` ends up.
     */
    private fun GuiSlot.declaresNavigation(): Boolean {
        val own = commands.any { it.isNavigation() }
        if (own) return true
        return interactions.values.any { interaction -> interaction.commands.any { it.isNavigation() } }
    }

    private fun String.isNavigation(): Boolean =
        NAVIGATION_COMMANDS.any { startsWith(it) }

    private companion object {
        /** Commands that move the player to another screen. */
        val NAVIGATION_COMMANDS = listOf("gui:view ", "gui:open ", "gui:action ", "gui:back", "gui:home")
    }
}
