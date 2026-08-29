package btcrenaud.gui.entries

import btcrenaud.gui.api.LeaderboardOrder
import btcrenaud.gui.api.LeaderboardScoreMode
import btcrenaud.gui.api.LeaderboardScope
import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.entries.emptyRef
import com.typewritermc.core.extension.annotations.Colored
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.extension.annotations.MultiLine
import com.typewritermc.core.extension.annotations.Tags
import com.typewritermc.engine.paper.entry.ManifestEntry
import com.typewritermc.engine.paper.entry.entries.ConstVar
import com.typewritermc.engine.paper.entry.entries.GroupEntry
import com.typewritermc.engine.paper.entry.entries.ReadableFactEntry
import com.typewritermc.engine.paper.entry.entries.Var
import com.typewritermc.engine.paper.utils.item.Item
import com.typewritermc.core.utils.point.Position

/** Reusable definition of a fact-backed leaderboard rendered by an OpenGUI layout. */
@Entry(
    "gui_leaderboard",
    "GUI leaderboard backed by Typewriter facts",
    Colors.BLUE,
    "mdi:podium"
)
@Tags("gui", "gui_leaderboard", "leaderboard")
class GuiLeaderboardEntry(
    override val id: String = "",
    override val name: String = "",
    @Help("Facts whose values are aggregated into the row score.")
    val facts: List<Ref<ReadableFactEntry>> = emptyList(),
    @Help("Rows are individual players, worlds, or Typewriter groups.")
    val scope: LeaderboardScope = LeaderboardScope.PLAYER,
    @Help("Group entry used when scope is GROUP. Leave empty for a player/world leaderboard.")
    val group: Ref<GroupEntry> = emptyRef(),
    @Help("Optional artifact used to retain the last known values of players who are offline.")
    val population: Ref<GuiLeaderboardPopulationEntry> = emptyRef(),
    @Help("Optional world selectors resolved as native Typewriter positions. Empty means every world.")
    val worlds: List<Var<Position>> = emptyList(),
    @Help("How multiple selected facts are combined for one row.")
    val scoreMode: LeaderboardScoreMode = LeaderboardScoreMode.SUM,
    @Help("Whether scores are sorted from highest to lowest or the reverse.")
    val order: LeaderboardOrder = LeaderboardOrder.DESCENDING,
    @Help("Maximum number of ranked rows considered before pagination.")
    val limit: Int = 100,
    @Help("If false, rows with a zero score are hidden.")
    val includeZero: Boolean = false,
    @Help("Item displayed for every ranked row. Empty uses PAPER.")
    val rowItem: Var<Item> = ConstVar(Item.Empty),
    @Help("Row name. Tokens: {rank}, {name}, {score}, {group}, {world}.")
    @Colored
    val rowName: Var<String> = ConstVar("<white>{rank}. {name}"),
    @Help("Row lore. Supports the same tokens as rowName and {score_<fact_id>} for individual facts.")
    @Colored @MultiLine
    val rowLore: List<Var<String>> = listOf(ConstVar("<gray>Score: <white>{score}"))
) : ManifestEntry
