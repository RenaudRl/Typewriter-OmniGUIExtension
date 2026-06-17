package btcrenaud.gui.serializers

import com.google.gson.*
import com.typewritermc.core.serialization.DataSerializer
import com.typewritermc.engine.paper.utils.Sound
import com.typewritermc.engine.paper.utils.DefaultSoundId
import btcrenaud.gui.GuiAudioData
import java.lang.reflect.Type

/**
 * Custom serializer for [GuiAudioData] that provides backward compatibility
 * for old entries where audio fields were stored as plain strings.
 *
 * Old format (string):
 *   "audio": { "onOpen": "minecraft:block.note_block.pling" }
 *
 * New format (Sound object):
 *   "audio": { "onOpen": { "soundId": {"type": "default", "value": "..."}, ... } }
 *
 * The serializer delegates to the engine's [com.typewritermc.engine.paper.loader.serializers.SoundSerializer]
 * for each field, and also handles the case where a field is a plain string directly
 * (which happens when Gson's reflective adapter bypasses the SoundTypeAdapterFactory).
 */
class GuiAudioDataSerializer : DataSerializer<GuiAudioData> {
    override val type: Type = GuiAudioData::class.java

    override fun serialize(src: GuiAudioData?, typeOfSrc: Type?, context: JsonSerializationContext?): JsonElement {
        if (src == null) return JsonNull.INSTANCE
        val obj = JsonObject()
        obj.add("onOpen", serializeSound(src.onOpen, context))
        obj.add("onClose", serializeSound(src.onClose, context))
        obj.add("onScroll", serializeSound(src.onScroll, context))
        obj.add("onClick", serializeSound(src.onClick, context))
        return obj
    }

    override fun deserialize(json: JsonElement?, typeOfT: Type?, context: JsonDeserializationContext?): GuiAudioData {
        if (json == null || !json.isJsonObject) return GuiAudioData()
        val obj = json.asJsonObject
        return GuiAudioData(
            onOpen = deserializeSound(obj, "onOpen", context),
            onClose = deserializeSound(obj, "onClose", context),
            onScroll = deserializeSound(obj, "onScroll", context),
            onClick = deserializeSound(obj, "onClick", context),
        )
    }

    private fun serializeSound(sound: Sound?, context: JsonSerializationContext?): JsonElement {
        if (sound == null) return JsonNull.INSTANCE
        return context?.serialize(sound, Sound::class.java) ?: JsonNull.INSTANCE
    }

    /**
     * Deserializes a single audio field, handling both old (string) and new (object) formats.
     */
    private fun deserializeSound(obj: JsonObject, key: String, context: JsonDeserializationContext?): Sound? {
        if (!obj.has(key)) return null
        val element = obj.get(key)
        if (element.isJsonNull) return null

        // Backward compatibility: old entries stored audio fields as plain strings
        if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
            return Sound(soundId = DefaultSoundId(element.asString))
        }

        // Normal deserialization via engine's SoundSerializer
        return context?.deserialize(element, Sound::class.java)
    }
}
