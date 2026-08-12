package btcrenaud.gui.editor.validation

import btcrenaud.gui.GuiType
import btcrenaud.gui.InventorySize
import btcrenaud.gui.api.MenuViewSupport.VIEW_FRAME_PLACEHOLDER
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/**
 * Server-side validation of staged `open_gui` entries (the authority — the web
 * editor mirrors these rules live, but nothing invalid may be persisted).
 *
 * Works directly on the staging JSON. Layout pool elements use the algebraic
 * encoding `{"case":"simple","value":{...}}` (legacy array form `["simple",{...}]`
 * is normalized first, mirroring AlgebraicSerialization).
 *
 * Bound semantics mirror [btcrenaud.gui.api.LayoutParser]:
 * - width is bounded (0 until width); width defaults to 9, a Scrollable's
 *   virtualWidth applies to its inner layout, a Frame bounds its inner layout
 *   to the frame width.
 * - height is bounded by the physical rows at the root, and by the virtual
 *   height when the layout is reached through a Scrollable.
 * - Collisions are only errors between two UNCONDITIONAL items (no criteria,
 *   no viewPermission) of the same layout — conditional overlaps are a
 *   legitimate pattern (state-dependent slots).
 */
object MenuValidationService {

    data class Issue(
        val severity: Severity,
        val code: String,
        val message: String,
        /** editorId of the offending element when known (clickable in the editor). */
        val editorId: String? = null,
        /** Human-readable location, e.g. "layoutPool[2].items[4]". */
        val path: String = "",
    )

    enum class Severity { ERROR, WARNING }

    data class Result(val issues: List<Issue>) {
        val errors: List<Issue> get() = issues.filter { it.severity == Severity.ERROR }
        val warnings: List<Issue> get() = issues.filter { it.severity == Severity.WARNING }
        val isValid: Boolean get() = errors.isEmpty()

        fun toJson(): JsonObject = JsonObject().apply {
            add("errors", issuesArray(errors))
            add("warnings", issuesArray(warnings))
        }

        private fun issuesArray(list: List<Issue>): JsonArray = JsonArray().apply {
            for (issue in list) add(JsonObject().apply {
                addProperty("code", issue.code)
                addProperty("message", issue.message)
                issue.editorId?.let { addProperty("editorId", it) }
                addProperty("path", issue.path)
            })
        }
    }

    /**
     * Pluggable buttonType validator, wired by the ButtonTypeRegistry.
     * Returns null when the type cannot be checked (no registry contribution),
     * true/false when it can. Severity of unknown types is controlled by
     * [strictButtonTypes] (progressive strictness: warning until every extension
     * has registered its contribution, then flipped to error).
     */
    @Volatile
    var buttonTypeValidator: ((buttonType: String, buttonPrefix: String?, entry: JsonObject) -> Boolean?)? = null

    @Volatile
    var strictButtonTypes: Boolean = false

    private val KNOWN_CASES = setOf(
        "simple", "flex", "paginated", "scrollable", "frame",
        "composite", "book", "merchant", "storage",
    )

    /** A normalized layout pool element. */
    private data class PoolLayout(
        val case: String,
        val value: JsonObject,
        val id: String,
        val editorId: String?,
        val path: String,
    )

    /** Bounds context while walking layout references. */
    /**
     * [space] identifies the coordinate space the layout is drawn in: two slots can only shadow
     * each other inside the SAME space. The root grid is one space; every `scrollable` opens a
     * new one (its content is virtual and scrolls independently), and so does each view filling
     * an `@view` frame (views never coexist on screen). [offsetX]/[offsetY] carry the frame
     * offsets, so a frame's local (0,0) maps to its real position on the grid.
     */
    private data class Bounds(
        val width: Int,
        val maxRows: Int?,
        val space: String = ROOT_SPACE,
        val offsetX: Int = 0,
        val offsetY: Int = 0,
        /**
         * False when an item's real position is NOT the one written in `x`/`y`.
         *
         * Only `flex` qualifies: [btcrenaud.gui.api.FlexLayout.getSlots] recomputes positions
         * entirely from `justifyContent`/`alignItems`, so the authored `x`/`y` describe nothing
         * and are usually left at zero for every item.
         *
         * Vanilla containers are NOT in that case, contrary to what a first version assumed:
         * [btcrenaud.gui.GuiSlotBuilder.build] reads `x`/`y` whatever the `guiType`. Exempting
         * them turned genuine stacks into silence.
         */
        val authoredPositions: Boolean = true,
        /**
         * The REAL inventory height, in rows.
         *
         * A frame does not clip: [FrameLayout][btcrenaud.gui.api.FrameLayout] offsets its
         * children, it does not crop them. A slot past its frame's height still renders — just
         * lower than intended. The only hard limit is the inventory itself, beyond which the
         * render pass drops the slot for good.
         */
        val inventoryRows: Int = 6,
    )

    private const val ROOT_SPACE = "root"

    /** Ten-row variant suffix of a root layout, as in [btcrenaud.gui.api.MenuViewSupport]. */
    private const val EXTENDED_SUFFIX = "_extended"

    /** Six chest rows plus the four projected onto the player's inventory. */
    private const val EXTENDED_ROWS = 10

    /**
     * Validates a staged entry. [lookupEntry] resolves another staged entry by id
     * (for baseMenuId template chains); it must return the raw staging JsonObject.
     */
    fun validate(entry: JsonObject, lookupEntry: (String) -> JsonObject?): Result {
        val issues = mutableListOf<Issue>()

        // ── Basic fields ────────────────────────────────────────────────────
        val guiTypeName = entry.get("guiType")?.takeIf { it.isJsonPrimitive }?.asString
        val guiType = guiTypeName?.let { name ->
            GuiType.entries.firstOrNull { it.name == name }.also {
                if (it == null) issues += Issue(
                    Severity.ERROR, "menu.guiType.unknown",
                    "Unknown menu type '$name'.", path = "guiType",
                )
            }
        } ?: GuiType.CUSTOM

        val sizeSlots = entry.get("size")?.takeIf { it.isJsonPrimitive }?.asString?.let { raw ->
            val parsed = InventorySize.entries.firstOrNull {
                it.slots.toString() == raw || it.name == raw
            }
            if (parsed == null) issues += Issue(
                Severity.ERROR, "menu.size.invalid",
                "Invalid inventory size '$raw' (expected 9/18/27/36/45/54).", path = "size",
            )
            parsed?.slots
        }

        val totalSize = when (guiType) {
            GuiType.CUSTOM -> sizeSlots ?: 54
            else -> guiType.inventoryType?.defaultSize ?: 54
        }
        val physicalRows = (totalSize + 8) / 9
        // Only a CUSTOM inventory is a grid: vanilla containers (anvil, hopper, brewing stand…)
        // fill their cells in declaration order, so their authors leave `x`/`y` at zero.
        val declaresExtended = entry.get("extendToPlayerInventory")
            ?.takeIf { it.isJsonPrimitive }?.asBoolean == true

        // ── Layout pool ─────────────────────────────────────────────────────
        val pool = parsePool(entry, issues)
        val poolById = mutableMapOf<String, PoolLayout>()
        for (layout in pool) {
            if (layout.id.isBlank()) {
                issues += Issue(
                    Severity.ERROR, "layout.id.blank",
                    "A '${layout.case}' layout has no id — every layout in the pool needs a unique one.",
                    editorId = layout.editorId, path = layout.path,
                )
                continue
            }
            val existing = poolById.put(layout.id, layout)
            if (existing != null) {
                issues += Issue(
                    Severity.ERROR, "layout.id.duplicate",
                    "Two pool layouts share the id '${layout.id}' — the second overwrites the first at runtime.",
                    editorId = layout.editorId, path = layout.path,
                )
            }
        }

        // ── Template chain (baseMenuId) ─────────────────────────────────────
        val effectivePool = HashMap<String, PoolLayout>()
        var effectiveMainLayoutId = entry.get("mainLayoutId")
            ?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

        // Inherited views, in chain order: the template declares them, the child inherits them.
        // Without this merge, every page reusing a shared shell was accused of "declaring no
        // views" while the runtime hands them over (MenuViewSupport.inherit).
        val effectiveViews = LinkedHashMap<String, com.google.gson.JsonElement>()

        val chainIds = mutableSetOf(entryId(entry))
        var baseId = entry.get("baseMenuId")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
        var chainPath = "baseMenuId"
        while (baseId != null) {
            if (!chainIds.add(baseId)) {
                issues += Issue(
                    Severity.ERROR, "template.cycle",
                    "Template chain loops back through '$baseId'.", path = chainPath,
                )
                break
            }
            val base = lookupEntry(baseId)
            if (base == null) {
                issues += Issue(
                    Severity.ERROR, "template.missing",
                    "Template menu '$baseId' does not exist.", path = chainPath,
                )
                break
            }
            // Base layouts merge UNDER the child's (same id = child overrides).
            for (layout in parsePool(base, mutableListOf())) {
                if (layout.id.isNotBlank()) effectivePool.putIfAbsent(layout.id, layout)
            }
            if (effectiveMainLayoutId == null) {
                effectiveMainLayoutId = base.get("mainLayoutId")
                    ?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
            }
            for ((viewId, view) in declaredViews(base)) effectiveViews.putIfAbsent(viewId, view)
            baseId = base.get("baseMenuId")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
            chainPath += " → $baseId"
        }
        // Own layouts override inherited ones.
        effectivePool.putAll(poolById)
        // Same for views: a view redeclared by the child replaces the template's.
        for ((viewId, view) in declaredViews(entry)) effectiveViews[viewId] = view

        // The entry used for the walk carries the effective views. A copy is made rather than
        // mutating the caller's object: the validator must leave nothing behind in the page it
        // was handed.
        val walkEntry = if (effectiveViews.size == declaredViews(entry).size) entry else {
            entry.deepCopy().also { copy ->
                copy.add("views", com.google.gson.JsonArray().apply {
                    effectiveViews.values.forEach { add(it) }
                })
            }
        }

        // ── Main layout resolution ──────────────────────────────────────────
        if (effectiveMainLayoutId == null) {
            if (effectivePool.isNotEmpty()) issues += Issue(
                Severity.WARNING, "menu.mainLayout.missing",
                "No mainLayoutId set — the menu opens empty.", path = "mainLayoutId",
            )
        } else if (effectiveMainLayoutId !in effectivePool) {
            issues += Issue(
                Severity.ERROR, "menu.mainLayout.unresolved",
                "mainLayoutId '$effectiveMainLayoutId' matches no layout in the pool (templates included).",
                path = "mainLayoutId",
            )
        }

        // ── Extended-chassis promotion ──────────────────────────────────────
        //
        // Mirrors [MenuViewSupport.extendedRootLayoutId]: when the pool holds the
        // `<root>_extended` variant, THAT is what the runtime renders — a quest codex or a shop
        // applies the promotion as it opens the page, and the window grows to ten rows. Measuring
        // the page against the written root, three times shorter, made a marker grid authored for
        // the extended 9x7 content frame land on the action row: an overlap announced where the
        // screen shows none.
        val promotedRoot = effectiveMainLayoutId
            ?.let { "$it$EXTENDED_SUFFIX" }
            ?.takeIf { it in effectivePool }
        val rootLayoutId = promotedRoot ?: effectiveMainLayoutId
        val inventoryRows = when {
            guiType != GuiType.CUSTOM -> physicalRows
            declaresExtended || promotedRoot != null -> EXTENDED_ROWS
            else -> physicalRows
        }

        // ── Reference walk: bounds, references, cycles, collisions ──────────
        val visited = mutableSetOf<String>()
        val referenced = mutableSetOf<String>()
        // Occupancy shared by the WHOLE composite menu: two different pools placing a slot on the
        // same cell overlap too. The per-PoolLayout map could not see it.
        val occupancy = HashMap<String, Occupant>()
        rootLayoutId?.let { mainId ->
            effectivePool[mainId]?.let { main ->
                walkLayout(
                    main,
                    Bounds(
                        width = 9, maxRows = inventoryRows,
                        inventoryRows = inventoryRows,
                    ),
                    effectivePool, totalSize, physicalRows,
                    stack = mutableListOf(), visited = visited, referenced = referenced,
                    issues = issues, entry = walkEntry, occupancy = occupancy,
                )
            }
        }

        // Layouts nothing reaches are still walked, so their slots get checked — but their
        // solitude is NOT reported.
        //
        // It proves nothing. A template chassis keeps variants in reserve for its child pages to
        // pick up (`shell_root_extended`, `shell_hotbar`), and an extension consumes its own from
        // its code with no frame naming them (a quest codex's sort anchor). The validator sees
        // neither the children nor the extensions' code, so it cannot tell a dead layout from one
        // awaited elsewhere. A genuinely broken reference is still caught by
        // `menu.mainLayout.unresolved` and by the frame `layoutId` check.
        for (layout in poolById.values) {
            if (layout.id in referenced || layout.id == rootLayoutId) continue
            // Own coordinate space: a layout nothing reaches cannot shadow anyone.
            walkLayout(
                layout,
                Bounds(
                    width = 9, maxRows = null, space = "unreferenced:${layout.id}",
                    inventoryRows = inventoryRows,
                ),
                effectivePool, totalSize, physicalRows,
                stack = mutableListOf(), visited = visited, referenced = referenced,
                issues = issues, entry = walkEntry, occupancy = occupancy,
            )
        }

        return Result(issues)
    }

    // ─── Pool parsing ───────────────────────────────────────────────────────

    private fun entryId(entry: JsonObject): String =
        entry.get("id")?.takeIf { it.isJsonPrimitive }?.asString
            ?: entry.get("entryId")?.takeIf { it.isJsonPrimitive }?.asString ?: ""

    private fun parsePool(entry: JsonObject, issues: MutableList<Issue>): List<PoolLayout> {
        val raw = entry.get("layoutPool")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        val result = mutableListOf<PoolLayout>()
        raw.forEachIndexed { index, element ->
            val path = "layoutPool[$index]"
            val normalized: JsonObject? = when {
                element.isJsonObject -> element.asJsonObject
                element.isJsonArray -> {
                    val arr = element.asJsonArray
                    if (arr.size() in 1..2 && arr[0].isJsonPrimitive) JsonObject().apply {
                        add("case", arr[0])
                        add("value", if (arr.size() == 2) arr[1] else JsonObject())
                    } else null
                }
                else -> null
            }
            if (normalized == null) {
                issues += Issue(Severity.ERROR, "layout.malformed", "Unreadable layoutPool element.", path = path)
                return@forEachIndexed
            }
            val case = normalized.get("case")?.takeIf { it is JsonPrimitive }?.asString
            if (case == null) {
                issues += Issue(Severity.ERROR, "layout.case.missing", "layoutPool element has no 'case' field.", path = path)
                return@forEachIndexed
            }
            if (case !in KNOWN_CASES) {
                issues += Issue(
                    Severity.ERROR, "layout.case.unknown",
                    "Unknown layout type '$case' (expected: ${KNOWN_CASES.sorted().joinToString(", ")}).",
                    path = path,
                )
                return@forEachIndexed
            }
            val value = normalized.get("value")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
            result += PoolLayout(
                case = case,
                value = value,
                id = value.get("id")?.takeIf { it.isJsonPrimitive }?.asString ?: "",
                editorId = value.get("editorId")?.takeIf { it.isJsonPrimitive }?.asString,
                path = path,
            )
        }
        return result
    }

    // ─── Layout walk ────────────────────────────────────────────────────────

    private fun walkLayout(
        layout: PoolLayout,
        bounds: Bounds,
        pool: Map<String, PoolLayout>,
        totalSize: Int,
        physicalRows: Int,
        stack: MutableList<String>,
        visited: MutableSet<String>,
        referenced: MutableSet<String>,
        issues: MutableList<Issue>,
        entry: JsonObject,
        occupancy: MutableMap<String, Occupant>,
    ) {
        if (layout.id in stack) {
            issues += Issue(
                Severity.ERROR, "layout.reference.cycle",
                "Layout reference cycle: ${(stack + layout.id).joinToString(" -> ")}.",
                editorId = layout.editorId, path = layout.path,
            )
            return
        }
        // A layout may be reached from several parents with different bounds —
        // re-validate per usage, but guard against exponential walks.
        val visitKey = "${layout.id}@${bounds.space}+${bounds.offsetX},${bounds.offsetY}:${bounds.width}x${bounds.maxRows}"
        if (!visited.add(visitKey)) return
        stack.add(layout.id)

        when (layout.case) {
            "simple", "flex" -> {
                val maxRows = if (layout.case == "flex") {
                    layout.value.get("virtualHeight")?.takeIf { it.isJsonPrimitive }?.asInt ?: bounds.maxRows
                } else bounds.maxRows
                // `flex` places its own items: their `x`/`y` describe nothing.
                val authored = bounds.authoredPositions && layout.case != "flex"
                validateItems(
                    layout, bounds.copy(maxRows = maxRows, authoredPositions = authored),
                    issues, entry, occupancy,
                )
            }
            "paginated" -> {
                val slots = layout.value.get("slots")?.takeIf { it.isJsonArray }?.asJsonArray
                slots?.forEachIndexed { i, slot ->
                    val index = slot.takeIf { it.isJsonPrimitive }?.asInt ?: return@forEachIndexed
                    if (index !in 0 until totalSize) issues += Issue(
                        Severity.ERROR, "paginated.slot.outOfBounds",
                        "Layout '${layout.id}': physical slot $index is outside the inventory (0..${totalSize - 1}).",
                        editorId = layout.editorId, path = "${layout.path}.slots[$i]",
                    )
                }
                validateButtonTypesOnly(layout, "items", issues, entry)
            }
            "scrollable" -> {
                val innerId = layout.value.get("innerId")?.takeIf { it.isJsonPrimitive }?.asString
                val virtualWidth = layout.value.get("virtualWidth")?.takeIf { it.isJsonPrimitive }?.asInt
                val virtualHeight = layout.value.get("virtualHeight")?.takeIf { it.isJsonPrimitive }?.asInt
                if (innerId.isNullOrBlank()) {
                    issues += Issue(
                        Severity.WARNING, "scrollable.inner.missing",
                        "Scrollable layout '${layout.id}' has no innerId — it displays nothing.",
                        editorId = layout.editorId, path = layout.path,
                    )
                } else {
                    val inner = pool[innerId]
                    if (inner == null) {
                        issues += Issue(
                            Severity.ERROR, "scrollable.inner.unresolved",
                            "Scrollable layout '${layout.id}': innerId '$innerId' is not in the pool.",
                            editorId = layout.editorId, path = layout.path,
                        )
                    } else {
                        referenced += innerId
                        // A scrollable opens its own space: its content scrolls, so it shadows
                        // nothing on the fixed grid.
                        walkLayout(
                            inner,
                            Bounds(
                                width = virtualWidth ?: 9,
                                maxRows = virtualHeight,
                                space = "scrollable:${layout.id}",
                            ),
                            pool, totalSize, physicalRows, stack, visited, referenced, issues, entry, occupancy,
                        )
                    }
                }
            }
            "frame" -> {
                val frames = layout.value.get("frames")?.takeIf { it.isJsonArray }?.asJsonArray
                frames?.forEachIndexed { i, frameEl ->
                    val frame = frameEl.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEachIndexed
                    val fx = frame.get("x")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
                    val fy = frame.get("y")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
                    val fw = frame.get("width")?.takeIf { it.isJsonPrimitive }?.asInt ?: 9
                    val fh = frame.get("height")?.takeIf { it.isJsonPrimitive }?.asInt ?: 1
                    val framePath = "${layout.path}.frames[$i]"
                    val frameEditorId = frame.get("editorId")?.takeIf { it.isJsonPrimitive }?.asString
                    if (fx < 0 || fy < 0 || fx + fw > bounds.width || (bounds.maxRows != null && fy + fh > bounds.maxRows)) {
                        issues += Issue(
                            Severity.ERROR, "frame.outOfBounds",
                            "Layout '${layout.id}': frame '${frame.get("id")?.asString ?: i}' ($fx,$fy ${fw}x$fh) overflows the available space (${bounds.width}x${bounds.maxRows ?: "unbounded"}).",
                            editorId = frameEditorId, path = framePath,
                        )
                    }
                    val layoutId = frame.get("layoutId")?.takeIf { it.isJsonPrimitive }?.asString
                    if (layoutId.isNullOrBlank()) {
                        issues += Issue(
                            Severity.WARNING, "frame.layout.missing",
                            "Frame without layoutId in '${layout.id}' — it is skipped at render time.",
                            editorId = frameEditorId, path = framePath,
                        )
                    } else if (layoutId == VIEW_FRAME_PLACEHOLDER) {
                        // '@view' is not a pool id: MenuViewSupport resolves it per active view
                        // at open time. Treating it as an unresolved reference reported every
                        // view-based menu as broken. Validate it against the declared views instead.
                        val frameId = frame.get("id")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                        validateViewFrame(
                            frameId, frameEditorId, framePath, layout.id,
                            bounds.copy(
                                width = fw, maxRows = fh,
                                offsetX = bounds.offsetX + fx, offsetY = bounds.offsetY + fy,
                            ),
                            pool, totalSize, physicalRows, stack, visited, referenced, issues, entry, occupancy,
                        )
                    } else {
                        val target = pool[layoutId]
                        if (target == null) {
                            issues += Issue(
                                Severity.ERROR, "frame.layout.unresolved",
                                "Frame of '${layout.id}': layoutId '$layoutId' is not in the pool.",
                                editorId = frameEditorId, path = framePath,
                            )
                        } else {
                            referenced += layoutId
                            walkLayout(
                                target,
                                bounds.copy(
                                    width = fw, maxRows = fh,
                                    offsetX = bounds.offsetX + fx, offsetY = bounds.offsetY + fy,
                                ),
                                pool, totalSize, physicalRows, stack, visited, referenced, issues, entry, occupancy,
                            )
                        }
                    }
                }
            }
            "composite" -> {
                val children = layout.value.get("children")?.takeIf { it.isJsonArray }?.asJsonArray
                children?.forEachIndexed { i, childEl ->
                    val childId = childEl.takeIf { it.isJsonPrimitive }?.asString ?: return@forEachIndexed
                    val child = pool[childId]
                    if (child == null) {
                        issues += Issue(
                            Severity.ERROR, "composite.child.unresolved",
                            "Composite '${layout.id}': child '$childId' is not in the pool.",
                            editorId = layout.editorId, path = "${layout.path}.children[$i]",
                        )
                    } else {
                        referenced += childId
                        walkLayout(child, bounds, pool, totalSize, physicalRows, stack, visited, referenced, issues, entry, occupancy)
                    }
                }
            }
            "storage" -> {
                val slots = layout.value.get("slots")?.takeIf { it.isJsonArray }?.asJsonArray
                slots?.forEachIndexed { i, slotEl ->
                    val slot = slotEl.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEachIndexed
                    val sx = slot.get("x")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
                    val sy = slot.get("y")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
                    // Storage slots are physical: slotIndex = y*9 + x.
                    if (sx !in 0..8 || sy < 0 || sy * 9 + sx >= totalSize) issues += Issue(
                        Severity.ERROR, "storage.slot.outOfBounds",
                        "Storage '${layout.id}': slot ($sx,$sy) is outside the physical inventory ($totalSize slots).",
                        editorId = slot.get("editorId")?.takeIf { it.isJsonPrimitive }?.asString,
                        path = "${layout.path}.slots[$i]",
                    )
                }
            }
            "book", "merchant" -> {
                // Dedicated top-level layouts — no grid content to validate here.
            }
        }

        stack.removeAt(stack.lastIndex)
    }

    // ─── Item validation ────────────────────────────────────────────────────

    /**
     * Validates a frame whose `layoutId` is `@view`.
     *
     * Mirrors `MenuViewSupport.resolveFrameLayoutId`: for each declared view, the frame is
     * filled by `view.frames[frameId]`, else by the `"${view.id}_$frameId"` convention, both
     * walked up `parentId`. Each resolved layout is walked with the frame's bounds so a per-view
     * overflow is still caught.
     *
     * A frame that NO view fills is NOT reported. The architecture makes that legitimate, and
     * common: views come down from the shared shell — they exist to draw the domain strip and to
     * navigate — while the layouts filling them live in the child pages. A main menu that is only
     * a domain strip leaves its content frame empty on purpose; a template chassis fills nothing
     * because its children do. Counting "how many of the 22 views fill this frame" measured the
     * shape of the architecture, not a defect.
     *
     * What IS a mistake, and the only case reported here: a view that explicitly names a layout
     * for this frame when no such layout exists. There the intent is written down and it fails —
     * that is a typo, not a choice.
     */
    private fun validateViewFrame(
        frameId: String,
        frameEditorId: String?,
        framePath: String,
        ownerLayoutId: String,
        bounds: Bounds,
        pool: Map<String, PoolLayout>,
        totalSize: Int,
        physicalRows: Int,
        stack: MutableList<String>,
        visited: MutableSet<String>,
        referenced: MutableSet<String>,
        issues: MutableList<Issue>,
        entry: JsonObject,
        occupancy: MutableMap<String, Occupant>,
    ) {
        val views = entry.get("views")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
            .orEmpty()
            .filter { it.get("id")?.takeIf { id -> id.isJsonPrimitive }?.asString?.isNotBlank() == true }

        if (views.isEmpty()) {
            issues += Issue(
                Severity.ERROR, "frame.view.noViews",
                "Frame '$frameId' of '$ownerLayoutId' points at '@view' but the menu declares no view — it stays empty.",
                editorId = frameEditorId, path = framePath,
            )
            return
        }

        if (frameId.isBlank()) {
            issues += Issue(
                Severity.ERROR, "frame.view.noFrameId",
                "'@view' frame without an id in '$ownerLayoutId': the '{view}_{frame}' convention cannot resolve it.",
                editorId = frameEditorId, path = framePath,
            )
            return
        }

        val byId = views.associateBy { it.get("id").asString }

        for (view in views) {
            val viewId = view.get("id").asString

            // Written intent: this view NAMES a layout for this frame. If the pool has no such
            // layout, the frame renders empty without a word — that is the mistake worth finding.
            val declared = view.get("frames")?.takeIf { it.isJsonObject }?.asJsonObject
                ?.get(frameId)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
            if (declared != null && !pool.containsKey(declared)) {
                issues += Issue(
                    Severity.WARNING, "view.frame.layoutMissing",
                    "View '$viewId': frame '$frameId' names layout '$declared', which is not in the " +
                        "pool (templates included) — the frame will stay empty.",
                    editorId = frameEditorId, path = framePath,
                )
            }

            val resolvedId = resolveViewFrameLayoutId(viewId, frameId, pool, byId) ?: continue
            referenced += resolvedId
            // Views fill the same frame in turn: they never coexist on screen, so each one gets
            // its own coordinate space.
            walkLayout(
                pool.getValue(resolvedId), bounds.copy(space = "${bounds.space}/view:$viewId"),
                pool, totalSize, physicalRows, stack, visited, referenced, issues, entry, occupancy,
            )
        }

    }

    /** Pool id filling [frameId] for [viewId], walking up `parentId`. Cycle-guarded. */
    private fun resolveViewFrameLayoutId(
        viewId: String,
        frameId: String,
        pool: Map<String, PoolLayout>,
        views: Map<String, JsonObject>,
    ): String? {
        var current: JsonObject? = views[viewId]
        val seen = mutableSetOf<String>()
        while (current != null) {
            val id = current.get("id").asString
            if (!seen.add(id)) return null
            current.get("frames")?.takeIf { it.isJsonObject }?.asJsonObject
                ?.get(frameId)?.takeIf { it.isJsonPrimitive }?.asString
                ?.takeIf { pool.containsKey(it) }
                ?.let { return it }
            "${id}_$frameId".takeIf { pool.containsKey(it) }?.let { return it }
            current = current.get("parentId")?.takeIf { it.isJsonPrimitive }?.asString
                ?.takeIf { it.isNotBlank() }
                ?.let { views[it] }
        }
        return null
    }

    /**
     * Who holds a cell, and whether it got there by repeating.
     *
     * A repeated slot covers a whole area on purpose; a single-cell slot claims exactly one. The
     * distinction is what separates a filled background from two buttons fighting over a cell.
     */
    private data class Occupant(
        val layoutId: String,
        val path: String,
        val repeats: Boolean,
        /** Carries a tag or a behaviour — it wins over an inert slot at render and click time. */
        val bearing: Boolean,
    )

    /** Views declared by an entry, keyed by id; those without an id are ignored. */
    private fun declaredViews(entry: JsonObject): Map<String, com.google.gson.JsonElement> =
        entry.get("views")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { element ->
                val view = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val id = view.get("id")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                id to (element as com.google.gson.JsonElement)
            }
            ?.toMap()
            .orEmpty()

    private fun validateItems(
        layout: PoolLayout,
        bounds: Bounds,
        issues: MutableList<Issue>,
        entry: JsonObject,
        occupancy: MutableMap<String, Occupant>,
    ) {
        val items = layout.value.get("items")?.takeIf { it.isJsonArray }?.asJsonArray ?: return
        val width = bounds.width
        val maxRows = bounds.maxRows

        items.forEachIndexed { index, itemEl ->
            val item = itemEl.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEachIndexed
            val itemPath = "${layout.path}.items[$index]"
            val editorId = item.get("editorId")?.takeIf { it.isJsonPrimitive }?.asString
            val positions = expandPositions(item)
            // `count` repeats the slot across the grid, it is not a stack size. An item authored
            // with count = 64 asks for 64 separate slots, and most of them land outside the
            // layout — so the hint travels with the error that reveals it.
            val repetitionHint = if (positions.size > 1) {
                " This slot repeats ${positions.size} times (count/repeatY are repetition counts, " +
                    "not stack sizes — set the item's amount for that)."
            } else ""

            // A frame is a WINDOW, not a bound.
            //
            // `FrameLayout` offsets its children without cropping them, and extensions rely on
            // that: a quest codex declares a marker grid far larger than the frame and then
            // scrolls or pages through it, and list menus do the same. Reporting "outside the
            // frame" therefore condemned the normal pattern of every paginated content. And when
            // an overflow IS harmful — the slot lands on another frame's cells — the occupancy
            // check says so, naming both culprits. Only what leaves the INVENTORY is gone for good.
            for ((px, py) in positions) {
                if (!bounds.authoredPositions) break
                if (bounds.space != ROOT_SPACE) break
                val absoluteX = bounds.offsetX + px
                val absoluteY = bounds.offsetY + py
                if (absoluteX in 0 until 9 && absoluteY in 0 until bounds.inventoryRows) continue

                issues += Issue(
                    Severity.ERROR, "slot.outOfBounds",
                    "Layout '${layout.id}': slot at ($px,$py) — ($absoluteX,$absoluteY) once offset — " +
                        "is outside the 9x${bounds.inventoryRows} inventory and will never " +
                        "render.$repetitionHint",
                    editorId = editorId, path = itemPath,
                )
            }

            // Repetition settings without a 'direction': ignored at render time, silently so far.
            val direction = itemDirection(item)
            if (btcrenaud.gui.api.SlotRepetition.hasOrphanRepetition(
                    direction,
                    itemInt(item, "count", 1),
                    itemInt(item, "gap", 1),
                    itemInt(item, "repeatY", 1),
                )
            ) {
                issues += Issue(
                    Severity.WARNING, "slot.repetition.noDirection",
                    "Layout '${layout.id}': 'count'/'gap'/'repeatY' are set without a 'direction', " +
                        "so they are ignored and the slot stays single. Pick a direction " +
                        "(${btcrenaud.gui.api.SlotRepetition.DIRECTIONS.joinToString("/")}) or reset those fields to 1.",
                    editorId = editorId, path = itemPath,
                )
            } else if (direction != null && direction !in btcrenaud.gui.api.SlotRepetition.DIRECTIONS) {
                issues += Issue(
                    Severity.ERROR, "slot.repetition.badDirection",
                    "Layout '${layout.id}': unknown direction '$direction' " +
                        "(expected: ${btcrenaud.gui.api.SlotRepetition.DIRECTIONS.joinToString(", ")}).",
                    editorId = editorId, path = itemPath,
                )
            }

            val conditional = isConditional(item)
            if (!conditional && bounds.authoredPositions) {
                val here = Occupant(
                    layoutId = layout.id,
                    path = itemPath,
                    repeats = positions.size > 1,
                    bearing = isBearing(item),
                )
                for ((px, py) in positions) {
                    // Menu-wide key: the offsets bring a frame's local coordinates back onto the
                    // real grid, and the space keeps independently-drawn areas apart.
                    val absX = bounds.offsetX + px
                    val absY = bounds.offsetY + py
                    val key = "${bounds.space}:$absX,$absY"
                    val other = occupancy.putIfAbsent(key, here) ?: continue
                    // Covering a repeated slot is how a filled background is authored: the fill is
                    // declared once over the whole grid and the buttons sit on top of it. Two slots
                    // from two different pools are the same pattern one layer up. Only two
                    // single-cell slots of the same nature inside one layout are a real mistake.
                    // Layering is DELIBERATE and is not reported.
                    //
                    // A repeated slot is a background and buttons belong on top of it; two slots
                    // from two different layouts are a chassis under a content pane; an inert slot
                    // under a bearing one is a decorated button. Those are how menus are built, and
                    // [SlotOverlay] settles them deterministically at render and at click. Warning
                    // about them told an extension's users that something was wrong when nothing
                    // was, which is the fastest way to make a console unreadable.
                    //
                    // Only two single-cell slots of the same nature inside ONE layout are a real
                    // mistake: neither is a background, neither wins on merit, and one of them is
                    // simply invisible.
                    val deliberate = other.repeats || here.repeats ||
                        other.layoutId != layout.id ||
                        other.bearing != here.bearing
                    if (deliberate) continue
                    issues += Issue(
                        Severity.ERROR, "slot.collision",
                        // Absolute coordinates: two occupants of one cell often live in different
                        // frames, where (0,0) means different places.
                        "Layout '${layout.id}': two unconditional slots occupy " +
                            "($absX,$absY) (${other.path} and $itemPath). " +
                            "Add criteria if the overlap is intended.",
                        editorId = editorId, path = itemPath,
                    )
                }
            }

            validateButtonType(item, editorId, itemPath, issues, entry)
        }
    }

    private fun itemInt(item: JsonObject, key: String, fallback: Int): Int =
        item.get(key)?.takeIf { it.isJsonPrimitive }?.asInt ?: fallback

    private fun itemDirection(item: JsonObject): String? =
        item.get("direction")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

    /**
     * The same formula as the runtime — literally: both call
     * [btcrenaud.gui.api.SlotRepetition.expand]. The editor can no longer predict a placement
     * the server does not draw.
     */
    private fun expandPositions(item: JsonObject): List<Pair<Int, Int>> =
        btcrenaud.gui.api.SlotRepetition.expand(
            x = itemInt(item, "x", 0),
            y = itemInt(item, "y", 0),
            direction = itemDirection(item),
            count = itemInt(item, "count", 1),
            gap = itemInt(item, "gap", 1),
            repeatY = itemInt(item, "repeatY", 1),
        )

    /**
     * An item is BEARING when it carries an identity or a behaviour a decorative item could not
     * replace. JSON mirror of [btcrenaud.gui.api.SlotOverlay.isBearing].
     */
    private fun isBearing(item: JsonObject): Boolean {
        fun nonEmptyArray(key: String) =
            item.get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.size()?.let { it > 0 } == true
        val buttonType = item.get("buttonType")?.takeIf { it.isJsonPrimitive }?.asString
        return !buttonType.isNullOrBlank() ||
            nonEmptyArray("triggers") ||
            nonEmptyArray("modifiers") ||
            nonEmptyArray("interactions") ||
            nonEmptyArray("interactionList") ||
            item.get("input")?.isJsonObject == true ||
            item.get("storageId")?.takeIf { it.isJsonPrimitive }?.asString?.isNotBlank() == true ||
            item.get("allowPickup")?.takeIf { it.isJsonPrimitive }?.asBoolean == true ||
            item.get("isGhost")?.takeIf { it.isJsonPrimitive }?.asBoolean == true
    }

    private fun isConditional(item: JsonObject): Boolean {
        val criteria = item.get("criteria")?.takeIf { it.isJsonArray }?.asJsonArray
        if (criteria != null && criteria.size() > 0) return true
        val viewPermission = item.get("viewPermission")?.takeIf { it.isJsonPrimitive }?.asString
        return !viewPermission.isNullOrBlank()
    }

    /** Validates buttonType against the pluggable registry validator. */
    private fun validateButtonType(
        item: JsonObject,
        editorId: String?,
        path: String,
        issues: MutableList<Issue>,
        entry: JsonObject,
    ) {
        val buttonType = item.get("buttonType")?.takeIf { it.isJsonPrimitive }?.asString
            ?.takeIf { it.isNotBlank() } ?: return
        val buttonPrefix = item.get("buttonPrefix")?.takeIf { it.isJsonPrimitive }?.asString
        val validator = buttonTypeValidator ?: return
        val known = validator(buttonType, buttonPrefix, entry) ?: return
        if (!known) {
            issues += Issue(
                if (strictButtonTypes) Severity.ERROR else Severity.WARNING,
                "slot.buttonType.unknown",
                "buttonType '$buttonType' is unknown to every extension registered for this menu.",
                editorId = editorId, path = path,
            )
        }
    }

    /** For layouts whose items are paged (positions computed at runtime) — only check buttonTypes. */
    private fun validateButtonTypesOnly(
        layout: PoolLayout,
        itemsField: String,
        issues: MutableList<Issue>,
        entry: JsonObject,
    ) {
        val items = layout.value.get(itemsField)?.takeIf { it.isJsonArray }?.asJsonArray ?: return
        items.forEachIndexed { index, itemEl ->
            val item = itemEl.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEachIndexed
            validateButtonType(
                item,
                item.get("editorId")?.takeIf { it.isJsonPrimitive }?.asString,
                "${layout.path}.$itemsField[$index]",
                issues, entry,
            )
        }
    }
}
