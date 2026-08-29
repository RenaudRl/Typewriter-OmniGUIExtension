package btcrenaud.gui.api

import btcrenaud.gui.entries.GuiLeaderboardEntry
import btcrenaud.gui.services.LeaderboardPopulationSnapshot
import btcrenaud.gui.services.LeaderboardPopulationStore
import btcrenaud.gui.services.MenuSessionService
import com.typewritermc.core.interaction.InteractionContext
import com.typewritermc.core.utils.point.World
import com.typewritermc.engine.paper.entry.entries.GroupId
import com.typewritermc.engine.paper.entry.entries.ReadableFactEntry
import com.typewritermc.engine.paper.utils.asMini
import com.typewritermc.engine.paper.utils.item.Item
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

enum class LeaderboardScope { PLAYER, WORLD, GROUP }
enum class LeaderboardScoreMode { SUM, MAX }
enum class LeaderboardOrder { ASCENDING, DESCENDING }

/** Typed identity of a leaderboard row; UUIDs stay implementation data and are never a token. */
sealed interface LeaderboardRowKey {
    val stableKey: String

    data class Player(val uuid: UUID) : LeaderboardRowKey {
        override val stableKey: String = "player:$uuid"
    }

    data class World(val world: com.typewritermc.core.utils.point.World) : LeaderboardRowKey {
        override val stableKey: String = "world:${world.identifier}"
    }

    data class Group(val groupId: GroupId) : LeaderboardRowKey {
        override val stableKey: String = "group:${groupId.id}"
    }
}

/** A rendered row using Typewriter's native world and group identifiers. */
data class LeaderboardRow(
    val key: LeaderboardRowKey,
    val name: String,
    val score: Int,
    val groupId: GroupId? = null,
    val world: World? = null,
    val factScores: Map<String, Int> = emptyMap(),
) {
    constructor(key: LeaderboardRowKey, name: String, score: Int) : this(key, name, score, null, null, emptyMap())
}

/** Resolves live values plus extension-owned offline snapshots into deterministic rankings. */
object LeaderboardService {
    private val logger = Logger.getLogger("Typewriter-OmniGUIExtension")
    private val reportedFailures = ConcurrentHashMap.newKeySet<String>()

    fun rank(entry: GuiLeaderboardEntry, viewer: Player, context: InteractionContext): List<LeaderboardRow> {
        val facts = entry.facts.mapNotNull { it.get() }.distinctBy { it.id }
        if (facts.isEmpty()) return emptyList()

        val population = entry.population.get()
        val selectedGroup = entry.group.get()
        val live = Bukkit.getOnlinePlayers().toList().map { player ->
            val scores = readScores(player, facts)
            val groupId = if (selectedGroup != null) selectedGroup.groupId(player) else null
            val snapshot = LeaderboardPopulationStore.snapshot(
                player,
                scores,
                if (groupId == null || !entry.group.isSet) emptyMap() else mapOf(entry.group.id to groupId),
            )
            if (population != null) LeaderboardPopulationStore.record(population, snapshot)
            snapshot
        }
        val candidates = if (population == null) live else LeaderboardPopulationStore.snapshots(population)
        val worlds = resolveWorlds(entry, viewer, context)
        val filtered = candidates.filter { snapshot -> worlds == null || snapshot.position?.world in worlds }

        val rows = when (entry.scope) {
            LeaderboardScope.PLAYER -> filtered.map { snapshot -> rowForPlayer(snapshot, entry, facts, entry.scoreMode) }
            LeaderboardScope.WORLD -> filtered.mapNotNull { it.position?.world }
                .distinct()
                .map { world ->
                    val members = filtered.filter { it.position?.world == world }
                    aggregateRows(members, facts, LeaderboardRowKey.World(world), world.displayName(), entry.scoreMode, world = world)
                }
            LeaderboardScope.GROUP -> {
                if (selectedGroup == null || !entry.group.isSet) emptyList()
                else filtered.mapNotNull { snapshot -> snapshot.groupIds[entry.group.id]?.let { it to snapshot } }
                    .groupBy({ it.first }, { it.second })
                    .map { (groupId, members) ->
                        aggregateRows(
                            members,
                            facts,
                            LeaderboardRowKey.Group(groupId),
                            groupId.id,
                            entry.scoreMode,
                            groupId = groupId,
                            world = members.firstNotNullOfOrNull { it.position?.world },
                        )
                    }
            }
        }
        return LeaderboardRanking.order(rows, entry.order, entry.includeZero, entry.limit)
    }

    private fun rowForPlayer(
        snapshot: LeaderboardPopulationSnapshot,
        entry: GuiLeaderboardEntry,
        facts: List<ReadableFactEntry>,
        mode: LeaderboardScoreMode,
    ): LeaderboardRow {
        val selectedScores = facts.associate { it.id to (snapshot.factScores[it.id] ?: 0) }
        return LeaderboardRow(
            key = LeaderboardRowKey.Player(snapshot.uuid),
            name = snapshot.name,
            score = combine(selectedScores.values.toList(), mode),
            groupId = snapshot.groupIds[entry.group.id].takeIf { entry.group.isSet },
            world = snapshot.position?.world,
            factScores = selectedScores,
        )
    }

    private fun aggregateRows(
        members: List<LeaderboardPopulationSnapshot>,
        facts: List<ReadableFactEntry>,
        key: LeaderboardRowKey,
        name: String,
        mode: LeaderboardScoreMode,
        groupId: GroupId? = null,
        world: World? = null,
    ): LeaderboardRow {
        val factScores = facts.associate { fact ->
            fact.id to members.sumOf { it.factScores[fact.id] ?: 0 }
        }
        return LeaderboardRow(key, name, combine(factScores.values.toList(), mode), groupId, world, factScores)
    }

    private fun resolveWorlds(
        entry: GuiLeaderboardEntry,
        viewer: Player,
        context: InteractionContext,
    ): Set<World>? {
        if (entry.worlds.isEmpty()) return null
        return entry.worlds.mapNotNull { selector ->
            runCatching { selector.get(viewer, context).world }
                .onFailure { logger.warning("Could not resolve leaderboard world selector: ${it.message}") }
                .getOrNull()
        }.toSet()
    }

    private fun readScores(player: Player, facts: List<ReadableFactEntry>): Map<String, Int> = facts.associate { fact ->
        fact.id to runCatching { fact.readForPlayersGroup(player).value }
            .getOrElse { failure ->
                val key = "${fact.id}:${failure::class.java.name}"
                if (reportedFailures.add(key)) logger.warning("Could not read leaderboard fact '${fact.id}': ${failure.message}")
                0
            }
    }

    private fun combine(values: List<Int>, mode: LeaderboardScoreMode): Int = when (mode) {
        LeaderboardScoreMode.SUM -> values.sum()
        LeaderboardScoreMode.MAX -> values.maxOrNull() ?: 0
    }

    private fun World.displayName(): String = runCatching {
        Bukkit.getWorld(UUID.fromString(identifier))?.name
    }.getOrNull() ?: identifier
}

object LeaderboardRanking {
    fun order(rows: List<LeaderboardRow>, order: LeaderboardOrder, includeZero: Boolean, limit: Int): List<LeaderboardRow> {
        val filtered = if (includeZero) rows else rows.filter { it.score != 0 }
        val comparator = Comparator<LeaderboardRow> { left, right ->
            val scoreComparison = left.score.compareTo(right.score)
            val orderedScore = if (order == LeaderboardOrder.DESCENDING) -scoreComparison else scoreComparison
            if (orderedScore != 0) orderedScore
            else left.name.lowercase(Locale.ROOT).compareTo(right.name.lowercase(Locale.ROOT))
                .takeIf { it != 0 } ?: left.key.stableKey.compareTo(right.key.stableKey)
        }
        return filtered.sortedWith(comparator).take(limit.coerceAtLeast(1))
    }
}

/** Dynamic leaderboard rows layered into an OpenGUI layout at render time. */
class LeaderboardLayout(
    private val entry: GuiLeaderboardEntry,
    private val context: InteractionContext,
    private val positions: List<Pair<Int, Int>>,
    private val previousButton: GuiSlot? = null,
    private val nextButton: GuiSlot? = null,
    override val id: String? = null,
) : MenuLayout {
    private val stateId = id?.takeIf { it.isNotBlank() } ?: "leaderboard:${entry.id}"
    override val virtualWidth: Int = (positions.maxOfOrNull { it.first } ?: 8) + 1
    override val virtualHeight: Int = (positions.maxOfOrNull { it.second } ?: 5) + 1

    override fun getSlots(session: MenuSessionService.ActiveSession, viewport: Viewport): List<GuiSlot> {
        val rows = LeaderboardService.rank(entry, session.player, context)
        val pageSize = positions.size
        if (pageSize == 0) return emptyList()
        val pageCount = ((rows.size + pageSize - 1) / pageSize).coerceAtLeast(1)
        val page = (session.pageStates[stateId] ?: 0).coerceIn(0, pageCount - 1)
        session.pageStates[stateId] = page
        val rendered = rows.drop(page * pageSize).take(pageSize).mapIndexed { index, row ->
            val position = positions[index]
            GuiSlot(position.first, position.second, buildItem(session.player, row, page * pageSize + index + 1))
        }.toMutableList()
        if (page > 0) previousButton?.let { rendered += it.copy(commands = it.commands + "gui:page -1 $stateId") }
        if (page < pageCount - 1) nextButton?.let { rendered += it.copy(commands = it.commands + "gui:page 1 $stateId") }
        return rendered
    }

    private fun buildItem(player: Player, row: LeaderboardRow, rank: Int): ItemStack {
        val blueprint = entry.rowItem.get(player, context)
        val stack = if (blueprint == Item.Empty) ItemStack(Material.PAPER) else blueprint.build(player, context).clone()
        val safeStack = stack.takeUnless { it.type.isAir } ?: ItemStack(Material.PAPER)
        val meta = safeStack.itemMeta ?: return safeStack
        val tokens = row.tokens(rank)
        meta.displayName(entry.rowName.get(player, context).replaceTokens(tokens).asMini().decoration(TextDecoration.ITALIC, false))
        val lore = entry.rowLore.map { it.get(player, context).replaceTokens(tokens).asMini().decoration(TextDecoration.ITALIC, false) }
        if (lore.isNotEmpty()) meta.lore(lore)
        safeStack.itemMeta = meta
        return safeStack
    }
}

private fun LeaderboardRow.tokens(rank: Int): Map<String, String> = buildMap {
    put("{rank}", rank.toString())
    put("{name}", name)
    put("{score}", score.toString())
    put("{group}", groupId?.id.orEmpty())
    put("{world}", world?.let { it.displayTokenName() }.orEmpty())
    factScores.forEach { (factId, value) -> put("{score_$factId}", value.toString()) }
}

private fun World.displayTokenName(): String = runCatching {
    Bukkit.getWorld(UUID.fromString(identifier))?.name
}.getOrNull() ?: identifier

private fun String.replaceTokens(tokens: Map<String, String>): String = tokens.entries.fold(this) { value, (token, replacement) -> value.replace(token, replacement) }
