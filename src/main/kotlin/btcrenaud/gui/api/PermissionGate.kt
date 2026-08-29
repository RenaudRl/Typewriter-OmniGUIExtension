package btcrenaud.gui.api

import org.bukkit.entity.Player

/**
 * The single owner of "is this optional permission field actually a gate?".
 *
 * Every permission field on a menu is opt-in, but the web editor serializes an unset one as `""`
 * rather than omitting it. Bukkit resolves an unregistered node — `""` included — through
 * `Permission.DEFAULT_PERMISSION`, which is `OP`: a blank field read as a real gate hides the slot
 * from every non-operator while operators keep seeing it. A blank gate is therefore no gate.
 *
 * Slot visibility, slot interactivity and view reachability all ask the same question, so they all
 * ask it here.
 */
object PermissionGate {

    /**
     * Whether a holder passes the gate declared by [permission].
     *
     * [hasPermission] is passed in rather than a `Player` so the rule stays testable without a
     * server; prefer the [permits] overload at call sites that already hold the player.
     */
    fun admits(permission: String?, hasPermission: (String) -> Boolean): Boolean =
        if (permission.isNullOrBlank()) true else hasPermission(permission)
}

/** Whether [player] passes the gate declared by this optional permission field. */
fun String?.permits(player: Player): Boolean =
    PermissionGate.admits(this, player::hasPermission)
