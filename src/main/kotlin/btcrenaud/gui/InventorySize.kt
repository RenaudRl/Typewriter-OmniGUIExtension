package btcrenaud.gui

import com.google.gson.annotations.SerializedName

/**
 * Represents allowed inventory sizes for custom GUIs. Each size is a multiple
 * of nine to align with Minecraft's inventory rows.
 */
enum class InventorySize(val slots: Int) {
    @SerializedName("9") SIZE_9(9),
    @SerializedName("18") SIZE_18(18),
    @SerializedName("27") SIZE_27(27),
    @SerializedName("36") SIZE_36(36),
    @SerializedName("45") SIZE_45(45),
    @SerializedName("54") SIZE_54(54);
}

