package btcrenaud.gui.editor.assets

import net.kyori.adventure.key.Key

/**
 * Fonts of the GUI resource pack. None of them lives in `minecraft:default`.
 *
 * Within one font the first provider defining a character wins: injecting our characters into
 * `minecraft:default` hid those of other plugins (CraftEngine emojis, proven in game on
 * 2026-09-27), and a single broken provider there made the client reject the whole pack. Each
 * family therefore has its own font, and a title combines them component by component — the
 * horizontal cursor is shared across fonts.
 */
object GuiFonts {

    const val NAMESPACE = "gui"

    /** `<shift:N>` spacing: a single `space` provider. */
    val SPACE: Key = Key.key(NAMESPACE, "space")

    /** Hand-made font providers dropped in `assets/font/`. */
    val GLYPHS: Key = Key.key(NAMESPACE, "glyphs")

    /** Location of a font definition inside the pack: `assets/<ns>/font/<path>.json`. */
    fun zipPath(font: Key): String = "assets/${font.namespace()}/font/${font.value()}.json"
}
