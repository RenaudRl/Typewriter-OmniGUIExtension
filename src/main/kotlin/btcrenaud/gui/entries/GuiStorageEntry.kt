package btcrenaud.gui.entries

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.extension.annotations.Tags
import com.typewritermc.engine.paper.entry.entries.ArtifactEntry
import java.util.UUID

/**
 * Persistent storage artifact used by [StorageLayout] and [StorageSlotData].
 * Each artifact holds item data for one or more storage slots.
 *
 * Create one artifact per storage menu. Items in layouts reference this artifact
 * via their `storage.entry` field.
 */
@Entry(
    "gui_storage",
    "Persistent storage artifact for GUI storage slots",
    Colors.BLUE,
    "mdi:archive"
)
@Tags("gui", "gui_storage")
class GuiStorageEntry(
    override val id: String = "",
    override val name: String = "",
    @Help("Unique artifact identifier. Used internally to persist data per group key.")
    override val artifactId: String = UUID.randomUUID().toString()
) : ArtifactEntry
