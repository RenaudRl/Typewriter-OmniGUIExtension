package btcrenaud.gui.services

import btcrenaud.gui.api.GuiSlot
import btcrenaud.gui.api.SlotAnimation
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles per-slot animations (tweening) for active GUI sessions.
 */
object AnimationService {
    private var plugin: Plugin? = null

    private fun requirePlugin(): Plugin = plugin
        ?: throw IllegalStateException("AnimationService not initialized. Call initialize(plugin) first.")

    /** FIX 6: Track only sessions with active animations instead of iterating all sessions every tick. */
    private val activeAnimatedSessions = ConcurrentHashMap<UUID, MenuSessionService.ActiveSession>()

    /** FIX 7: Synchronization lock for animatedSlots access between tick and render threads. */
    private val animationLock = Any()

    fun initialize(plugin: Plugin) {
        this.plugin = plugin
        // Global region scheduler: Folia-safe (the legacy BukkitScheduler throws on Folia).
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, { _ -> tick() }, 1L, 1L)
    }

    fun shutdown() {
        activeAnimatedSessions.clear()
    }

    /** FIX 6: Register a session when it has animated slots. */
    fun registerSession(session: MenuSessionService.ActiveSession) {
        if (session.animatedSlots.isNotEmpty()) {
            activeAnimatedSessions[session.player.uniqueId] = session
        }
    }

    /** FIX 6: Unregister a session when it no longer has animated slots. */
    fun unregisterSession(playerId: UUID) {
        activeAnimatedSessions.remove(playerId)
    }

    private fun tick() {
        // FIX 6: Only iterate sessions known to have active animations
        if (activeAnimatedSessions.isEmpty()) return

        val it = activeAnimatedSessions.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            val session = entry.value

            // FIX 7: Synchronize access to animatedSlots with render thread
            val hasAnimations = synchronized(animationLock) {
                if (session.animatedSlots.isEmpty()) {
                    it.remove()
                    false
                } else {
                    true
                }
            }
            if (!hasAnimations) continue

            val now = System.currentTimeMillis()
            var changed = false

            // FIX 7: Synchronize iteration and modification of animatedSlots
            synchronized(animationLock) {
                val animIt = session.animatedSlots.iterator()
                while (animIt.hasNext()) {
                    val animEntry = animIt.next()
                    val state = animEntry.value
                    val config = state.slot.animation
                    if (config == null) {
                        animIt.remove()
                        continue
                    }

                    val elapsed = now - state.startTime
                    val progress = (elapsed.toDouble() / config.duration).coerceIn(0.0, 1.0)

                    if (progress >= 1.0) {
                        animIt.remove()
                        session.finishedAnimations.set(animEntry.key, config.targetX to config.targetY)
                        if (com.typewritermc.core.entries.Query.findWhere<btcrenaud.gui.GuiConfigEntry> { true }.firstOrNull()?.debug == true) {
                            requirePlugin().logger.info("[Animation] Finished animation for slot ${animEntry.key}")
                        }
                    }
                    changed = true
                }

                // If no more animations, unregister this session
                if (session.animatedSlots.isEmpty()) {
                    it.remove()
                }
            }

            if (changed) {
                MenuSessionService.refresh(session.player)
            }
        }
    }

    /**
     * Calculates the interpolated position of a slot if it's animating.
     */
    fun getInterpolatedPosition(session: MenuSessionService.ActiveSession, index: Int, defaultX: Int, defaultY: Int): Pair<Int, Int> {
        // FIX 7: Synchronize read access to animatedSlots
        synchronized(animationLock) {
            val state = session.animatedSlots[index] ?: return session.finishedAnimations.get(index) ?: defaultX to defaultY
            val config = state.slot.animation ?: return defaultX to defaultY

            val elapsed = System.currentTimeMillis() - state.startTime
            val progress = (elapsed.toDouble() / config.duration).coerceIn(0.0, 1.0)

            // Easing (simplified)
            val t = when (config.easing) {
                "ease_in" -> progress * progress
                "ease_out" -> progress * (2 - progress)
                else -> progress // linear
            }

            val curX = (state.startX + (config.targetX - state.startX) * t).toInt()
            val curY = (state.startY + (config.targetY - state.startY) * t).toInt()

            return curX to curY
        }
    }
}
