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
    private data class Bounds(val width: Int, val maxRows: Int?)

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
            baseId = base.get("baseMenuId")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
            chainPath += " → $baseId"
        }
        // Own layouts override inherited ones.
        effectivePool.putAll(poolById)

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

        // ── Reference walk: bounds, references, cycles, collisions ──────────
        val visited = mutableSetOf<String>()
        val referenced = mutableSetOf<String>()
        effectiveMainLayoutId?.let { mainId ->
            effectivePool[mainId]?.let { main ->
                walkLayout(
                    main, Bounds(width = 9, maxRows = physicalRows),
                    effectivePool, totalSize, physicalRows,
                    stack = mutableListOf(), visited = visited, referenced = referenced,
                    issues = issues, entry = entry,
                )
            }
        }

        // Unreferenced own layouts still get standalone checks + a warning.
        for (layout in poolById.values) {
            if (layout.id in referenced || layout.id == effectiveMainLayoutId) continue
            issues += Issue(
                Severity.WARNING, "layout.unreferenced",
                "Layout '${layout.id}' is not reachable from the main layout.",
                editorId = layout.editorId, path = layout.path,
            )
            walkLayout(
                layout, Bounds(width = 9, maxRows = null),
                effectivePool, totalSize, physicalRows,
                stack = mutableListOf(), visited = visited, referenced = referenced,
                issues = issues, entry = entry,
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
        val visitKey = "${layout.id}@${bounds.width}x${bounds.maxRows}"
        if (!visited.add(visitKey)) return
        stack.add(layout.id)

        when (layout.case) {
            "simple", "flex" -> {
                val maxRows = if (layout.case == "flex") {
                    layout.value.get("virtualHeight")?.takeIf { it.isJsonPrimitive }?.asInt ?: bounds.maxRows
                } else bounds.maxRows
                validateItems(layout, bounds.width, maxRows, issues, entry)
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
                        walkLayout(
                            inner,
                            Bounds(width = virtualWidth ?: 9, maxRows = virtualHeight),
                            pool, totalSize, physicalRows, stack, visited, referenced, issues, entry,
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
                            Bounds(width = fw, maxRows = fh),
                            pool, totalSize, physicalRows, stack, visited, referenced, issues, entry,
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
                                target, Bounds(width = fw, maxRows = fh),
                                pool, totalSize, physicalRows, stack, visited, referenced, issues, entry,
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
                        walkLayout(child, bounds, pool, totalSize, physicalRows, stack, visited, referenced, issues, entry)
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
     * walked up `parentId`. A view that resolves nothing renders the frame empty — that is
     * by design at runtime, so it is a WARNING here, never an error. Each resolved layout is
     * walked with the frame's bounds so a per-view overflow is still caught.
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
        val unresolved = mutableListOf<String>()

        for (view in views) {
            val viewId = view.get("id").asString
            val resolvedId = resolveViewFrameLayoutId(viewId, frameId, pool, byId)
            if (resolvedId == null) {
                unresolved += viewId
                continue
            }
            referenced += resolvedId
            walkLayout(
                pool.getValue(resolvedId), bounds,
                pool, totalSize, physicalRows, stack, visited, referenced, issues, entry,
            )
        }

        if (unresolved.size == views.size) {
            issues += Issue(
                Severity.WARNING, "frame.view.unresolvedAll",
                "Frame '$frameId' of '$ownerLayoutId': no view fills it — " +
                    "add a '{view}_$frameId' layout to the pool or map it in the view's 'frames'.",
                editorId = frameEditorId, path = framePath,
            )
        } else if (unresolved.isNotEmpty()) {
            issues += Issue(
                Severity.WARNING, "frame.view.unresolved",
                "Frame '$frameId' of '$ownerLayoutId': empty for ${unresolved.joinToString(", ")}.",
                editorId = frameEditorId, path = framePath,
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
    private data class Occupant(val path: String, val repeats: Boolean)

    private fun validateItems(
        layout: PoolLayout,
        width: Int,
        maxRows: Int?,
        issues: MutableList<Issue>,
        entry: JsonObject,
    ) {
        val items = layout.value.get("items")?.takeIf { it.isJsonArray }?.asJsonArray ?: return
        // (x,y) -> first unconditional occupant, for collision reporting.
        val occupied = HashMap<Pair<Int, Int>, Occupant>()

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

            for ((px, py) in positions) {
                if (px < 0 || py < 0 || px >= width || (maxRows != null && py >= maxRows)) {
                    issues += Issue(
                        Severity.ERROR, "slot.outOfBounds",
                        "Layout '${layout.id}': slot at ($px,$py) is outside the " +
                            "${width}x${maxRows ?: "unbounded"} space.$repetitionHint",
                        editorId = editorId, path = itemPath,
                    )
                }
            }

            val conditional = isConditional(item)
            if (!conditional) {
                val here = Occupant(itemPath, repeats = positions.size > 1)
                for (pos in positions) {
                    val other = occupied.putIfAbsent(pos, here) ?: continue
                    // Covering a repeated slot is how a filled background is authored: the fill is
                    // declared once over the whole grid and the buttons sit on top of it. Only two
                    // slots that each name a single cell are competing for it by mistake, and only
                    // then is one of them invisible for no reason.
                    val fill = other.repeats || here.repeats
                    issues += if (fill) {
                        Issue(
                            Severity.WARNING, "slot.overlapsFill",
                            "Layout '${layout.id}': (${pos.first},${pos.second}) is covered by " +
                                "$itemPath on top of ${other.path}, so only one of them shows.",
                            editorId = editorId, path = itemPath,
                        )
                    } else {
                        Issue(
                            Severity.ERROR, "slot.collision",
                            "Layout '${layout.id}': two unconditional slots occupy " +
                                "(${pos.first},${pos.second}) (${other.path} and $itemPath). " +
                                "Add criteria if the overlap is intended.",
                            editorId = editorId, path = itemPath,
                        )
                    }
                }
            }

            validateButtonType(item, editorId, itemPath, issues, entry)
        }
    }

    /** Mirrors GuiSlotBuilder's repetition expansion exactly. */
    private fun expandPositions(item: JsonObject): List<Pair<Int, Int>> {
        val x = item.get("x")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
        val y = item.get("y")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
        val direction = item.get("direction")?.takeIf { it.isJsonPrimitive }?.asString
        if (direction == null) return listOf(x to y)
        val count = item.get("count")?.takeIf { it.isJsonPrimitive }?.asInt ?: 1
        val gap = item.get("gap")?.takeIf { it.isJsonPrimitive }?.asInt ?: 1
        val repeatY = item.get("repeatY")?.takeIf { it.isJsonPrimitive }?.asInt ?: 1
        val positions = mutableListOf<Pair<Int, Int>>()
        for (ry in 0 until repeatY.coerceAtLeast(1)) {
            for (rc in 0 until count.coerceAtLeast(1)) {
                positions += when (direction) {
                    "right" -> (x + rc * gap) to (y + ry * gap)
                    "left" -> (x - rc * gap) to (y + ry * gap)
                    "down" -> (x + ry * gap) to (y + rc * gap)
                    "up" -> (x + ry * gap) to (y - rc * gap)
                    else -> x to y
                }
            }
        }
        return positions
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
