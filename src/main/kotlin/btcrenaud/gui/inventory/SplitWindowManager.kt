package btcrenaud.gui.inventory

import org.bukkit.entity.Player

/**
 * Tracks which players have an extended-viewport menu open.
 *
 * The state is carried by a scoreboard tag rather than a private set, so any other plugin — an
 * inventory-hiding module, an anti-cheat, a scoreboard script — can ask the question without
 * taking a compile-time dependency on this extension.
 *
 * Folia-safe: scoreboard tags are player-scoped state, read and written on the player's own region.
 */
object SplitWindowManager {

    const val TAG_EXTENDED = "omnigui_extended_menu"
    const val TOP_SLOT_COUNT = 54
    const val PLAYER_SLOT_COUNT = 36
    const val FIRST_PLAYER_SLOT = TOP_SLOT_COUNT
    const val EXTENDED_SLOT_COUNT = TOP_SLOT_COUNT + PLAYER_SLOT_COUNT

    fun slotCount(): Int = EXTENDED_SLOT_COUNT

    fun isBottomSlot(rawSlot: Int): Boolean =
        rawSlot in FIRST_PLAYER_SLOT until EXTENDED_SLOT_COUNT

    fun markExtended(player: Player) {
        player.addScoreboardTag(TAG_EXTENDED)
    }

    fun unmarkExtended(player: Player) {
        player.removeScoreboardTag(TAG_EXTENDED)
    }

    fun isExtended(player: Player): Boolean = TAG_EXTENDED in player.scoreboardTags
}
