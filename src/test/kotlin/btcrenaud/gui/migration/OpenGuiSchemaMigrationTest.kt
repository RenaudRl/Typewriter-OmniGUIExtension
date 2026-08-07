package btcrenaud.gui.migration

import com.google.gson.JsonParser
import java.nio.file.Files
import java.util.logging.Logger
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenGuiSchemaMigrationTest {
    @Test
    fun `normalizer extracts inline storage and merges pagination buttons`() {
        val legacy = JsonParser.parseString(
            """
            {
              "id":"menu",
              "blueprintId":"open_gui",
              "layoutPool":[
                {
                  "case":"paginated",
                  "value":{
                    "id":"main",
                    "items":[{"item":{},"x":1,"storage":{"entry":"artifact","maxAmount":32}}],
                    "nextPage":{"item":{"item":{},"x":8},"direction":"RIGHT","step":7},
                    "previousPage":{"item":{"item":{},"x":0},"direction":"LEFT","step":4},
                    "backButton":{"item":{"item":{},"x":4},"direction":"UP","step":1}
                  }
                }
              ]
            }
            """.trimIndent(),
        ).asJsonObject

        val first = OpenGuiSchemaNormalizer.normalizeEntry(legacy)
        assertTrue(first.changed)
        val item = first.json.getAsJsonArray("layoutPool")[0].asJsonObject
            .getAsJsonObject("value").getAsJsonArray("items")[0].asJsonObject
        assertFalse(item.has("storage"))
        val storageId = item.get("storageId").asString
        val storage = first.json.getAsJsonArray("storagePool")[0].asJsonObject
        assertEquals(storageId, storage.get("id").asString)
        assertEquals("artifact", storage.get("entry").asString)

        val paginated = first.json.getAsJsonArray("layoutPool")[0].asJsonObject
            .getAsJsonObject("value")
        assertFalse(paginated.has("nextPage"))
        assertFalse(paginated.has("previousPage"))
        assertFalse(paginated.has("backButton"))
        assertEquals(
            listOf("NEXT", "PREVIOUS", "BACK"),
            paginated.getAsJsonArray("navigationButtons").map {
                it.asJsonObject.get("role").asString
            },
        )

        val second = OpenGuiSchemaNormalizer.normalizeEntry(first.json)
        assertFalse(second.changed)
        assertEquals(first.json, second.json)
    }

    @Test
    fun `file migrator backs up pages and staging and is idempotent`() {
        val root = Files.createTempDirectory("omnigui-migration")
        val pages = root.resolve("pages").createDirectories()
        val staging = root.resolve("staging").createDirectories()
        val legacyPage =
            """{"name":"Menus","entries":[{"id":"menu","blueprintId":"open_gui","layoutPool":[{"case":"simple","value":{"items":[{"item":{},"storage":{"entry":"artifact"}}]}}]}]}"""
        val publishedFile = pages.resolve("published.json")
        val stagingFile = staging.resolve("draft.json")
        publishedFile.writeText(legacyPage)
        stagingFile.writeText(legacyPage)

        assertEquals(2, OpenGuiPageMigrator.migrateOnce(root.toFile(), Logger.getAnonymousLogger()))
        // The marker makes the second start a no-op without reading a single page.
        assertEquals(0, OpenGuiPageMigrator.migrateOnce(root.toFile(), Logger.getAnonymousLogger()))
        val marker = root.resolve(".omnigui-schema-v2")
        assertTrue(Files.isRegularFile(marker))
        // Losing the marker must cost one extra scan, never a second conversion.
        Files.delete(marker)
        assertEquals(0, OpenGuiPageMigrator.migrateOnce(root.toFile(), Logger.getAnonymousLogger()))

        listOf(publishedFile, stagingFile).forEach { file ->
            val entry = JsonParser.parseString(Files.readString(file)).asJsonObject
                .getAsJsonArray("entries")[0].asJsonObject
            assertEquals(2, entry.get("_omniGuiSchema").asInt)
            assertNotNull(entry.getAsJsonArray("storagePool"))
        }
        val backups = root.resolve("backup/omnigui-schema-v2")
        assertTrue(
            Files.walk(backups).use { files ->
                files.filter { Files.isRegularFile(it) }.count() == 2L
            },
        )
    }
}
