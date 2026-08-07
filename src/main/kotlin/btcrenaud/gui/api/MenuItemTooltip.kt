package btcrenaud.gui.api

import com.google.common.collect.ImmutableMultimap
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta

/**
 * Strips the vanilla tooltip clutter from items drawn as menu buttons.
 *
 * A menu button is a picture with a name and a lore. Everything vanilla adds underneath — attack
 * damage, armour value, durability, "Can contain 64 stacks of items" under a bundle, enchantment
 * lists, dye and trim lines — is noise that breaks the layout of a carefully written lore, and
 * makes the same button look different depending on which material happens to back it.
 *
 * Two things are needed, not one: [ItemMeta.addItemFlags] hides the *displayed* modifiers, but on
 * 1.21.x an item with no explicit modifier list still shows its material's built-in attributes, so
 * the list must also be explicitly emptied.
 */
object MenuItemTooltip {

    /**
     * Returns the slot with a cleaned-up item, or the slot unchanged when its item is a real
     * object rather than a button.
     *
     * The discriminator is [GuiSlot.allowPickup]: a slot the player can take from — a backpack
     * cell, a wardrobe equipment slot — holds the actual stack that leaves the menu. Emptying its
     * attribute list would hand the player a gutted item, so those are left strictly alone.
     * Ghost slots copy to the cursor and are treated the same way.
     */
    fun forSlot(slot: GuiSlot): GuiSlot {
        if (slot.allowPickup || slot.isGhost) return slot
        val cleaned = hide(slot.item) ?: return slot
        return slot.copy(item = cleaned)
    }

    /**
     * Returns a copy of [stack] with every vanilla tooltip section hidden, or `null` when there is
     * nothing to do (empty stack, or meta the server refuses to hand out).
     */
    fun hide(stack: ItemStack): ItemStack? {
        if (stack.type.isAir) return null
        val copy = stack.clone()
        val meta = copy.itemMeta ?: return null
        meta.addItemFlags(*org.bukkit.inventory.ItemFlag.values())
        // HIDE_ATTRIBUTES only hides modifiers that are written on the item. A sword with no
        // modifier list of its own still renders its material defaults, so the list is replaced
        // by an explicitly empty one.
        meta.attributeModifiers = ImmutableMultimap.of()
        copy.itemMeta = meta
        return copy
    }
}
