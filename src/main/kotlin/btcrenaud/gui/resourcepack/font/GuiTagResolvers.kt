package btcrenaud.gui.resourcepack.font

import btcrenaud.gui.editor.assets.MagicDigitFontGenerator
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver

/**
 * Internal MiniMessage resolver for `<shift:N>`, so compiled GUI titles render pixel-perfectly
 * WITHOUT CraftEngine.
 *
 * `<shift:N>` inserts the Magic Digit space character(s) advancing the text cursor by N pixels
 * (negative = backwards). The space glyphs live in the standalone pack's default font
 * ([MagicDigitFontGenerator]), which this extension generates itself — the tag is therefore
 * self-contained and never needed CraftEngine.
 *
 * Used as a fallback by the title/item MiniMessage parsing in OpenGuiActionEntry. When CraftEngine
 * IS present its own tags take precedence, keeping existing packs unchanged.
 */
object GuiTagResolvers {

    /** `<shift:N>` — advances the cursor by N pixels using Magic Digit spaces. */
    fun shift(): TagResolver = TagResolver.resolver("shift") { args, _ ->
        val amount = args.popOr("shift needs an amount").asInt().orElse(0)
        Tag.selfClosingInserting(Component.text(MagicDigitFontGenerator.spaceChar(amount)))
    }
}
