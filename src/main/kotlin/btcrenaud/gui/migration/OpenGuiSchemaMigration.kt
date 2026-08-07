package btcrenaud.gui.migration

import btcrenaud.gui.*
import com.google.gson.*
import com.typewritermc.core.entries.Ref
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.logging.Logger

/**
 * Canonical OmniGUI schema normalizer.
 *
 * Version 2 removes repeated complex editor shapes from every GUI item and paginated layout:
 * inline `storage` becomes `storagePool` + `storageId`, while the three pagination button
 * fields become one role-based `navigationButtons` list.
 */
object OpenGuiSchemaNormalizer {
    const val SCHEMA_VERSION = 2
    private const val SCHEMA_FIELD = "_omniGuiSchema"

    data class Result(val json: JsonObject, val changed: Boolean)

    fun normalizeEntry(source: JsonObject): Result {
        val root = source.deepCopy()
        var changed = false

        val storagePool = root.getAsJsonArray("storagePool") ?: JsonArray()
        val usedIds = storagePool.mapNotNull {
            it.takeIf(JsonElement::isJsonObject)?.asJsonObject?.get("id")
                ?.takeIf(JsonElement::isJsonPrimitive)?.asString
        }.toMutableSet()

        fun uniqueStorageId(path: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(path.toByteArray(StandardCharsets.UTF_8))
                .take(8)
                .joinToString("") { "%02x".format(it) }
            val base = "legacy_storage_$digest"
            var candidate = base
            var suffix = 2
            while (!usedIds.add(candidate)) candidate = "${base}_${suffix++}"
            return candidate
        }

        fun visit(element: JsonElement, path: String) {
            when {
                element.isJsonArray -> element.asJsonArray.forEachIndexed { index, child ->
                    visit(child, "$path/$index")
                }
                element.isJsonObject -> {
                    val obj = element.asJsonObject
                    val inlineStorage = obj.get("storage")
                        ?.takeIf { it.isJsonObject && obj.has("item") }
                        ?.asJsonObject
                    if (inlineStorage != null) {
                        val storage = inlineStorage.deepCopy()
                        val id = storage.get("id")
                            ?.takeIf { it.isJsonPrimitive && it.asString.isNotBlank() }
                            ?.asString
                            ?.takeIf(usedIds::add)
                            ?: uniqueStorageId(path)
                        storage.addProperty("id", id)
                        storagePool.add(storage)
                        obj.remove("storage")
                        obj.addProperty("storageId", id)
                        changed = true
                    }

                    val legacyButtons = listOf(
                        "nextPage" to PaginationButtonRole.NEXT,
                        "previousPage" to PaginationButtonRole.PREVIOUS,
                        "backButton" to PaginationButtonRole.BACK,
                    )
                    if (legacyButtons.any { obj.has(it.first) }) {
                        val buttons = obj.getAsJsonArray("navigationButtons") ?: JsonArray()
                        val presentRoles = buttons.mapNotNull {
                            it.takeIf(JsonElement::isJsonObject)?.asJsonObject?.get("role")
                                ?.takeIf(JsonElement::isJsonPrimitive)?.asString
                        }.toMutableSet()
                        legacyButtons.forEach { (field, role) ->
                            val legacy = obj.remove(field)
                            if (legacy != null && !legacy.isJsonNull && legacy.isJsonObject &&
                                presentRoles.add(role.name)
                            ) {
                                val button = JsonObject()
                                button.addProperty("role", role.name)
                                button.add(
                                    "item",
                                    legacy.asJsonObject.get("item")?.deepCopy() ?: JsonObject(),
                                )
                                buttons.add(button)
                            }
                            if (legacy != null) changed = true
                        }
                        obj.add("navigationButtons", buttons)
                    }

                    obj.entrySet().toList().forEach { (key, child) ->
                        if (key != "storagePool") visit(child, "$path/$key")
                    }
                }
            }
        }

        root.get("layoutPool")?.let { visit(it, "/layoutPool") }
        if (storagePool.size() > 0 || root.has("storagePool")) root.add("storagePool", storagePool)
        val currentVersion = root.get(SCHEMA_FIELD)
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.asInt
        if (currentVersion != SCHEMA_VERSION) {
            root.addProperty(SCHEMA_FIELD, SCHEMA_VERSION)
            changed = true
        }
        return Result(root, changed)
    }

    fun isOpenGui(entry: JsonObject): Boolean =
        entry.get("blueprintId")?.asString == "open_gui" ||
            entry.get("type")?.asString == "open_gui"
}

/**
 * Migrates published and staging page files with one pre-migration backup and atomic
 * replacement.
 *
 * The conversion is one-shot: [migrateOnce] records a marker next to the pages once it has run,
 * and every later start returns on a single file check instead of parsing every page. The
 * per-entry `_omniGuiSchema` field still makes the conversion itself idempotent, so a lost or
 * hand-deleted marker costs one extra scan, never a double conversion.
 */
object OpenGuiPageMigrator {
    private val timestampFormat = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /** Written after the first conversion so the scan leaves the startup path for good. */
    private const val MARKER_FILE = ".omnigui-schema-v2"

    /**
     * Runs the conversion at most once per installation.
     *
     * @return the number of page files rewritten, always 0 once the marker exists.
     */
    fun migrateOnce(baseDirectory: File, logger: Logger): Int {
        val marker = baseDirectory.resolve(MARKER_FILE)
        if (marker.isFile) return 0

        val migrated = migrate(baseDirectory, logger)

        // A marker that cannot be written only costs one more scan next start — the conversion
        // is idempotent — so it is reported and swallowed rather than failing the extension.
        runCatching {
            baseDirectory.mkdirs()
            marker.writeText(
                "OmniGUI schema v${OpenGuiSchemaNormalizer.SCHEMA_VERSION} applied on " +
                    "${LocalDateTime.now()}. Delete this file to force a re-scan.",
                StandardCharsets.UTF_8,
            )
        }.onFailure {
            logger.warning(
                "[OmniGUI] Could not write ${marker.path}: ${it.message}. " +
                    "Pages will be scanned again on the next start.",
            )
        }
        return migrated
    }

    private fun migrate(baseDirectory: File, logger: Logger): Int {
        val candidates = listOf("pages", "staging").flatMap { directoryName ->
            val directory = baseDirectory.resolve(directoryName)
            directory.listFiles()
                ?.filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
                ?.map { directoryName to it }
                .orEmpty()
        }

        val changes = candidates.mapNotNull { (directoryName, file) ->
            val page = runCatching { JsonParser.parseString(file.readText()).asJsonObject }
                .getOrElse {
                    logger.warning("[OmniGUI] Skipping invalid page ${file.path}: ${it.message}")
                    return@mapNotNull null
                }
            var changed = false
            val entries = page.getAsJsonArray("entries") ?: return@mapNotNull null
            entries.forEachIndexed { index, element ->
                if (!element.isJsonObject || !OpenGuiSchemaNormalizer.isOpenGui(element.asJsonObject)) {
                    return@forEachIndexed
                }
                val result = OpenGuiSchemaNormalizer.normalizeEntry(element.asJsonObject)
                if (result.changed) {
                    entries[index] = result.json
                    changed = true
                }
            }
            if (changed) Triple(directoryName, file, page) else null
        }
        if (changes.isEmpty()) return 0

        val backupRoot = baseDirectory.resolve(
            "backup/omnigui-schema-v2/${timestampFormat.format(LocalDateTime.now())}",
        )
        changes.forEach { (directoryName, file, _) ->
            val target = backupRoot.resolve(directoryName).resolve(file.name)
            target.parentFile.mkdirs()
            Files.copy(file.toPath(), target.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
        }

        changes.forEach { (_, file, page) ->
            val temporary = file.toPath().resolveSibling("${file.name}.omnigui-v2.tmp")
            Files.writeString(temporary, page.toString(), StandardCharsets.UTF_8)
            try {
                Files.move(
                    temporary,
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
        logger.info(
            "[OmniGUI] Migrated ${changes.size} page file(s) to schema v2. " +
                "Backup: ${backupRoot.path}",
        )
        return changes.size
    }
}
