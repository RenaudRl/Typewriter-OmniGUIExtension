package btcrenaud.gui.services

import btcrenaud.gui.entries.GuiLeaderboardEntry
import btcrenaud.gui.entries.GuiLeaderboardPopulationEntry
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.typewritermc.engine.paper.entry.AssetManager
import com.typewritermc.engine.paper.entry.entries.GroupId
import com.typewritermc.engine.paper.entry.entries.ReadableFactEntry
import com.typewritermc.engine.paper.utils.position
import com.typewritermc.core.utils.point.Position
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.cancel
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.HandlerList
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/** Last-known values kept by the extension for one player. */
data class LeaderboardPopulationSnapshot(
    val uuid: UUID,
    val name: String,
    val position: Position?,
    val factScores: Map<String, Int>,
    val groupIds: Map<String, GroupId>,
)

/**
 * Owns the extension-side leaderboard population cache and its asynchronous artifact writes.
 *
 * The cache is deliberately independent from Typewriter's fact database: the official engine
 * does not expose an offline read API. The artifact is a last-known snapshot, refreshed while a
 * player is online and once more during [PlayerQuitEvent].
 */
object LeaderboardPopulationStore : Listener {
    private const val WRITE_DEBOUNCE_MS = 200L

    private val logger = Logger.getLogger("Typewriter-OmniGUIExtension")
    private val populations = ConcurrentHashMap<String, ConcurrentHashMap<UUID, LeaderboardPopulationSnapshot>>()
    private val writeLocks = ConcurrentHashMap<String, Mutex>()
    private val writeRevisions = ConcurrentHashMap<String, Long>()
    private val activeWrites = HashSet<String>()
    private val scheduleLock = Any()
    private val writeJobs = ConcurrentHashMap<String, Job>()
    private var assetManager: AssetManager? = null
    private var scope: CoroutineScope? = null
    private var trackedDefinitions: List<Pair<GuiLeaderboardEntry, GuiLeaderboardPopulationEntry>> = emptyList()

    suspend fun initialize(
        plugin: Plugin,
        assetManager: AssetManager,
        entries: Collection<GuiLeaderboardEntry>,
        artifacts: Collection<GuiLeaderboardPopulationEntry>,
    ) {
        this.assetManager = assetManager
        this.scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        trackedDefinitions = entries.mapNotNull { leaderboard ->
            leaderboard.population.get()?.let { population -> leaderboard to population }
        }

        artifacts.distinctBy { it.artifactId }
            .filter { it.artifactId.isNotBlank() }
            .forEach { population -> load(population) }

        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    fun snapshots(population: GuiLeaderboardPopulationEntry): List<LeaderboardPopulationSnapshot> =
        populations[population.artifactId]?.values?.toList().orEmpty()

    fun snapshot(
        player: Player,
        factScores: Map<String, Int>,
        groupIds: Map<String, GroupId>,
    ): LeaderboardPopulationSnapshot = LeaderboardPopulationSnapshot(
        uuid = player.uniqueId,
        name = player.name,
        position = player.position,
        factScores = factScores,
        groupIds = groupIds,
    )

    fun record(population: GuiLeaderboardPopulationEntry, snapshot: LeaderboardPopulationSnapshot) {
        val artifactId = population.artifactId.takeIf { it.isNotBlank() } ?: return
        val cache = populations.computeIfAbsent(artifactId) { ConcurrentHashMap() }
        cache.compute(snapshot.uuid) { _, previous -> previous?.merge(snapshot) ?: snapshot }
        scheduleWrite(population, artifactId)
    }

    /** Captures all configured leaderboard facts before the engine drops the player session. */
    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.player
        trackedDefinitions.groupBy { it.second.artifactId }.forEach { (_, definitions) ->
            val population = definitions.first().second
            val facts = definitions.asSequence()
                .flatMap { it.first.facts.asSequence() }
                .mapNotNull { it.get() }
                .distinctBy { it.id }
                .toList()
            val groups = definitions.asSequence()
                .map { it.first.group }
                .filter { it.isSet }
                .distinctBy { it.id }
                .toList()

            val scores = facts.associate { fact -> fact.id to readFact(player, fact) }
            val groupIds = groups.mapNotNull { ref ->
                val group = ref.get() ?: return@mapNotNull null
                group.groupId(player)?.let { ref.id to it }
            }.toMap()
            record(population, snapshot(player, scores, groupIds))
        }
    }

    suspend fun shutdown() {
        HandlerList.unregisterAll(this)
        writeJobs.values.toList().joinAll()
        writeJobs.clear()
        scope?.cancel()
        scope = null
        assetManager = null
        trackedDefinitions = emptyList()
    }

    private suspend fun load(population: GuiLeaderboardPopulationEntry) {
        val manager = assetManager ?: return
        val content = if (manager.containsAsset(population)) manager.fetchStringAsset(population) else null
        val snapshots = content?.let { LeaderboardPopulationCodec.decode(it) }.orEmpty()
        populations[population.artifactId] = ConcurrentHashMap(snapshots.associateBy { it.uuid })
        if (content != null && snapshots.isEmpty() && content.isNotBlank()) {
            logger.warning("Ignoring malformed leaderboard population artifact '${population.id}'.")
        }
    }

    private fun scheduleWrite(population: GuiLeaderboardPopulationEntry, artifactId: String) {
        val writer = scope ?: return
        synchronized(scheduleLock) {
            writeRevisions[artifactId] = (writeRevisions[artifactId] ?: 0L) + 1L
            if (!activeWrites.add(artifactId)) return
        }

        val job = writer.launch {
            val mutex = writeLocks.computeIfAbsent(artifactId) { Mutex() }
            try {
                while (true) {
                    delay(WRITE_DEBOUNCE_MS)
                    val revision = synchronized(scheduleLock) { writeRevisions[artifactId] ?: 0L }
                    mutex.withLock {
                        val current = populations[artifactId]?.values?.toList().orEmpty()
                        assetManager?.storeStringAsset(population, LeaderboardPopulationCodec.encode(current))
                    }
                    synchronized(scheduleLock) {
                        if (writeRevisions[artifactId] == revision) {
                            activeWrites.remove(artifactId)
                            return@launch
                        }
                    }
                }
            } finally {
                writeJobs.remove(artifactId)
            }
        }
        writeJobs[artifactId] = job
    }

    private fun readFact(player: Player, fact: ReadableFactEntry): Int = runCatching {
        fact.readForPlayersGroup(player).value
    }.getOrElse { failure ->
        logger.warning("Could not read leaderboard fact '${fact.id}' for ${player.uniqueId}: ${failure.message}")
        0
    }

    private fun LeaderboardPopulationSnapshot.merge(newer: LeaderboardPopulationSnapshot) = copy(
        name = newer.name,
        position = newer.position,
        factScores = factScores + newer.factScores,
        groupIds = groupIds + newer.groupIds,
    )
}

/** Stable JSON boundary for the extension-owned snapshot artifact. */
object LeaderboardPopulationCodec {
    private val gson = Gson()

    fun encode(snapshots: Collection<LeaderboardPopulationSnapshot>): String {
        val root = JsonObject().apply {
            addProperty("version", 1)
            add("players", JsonObject())
        }
        val players = root.getAsJsonObject("players")
        snapshots.sortedBy { it.uuid.toString() }.forEach { snapshot ->
            val player = JsonObject().apply {
                addProperty("name", snapshot.name)
                snapshot.position?.let { add("position", encode(it)) }
                add("facts", JsonObject().also { facts ->
                    snapshot.factScores.toSortedMap().forEach { (id, value) -> facts.addProperty(id, value) }
                })
                add("groups", JsonObject().also { groups ->
                    snapshot.groupIds.toSortedMap().forEach { (refId, groupId) -> groups.addProperty(refId, groupId.id) }
                })
            }
            players.add(snapshot.uuid.toString(), player)
        }
        return gson.toJson(root)
    }

    fun decode(content: String): List<LeaderboardPopulationSnapshot> = runCatching {
        val root = JsonParser.parseString(content).asJsonObject
        val players = root.getAsJsonObject("players") ?: return emptyList()
        players.entrySet().mapNotNull { (rawUuid, element) ->
            element.takeIf { it.isJsonObject }?.let { decodePlayer(rawUuid, it.asJsonObject) }
        }
    }.getOrElse { emptyList() }

    private fun encode(position: Position): JsonObject = JsonObject().apply {
        addProperty("world", position.world.identifier)
        addProperty("x", position.x)
        addProperty("y", position.y)
        addProperty("z", position.z)
        addProperty("yaw", position.yaw)
        addProperty("pitch", position.pitch)
    }

    private fun decodePlayer(rawUuid: String, player: JsonObject): LeaderboardPopulationSnapshot? {
        val uuid = runCatching { UUID.fromString(rawUuid) }.getOrNull() ?: return null
        val position = player.getAsJsonObject("position")?.let { raw ->
            val world = raw.get("world")?.stringValue()?.takeIf { it.isNotBlank() } ?: return@let null
            Position(
                com.typewritermc.core.utils.point.World(world),
                raw.number("x"), raw.number("y"), raw.number("z"),
                raw.number("yaw").toFloat(), raw.number("pitch").toFloat(),
            )
        }
        val facts = player.getAsJsonObject("facts")?.entrySet()?.mapNotNull { (id, value) ->
            value.asIntOrNull()?.let { id to it }
        }?.toMap().orEmpty()
        val groups = player.getAsJsonObject("groups")?.entrySet()?.mapNotNull { (refId, value) ->
            value.stringValue()?.takeIf { it.isNotBlank() }?.let { refId to GroupId(it) }
        }?.toMap().orEmpty()
        return LeaderboardPopulationSnapshot(
            uuid = uuid,
            name = player.get("name")?.stringValue()?.takeIf { it.isNotBlank() } ?: rawUuid,
            position = position,
            factScores = facts,
            groupIds = groups,
        )
    }

    private fun JsonObject.number(key: String): Double = runCatching { get(key)?.asDouble ?: 0.0 }.getOrDefault(0.0)
    private fun com.google.gson.JsonElement.stringValue(): String? = runCatching { asString }.getOrNull()
    private fun com.google.gson.JsonElement.asIntOrNull(): Int? = runCatching { asInt }.getOrNull()
}
