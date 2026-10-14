package btcrenaud.gui.api

/**
 * Keeps an item's name override only when it carries one.
 *
 * The panel serializes `displayName` as the empty string for every item that does not declare a
 * name. Applying that string as-is put an empty `custom_name` on the ItemStack, which wins over the
 * item's own `item_name` component and over the vanilla name: the player saw a blank tooltip title.
 * A blank override therefore means "no override" and leaves the item's name untouched.
 */
fun nameOverride(raw: String?): String? = raw?.takeIf { it.isNotBlank() }
