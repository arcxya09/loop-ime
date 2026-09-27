package app.loop.ime

import android.view.inputmethod.EditorInfo

internal data class EnterKey(val label: String, val action: Int?) {
    companion object {
        fun forEditor(info: EditorInfo?): EnterKey {
            if(info==null || info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION!=0)return EnterKey("换行",null)
            if(!info.actionLabel.isNullOrBlank())return EnterKey(info.actionLabel.toString(),info.actionId)
            val action=info.imeOptions and EditorInfo.IME_MASK_ACTION
            val label=when(action) {
                EditorInfo.IME_ACTION_SEND -> "发送"
                EditorInfo.IME_ACTION_SEARCH -> "搜索"
                EditorInfo.IME_ACTION_NEXT -> "下一项"
                EditorInfo.IME_ACTION_PREVIOUS -> "上一项"
                EditorInfo.IME_ACTION_DONE -> "完成"
                EditorInfo.IME_ACTION_GO -> "前往"
                else -> return EnterKey("换行",null)
            }
            return EnterKey(label,action)
        }
    }
}
