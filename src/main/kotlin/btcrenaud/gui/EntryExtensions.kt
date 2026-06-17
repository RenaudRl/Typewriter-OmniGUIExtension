package btcrenaud.gui

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.typewritermc.engine.paper.entry.entries.ArtifactEntry
import org.bukkit.Bukkit
import org.koin.core.qualifier.named
import org.koin.java.KoinJavaComponent.get
import java.io.File

private val gson: Gson
    get() = get(Gson::class.java, named("dataSerializer"))

private val pluginFolder: File?
    get() = Bukkit.getPluginManager().getPlugin("Typewriter")?.dataFolder

fun ArtifactEntry.loadData(): JsonObject {
    if (artifactId.isBlank()) return JsonObject()
    val folder = pluginFolder ?: return JsonObject()
    val base = File(folder, "assets").canonicalFile
    val file = File(base, path).canonicalFile
    if (!file.path.startsWith(base.path) || !file.exists()) return JsonObject()
    return try {
        gson.fromJson(file.readText(), JsonObject::class.java) ?: JsonObject()
    } catch (_: Throwable) {
        JsonObject()
    }
}

fun ArtifactEntry.saveData(obj: JsonObject) {
    if (artifactId.isBlank()) return
    val folder = pluginFolder ?: return
    val base = File(folder, "assets").canonicalFile
    val file = File(base, path).canonicalFile
    if (!file.path.startsWith(base.path)) return
    file.parentFile.mkdirs()
    try {
        file.writeText(gson.toJson(obj))
    } catch (_: Throwable) {}
}

/** Loads the JSON section for [groupKey] (e.g. player UUID string or custom group id). */
fun ArtifactEntry.loadGroupData(groupKey: String): JsonObject {
    val root = loadData()
    return root[groupKey]?.asJsonObject ?: JsonObject()
}

/** Saves the JSON section for [groupKey] back into the artifact file. */
fun ArtifactEntry.saveGroupData(groupKey: String, obj: JsonObject) {
    if (artifactId.isBlank()) return
    val root = loadData()
    root.add(groupKey, obj)
    saveData(root)
}