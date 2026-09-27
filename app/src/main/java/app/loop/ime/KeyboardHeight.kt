package app.loop.ime

/** Scale only the keys/panels. Toolbar and system navigation insets retain their own dimensions. */
enum class KeyboardHeight(val value: String,val label: String,val padDp: Int,val rowDp: Int) {
    HIGH("high","高",212,49),
    MEDIUM("medium","中",188,43),
    LOW("low","低",164,37);

    companion object {
        fun from(value: String)=entries.firstOrNull { it.value==value } ?: HIGH
    }
}
