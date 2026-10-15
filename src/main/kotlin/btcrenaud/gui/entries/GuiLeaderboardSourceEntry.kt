package btcrenaud.gui.entries

import com.typewritermc.core.extension.annotations.Tags
import com.typewritermc.core.interaction.InteractionContext
import com.typewritermc.engine.paper.entry.ManifestEntry
import org.bukkit.entity.Player
import java.util.UUID

/**
 * Rows of a `gui_leaderboard` owned by another extension, which knows every subject, online or not.
 * Implement it as an entry and reference it from the `source` field of a `gui_leaderboard`.
 *
 * Called on the thread that renders the menu (Paper: main thread; Folia: the viewer's region thread),
 * at every refresh: answer from memory, never block, never read another player's live state.
 * An exception is logged once and the leaderboard shows no rows; it never breaks the menu.
 */
@Tags("gui_leaderboard_source")
interface GuiLeaderboardSourceEntry : ManifestEntry {
    fun leaderboardRows(viewer: Player, context: InteractionContext): List<LeaderboardSourceRow>
}

/**
 * One ranked subject. Every text is PLAIN text: OmniGUI escapes `name`, `scoreText` and `values`
 * before they reach MiniMessage, so do not escape them yourself (that would show backslashes).
 *
 * @property key stable identity inside the source; breaks ties between equal scores and names.
 * @property name display name.
 * @property playerId the player the row speaks about (item Vars resolve against them while they are
 *   online); null when the row is not a player.
 * @property score ranking value, compared as a number.
 * @property scoreText what `{score}` shows (format, unit); `{score_raw}` always shows [score].
 * @property values extra texts, available as `{value_<key>}` in rowName and rowLore.
 */
data class LeaderboardSourceRow(
    val key: String,
    val name: String,
    val playerId: UUID?,
    val score: Long,
    val scoreText: String,
    val values: Map<String, String>,
)
