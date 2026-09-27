package app.loop.ime

data class PanelAction(val label: String,val code: String,val icon: String="",val enabled: Boolean=true,val selected: Boolean=false)
data class PanelCard(val title: String,val text: String,val insert: ()->Unit,val actions: List<Pair<String,()->Unit>> = emptyList())
object ToolCatalog {
    val defaults=listOf("clipboard","memory","emoji","edit","layouts","height","speech_settings","settings")
    val labels=mapOf("clipboard" to "剪贴板","memory" to "输入记忆","emoji" to "表情符号","edit" to "文本编辑","layouts" to "键盘布局","height" to "键盘高度","speech_settings" to "语音设置","settings" to "全部设置","phrases" to "常用短语","hand" to "单手模式","quick" to "快捷建议")
    fun order(value: String): List<String> = (value.split(',').filter { it in labels }+defaults).distinct().take(8)
    fun actions(value: String)=order(value).map { PanelAction(labels.getValue(it),it,when(it) { "speech_settings"->"mic";"layouts"->"keyboard";else->it }) }
}
