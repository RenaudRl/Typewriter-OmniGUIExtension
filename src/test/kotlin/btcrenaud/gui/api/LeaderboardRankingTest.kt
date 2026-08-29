package btcrenaud.gui.api

import kotlin.test.Test
import kotlin.test.assertEquals
import java.util.UUID

class LeaderboardRankingTest {
    @Test
    fun `descending ranking is deterministic and limited`() {
        val rows = listOf(
            LeaderboardRow(LeaderboardRowKey.Player(UUID.fromString("00000000-0000-0000-0000-00000000000b")), "Beta", 10),
            LeaderboardRow(LeaderboardRowKey.Player(UUID.fromString("00000000-0000-0000-0000-00000000000a")), "Alpha", 10),
            LeaderboardRow(LeaderboardRowKey.Player(UUID.fromString("00000000-0000-0000-0000-00000000000c")), "Zero", 0),
        )

        assertEquals(
            listOf("Alpha", "Beta"),
            LeaderboardRanking.order(rows, LeaderboardOrder.DESCENDING, includeZero = false, limit = 2)
                .map { it.name },
        )
    }

    @Test
    fun `ascending ranking can include zero scores`() {
        val rows = listOf(
            LeaderboardRow(LeaderboardRowKey.Player(UUID.fromString("00000000-0000-0000-0000-00000000000b")), "Beta", 4),
            LeaderboardRow(LeaderboardRowKey.Player(UUID.fromString("00000000-0000-0000-0000-00000000000c")), "Zero", 0),
            LeaderboardRow(LeaderboardRowKey.Player(UUID.fromString("00000000-0000-0000-0000-00000000000d")), "Alpha", -2),
        )

        assertEquals(
            listOf("Alpha", "Zero", "Beta"),
            LeaderboardRanking.order(rows, LeaderboardOrder.ASCENDING, includeZero = true, limit = 10)
                .map { it.name },
        )
    }
}
