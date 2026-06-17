package btcrenaud.gui

/**
 * Centralized constants shared across the GUI extension.
 *
 * Avoids duplication of magic numbers (pack_format, ports, etc.)
 * and provides a single source of truth for configuration values.
 */
object GuiConstants {
    /**
     * Minecraft resource pack format version.
     *
     * Minecraft 1.21.4 uses pack_format 34.
     * Update this when targeting a different Minecraft version.
     *
     * @see <a href="https://minecraft.wiki/w/Pack_format">Minecraft Wiki — Pack Format</a>
     */
    const val PACK_FORMAT = 34

    /** Default HTTP port for the Ktor web editor server. */
    const val DEFAULT_HTTP_PORT = 8082

    /** Default Socket.IO port for real-time editor communication. */
    const val DEFAULT_SOCKET_PORT = 9093

    /** Maximum frame payload length (5 MB) for large asset transfers. */
    const val MAX_FRAME_PAYLOAD = 5 * 1024 * 1024
}
