package btcrenaud.gui.api

import btcrenaud.gui.entries.LeaderboardSourceRow
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LeaderboardSourceTest {
    private val alice = UUID.fromString("00000000-0000-0000-0000-00000000000a")

    private fun row(
        key: String,
        name: String = key,
        score: Long = 0,
        scoreText: String = score.toString(),
        values: Map<String, String> = emptyMap(),
        playerId: UUID? = null,
    ) = LeaderboardSourceRow(key, name, playerId, score, scoreText, values)

    private fun rank(rows: List<LeaderboardSourceRow>, order: LeaderboardOrder, includeZero: Boolean = false, limit: Int = 100) =
        LeaderboardRanking.order(LeaderboardService.rowsFromSource("test") { rows }, order, includeZero, limit)

    @Test
    fun `source rows are ranked by score, then name, then key, with scores beyond Int`() {
        val big = Int.MAX_VALUE.toLong() + 5
        val ranked = rank(
            listOf(row("b", "Beta", 10), row("a", "Alpha", 10), row("c", "Gamma", big), row("d", "Delta", 3)),
            LeaderboardOrder.DESCENDING,
        )

        assertEquals(listOf("Gamma", "Alpha", "Beta", "Delta"), ranked.map { it.name })
    }

    @Test
    fun `ascending order, zero filter and limit apply to source rows`() {
        val rows = listOf(row("a", "A", 0), row("b", "B", 30), row("c", "C", 20), row("d", "D", 10))

        assertEquals(listOf("D", "C"), rank(rows, LeaderboardOrder.ASCENDING, limit = 2).map { it.name })
        assertEquals(listOf("A", "D", "C", "B"), rank(rows, LeaderboardOrder.ASCENDING, includeZero = true).map { it.name })
    }

    @Test
    fun `equal names are told apart by the row key`() {
        val ranked = rank(listOf(row("k2", "Same", 5), row("k1", "Same", 5)), LeaderboardOrder.DESCENDING)

        assertEquals(listOf("source:k1", "source:k2"), ranked.map { it.key.stableKey })
    }

    @Test
    fun `tokens show scoreText, raw score and values`() {
        val tokens = LeaderboardService.rowsFromSource("test") {
            listOf(row("a", "Alice", 83_000, scoreText = "01:23", values = mapOf("runs" to "12", "best" to "01:23")))
        }.single().tokens(4)

        assertEquals("4", tokens["{rank}"])
        assertEquals("Alice", tokens["{name}"])
        assertEquals("01:23", tokens["{score}"])
        assertEquals("83000", tokens["{score_raw}"])
        assertEquals("12", tokens["{value_runs}"])
        assertEquals("01:23", tokens["{value_best}"])
    }

    @Test
    fun `a name, a score text and values cannot inject MiniMessage tags`() {
        val tokens = LeaderboardService.rowsFromSource("test") {
            listOf(row("a", "<rainbow>Eve", 1, scoreText = "<obf>9", values = mapOf("label" to "<font:x>big</font>")))
        }.single().tokens(1)

        val plain = PlainTextComponentSerializer.plainText()
        val mini = MiniMessage.miniMessage()
        assertEquals("<rainbow>Eve", plain.serialize(mini.deserialize(tokens.getValue("{name}"))))
        assertEquals("<obf>9", plain.serialize(mini.deserialize(tokens.getValue("{score}"))))
        assertEquals("<font:x>big</font>", plain.serialize(mini.deserialize(tokens.getValue("{value_label}"))))
        assertEquals("1", tokens["{score_raw}"])
    }

    @Test
    fun `equal scores are ordered by the raw name, not by its escaped form`() {
        // Escaped, "<red>" becomes "\<red>" and sorts after "[z" ('\' follows '['); raw, '<' sorts before '['.
        val ranked = rank(listOf(row("a", "[z", 5), row("b", "<red>", 5)), LeaderboardOrder.DESCENDING)

        assertEquals(listOf("<red>", "[z"), ranked.map { it.name })
    }

    @Test
    fun `escaping happens when a row is shown, so a row cut by the limit is never escaped`() {
        val ranked = rank(listOf(row("a", "<red>a", 3), row("b", "<red>b", 2)), LeaderboardOrder.DESCENDING, limit = 1)

        assertEquals(listOf("<red>a"), ranked.map { it.name }, "the ranking works on the plain text")
        assertEquals("\\<red>a", ranked.single().tokens(1)["{name}"], "the displayed row is escaped")
    }

    @Test
    fun `a source row speaks about its player when it has one`() {
        val rows = LeaderboardService.rowsFromSource("test") { listOf(row("a", playerId = alice), row("b")) }

        assertEquals(alice, rows[0].subjectUuid())
        assertNull(rows[1].subjectUuid())
    }

    @Test
    fun `a failing source shows no rows instead of breaking the menu`() {
        repeat(2) {
            assertEquals(emptyList(), LeaderboardService.rowsFromSource("broken") { error("boom") })
        }
    }

    @Test
    fun `fact rows keep their tokens and gain only the raw score`() {
        val fact = LeaderboardRow(LeaderboardRowKey.Player(alice), "Alice", 7)

        assertEquals("7", fact.scoreText)
        assertEquals(
            mapOf(
                "{rank}" to "2", "{name}" to "Alice", "{score}" to "7", "{score_raw}" to "7",
                "{group}" to "", "{world}" to "",
            ),
            fact.tokens(2),
        )
        assertEquals(alice, fact.subjectUuid())
    }
}
