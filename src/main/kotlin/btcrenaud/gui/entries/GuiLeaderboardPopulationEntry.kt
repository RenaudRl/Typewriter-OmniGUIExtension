package btcrenaud.gui.entries

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Tags
import com.typewritermc.engine.paper.entry.entries.ArtifactEntry
import java.util.UUID

/**
 * Artifact containing the extension-owned last-known population of leaderboards.
 *
 * The engine's public API deliberately does not expose an offline fact snapshot. Keeping this
 * artifact in the extension makes offline rows available without changing the official engine.
 */
@Entry(
    "gui_leaderboard_population",
    "Offline population data for GUI leaderboards",
    Colors.BLUE,
    "mdi:database"
)
@Tags("gui", "gui_leaderboard", "leaderboard_population", "artifact")
class GuiLeaderboardPopulationEntry(
    override val id: String = "",
    override val name: String = "",
    override val artifactId: String = UUID.randomUUID().toString(),
) : ArtifactEntry
