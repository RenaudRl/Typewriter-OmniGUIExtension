package btcrenaud.gui.api

/**
 * Builds the MiniMessage progress line of a storage slot that collects a required amount.
 *
 * Pure on purpose: the texts come from the `gui_settings` entry, and the line is only parsed into a
 * component by the caller, so the fill rule stays testable without the engine.
 */
object StorageProgress {

    /**
     * [template] with `{current}`, `{required}` and `{bar}` replaced. The bar is [length] segments,
     * [filledSegment] repeated for the share of [required] already collected (rounded down), then
     * [emptySegment] for the rest. A [length] under 1 gives an empty bar.
     */
    fun line(
        template: String,
        filledSegment: String,
        emptySegment: String,
        length: Int,
        current: Int,
        required: Int,
    ): String {
        val bar = bar(filledSegment, emptySegment, length, current, required)
        return template
            .replace("{current}", current.toString())
            .replace("{required}", required.toString())
            .replace("{bar}", bar)
    }

    private fun bar(filledSegment: String, emptySegment: String, length: Int, current: Int, required: Int): String {
        val segments = length.coerceAtLeast(0)
        if (required <= 0) return emptySegment.repeat(segments)
        val filled = (current.toDouble() / required * segments).toInt().coerceIn(0, segments)
        return filledSegment.repeat(filled) + emptySegment.repeat(segments - filled)
    }
}
