package btcrenaud.gui.api

import btcrenaud.gui.entries.GuiSettingEntry
import btcrenaud.gui.entries.NodeDirectionalMap
import btcrenaud.gui.entries.NodeState
import btcrenaud.gui.services.MenuSessionService
import com.typewritermc.core.entries.Query
import com.typewritermc.engine.paper.utils.item.Item
import org.bukkit.inventory.ItemStack
import kotlin.math.abs

/**
 * A specialized layout for Skill Trees that automatically resolves 
 * directional connector items between core nodes.
 */
class SkillTreeLayout(
    val coreNodes: List<CoreNode>,
    val connections: List<NodeConnection>,
    override val id: String? = null,
    val stateProvider: (connection: NodeConnection, session: MenuSessionService.ActiveSession) -> NodeState = { _, _ -> NodeState.UNLOCKED }
) : MenuLayout {

    override fun getSlots(session: MenuSessionService.ActiveSession, viewport: Viewport): List<GuiSlot> {
        val settings = Query.find<GuiSettingEntry>().firstOrNull() ?: return emptyList()
        val slots = mutableMapOf<Pair<Int, Int>, GuiSlot>()

        // 1. Place Core Nodes
        for (node in coreNodes) {
            slots[node.x to node.y] = node.toGuiSlot()
        }

        // 2. Calculate Path Points
        val pathPoints = mutableSetOf<Pair<Int, Int>>()
        for (conn in connections) {
            val from = coreNodes.find { it.id == conn.from } ?: continue
            val to = coreNodes.find { it.id == conn.to } ?: continue
            
            fillPath(from.x, from.y, to.x, to.y, pathPoints)
        }

        // 3. Resolve Path Nodes (Connectors)
        for (point in pathPoints) {
            if (slots.containsKey(point)) continue // Core nodes take priority
            
            val up = hasConnection(point.first, point.second - 1, pathPoints, coreNodes)
            val down = hasConnection(point.first, point.second + 1, pathPoints, coreNodes)
            val left = hasConnection(point.first - 1, point.second, pathPoints, coreNodes)
            val right = hasConnection(point.first + 1, point.second, pathPoints, coreNodes)
            
            // Determine the connector state from the relevant connection context
            val relevantConnection = connections.find { conn ->
                val from = coreNodes.find { it.id == conn.from }
                val to = coreNodes.find { it.id == conn.to }
                from != null && to != null && point in pathPointsBetween(from, to)
            }
            val state = relevantConnection?.let { stateProvider(it, session) } ?: NodeState.UNLOCKED
            val directionalMap = settings.nodeDefaults[state] ?: NodeDirectionalMap()
            
            val item = resolveConnector(directionalMap, up, down, left, right)
            if (item != Item.Empty) {
                slots[point] = GuiSlot(point.first, point.second, item.build(session.player))
            }
        }

        return slots.values.toList()
    }

    private fun pathPointsBetween(from: CoreNode, to: CoreNode): Set<Pair<Int, Int>> {
        val points = mutableSetOf<Pair<Int, Int>>()
        fillPath(from.x, from.y, to.x, to.y, points)
        return points
    }

    private fun fillPath(x1: Int, y1: Int, x2: Int, y2: Int, path: MutableSet<Pair<Int, Int>>) {
        var cx = x1
        var cy = y1
        
        // Manhattan path: Horizontal then Vertical
        val dx = if (x2 > x1) 1 else -1
        val dy = if (y2 > y1) 1 else -1
        
        while (cx != x2) {
            cx += dx
            if (cx != x2 || cy != y2) path.add(cx to cy)
        }
        while (cy != y2) {
            cy += dy
            if (cx != x2 || cy != y2) path.add(cx to cy)
        }
    }

    private fun hasConnection(x: Int, y: Int, path: Set<Pair<Int, Int>>, cores: List<CoreNode>): Boolean {
        return path.contains(x to y) || cores.any { it.x == x && it.y == y }
    }

    private fun resolveConnector(map: NodeDirectionalMap, up: Boolean, down: Boolean, left: Boolean, right: Boolean): Item {
        return when {
            up && down && left && right -> map.upDownLeftRight
            up && down && left -> map.upDownLeft
            up && down && right -> map.upDownRight
            up && left && right -> map.upLeftRight
            down && left && right -> map.downLeftRight
            up && down -> map.upDown
            up && left -> map.upLeft
            up && right -> map.upRight
            down && left -> map.downLeft
            down && right -> map.downRight
            left && right -> map.leftRight
            up -> map.up
            down -> map.down
            left -> map.left
            right -> map.right
            else -> map.none
        }
    }

    override val virtualWidth: Int get() = coreNodes.maxOfOrNull { it.x }?.plus(1) ?: 9
    override val virtualHeight: Int get() = coreNodes.maxOfOrNull { it.y }?.plus(1) ?: 6
}

/**
 * A major node in the skill tree (clickable).
 */
data class CoreNode(
    val id: String,
    val x: Int,
    val y: Int,
    val item: ItemStack,
    val commands: List<String> = emptyList()
) {
    fun toGuiSlot() = GuiSlot(x, y, item, commands = commands)
}

/**
 * A connection between two core nodes.
 */
data class NodeConnection(val from: String, val to: String)
