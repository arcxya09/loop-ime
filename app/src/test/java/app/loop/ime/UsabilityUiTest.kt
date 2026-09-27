package app.loop.ime

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[37],application=Application::class,qualifiers="w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class UsabilityUiTest {
    private fun all(v: View): List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun measure(v: View) { v.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));v.layout(0,0,v.measuredWidth,v.measuredHeight) }
    private fun capture(v: View,name: String) {
        val out=File("build/ui-0.3").apply { mkdirs() };val bitmap=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bitmap));File(out,name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
    @Test fun toolsAndSpecializedPanelsKeepHeightAndAccessibleTileSize() {
        val c=RuntimeEnvironment.getApplication();val events=mutableListOf<String>();val k=KeyboardView(c,events::add)
        for(preset in KeyboardHeight.entries) {
            k.setHeightPreset(preset);k.setMode(true);measure(k);val height=k.height
            k.composition("ni hao");k.setCandidates(listOf("你好" to {}))
            k.tools(ToolCatalog.actions(""),listOf(PanelAction("隐私模式 · 关闭","privacy"),PanelAction("此应用 · 跟随全局","app_privacy")))
            measure(k);assertEquals(height,k.height)
            val tiles=all(k).filter { it.tag?.toString()?.startsWith("tool:")==true };assertEquals(8,tiles.size)
            tiles.forEach { assertTrue(it.height>=48);assertTrue(it.width>=48) }
            capture(k,"tools-${preset.value}.png")
            tiles.first().performClick();assertEquals("clipboard",events.last())
            k.actionPanel("编辑",listOf(PanelAction("←","left"),PanelAction("→","right")));measure(k);assertEquals(height,k.height)
            k.closePanel();measure(k);assertEquals(height,k.height);assertEquals("ni hao",k.findViewWithTag<TextView>("keyboard_preedit").text)
            k.setInputKind("phone");k.setMode(false,true);measure(k);assertEquals(height,k.height)
            assertTrue(all(k.findViewWithTag("keyboard_body")).filterIsInstance<TextView>().any { it.text=="+" })
        }
    }
    @Test fun largeTextDarkPanelsStayFixedAndSettingsBackTracksItsParent() {
        val base=RuntimeEnvironment.getApplication()
        val cfg=Configuration(base.resources.configuration).apply { fontScale=1.5f;uiMode=Configuration.UI_MODE_NIGHT_YES }
        val c=base.createConfigurationContext(cfg);val k=KeyboardView(c) {}
        k.setHeightPreset(KeyboardHeight.LOW);measure(k);val before=k.height
        k.tools(ToolCatalog.actions(""),listOf(PanelAction("隐私保护 · 生效","privacy",selected=true),PanelAction("字段强制隐私","app_privacy",enabled=false)))
        measure(k);assertEquals(before,k.height);assertEquals(8,all(k).count { it.tag?.toString()?.startsWith("tool:")==true })
        capture(k,"tools-large-dark.png")
        val life=Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            fun click(text: String)=all(life.get().window.decorView).filterIsInstance<TextView>().single { it.text==text }.performClick()
            click("数据与维护　›");click("高级维护");click("数据库检查与恢复");click("‹ 返回")
            assertTrue(all(life.get().window.decorView).filterIsInstance<TextView>().any { it.text.toString()=="高级维护" })
            click("‹ 返回");assertTrue(all(life.get().window.decorView).filterIsInstance<TextView>().any { it.text.toString()=="数据与维护" })
        } finally { life.pause().stop().destroy() }
    }
    @Test fun oneHandMovesKeysWithoutChangingHeightAndPrivatePreviewClosesWithPanel() {
        val life=Robolectric.buildActivity(android.app.Activity::class.java).setup().visible()
        try {
            val k=KeyboardView(life.get()) {};life.get().setContentView(k);measure(k)
            val body=k.findViewWithTag<View>("keyboard_body");val height=k.height
            k.setHand("left");measure(k);assertEquals(height,k.height);assertTrue(body.paddingRight>body.paddingLeft)
            k.setHand("right");measure(k);assertEquals(height,k.height);assertTrue(body.paddingLeft>body.paddingRight)
            k.setHand("off");measure(k);assertEquals(body.paddingLeft,body.paddingRight)
            k.cards("剪贴板",listOf(PanelCard("当前剪贴板","仅供预览的完整内容",{})));measure(k)
            all(k).filterIsInstance<TextView>().single { it.text=="仅供预览的完整内容" }.performClick()
            val dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(dialog.isShowing);assertTrue(dialog.window!!.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE!=0)
            k.closePanel();assertFalse(dialog.isShowing);measure(k);assertEquals(height,k.height)
        } finally { life.pause().stop().destroy() }
    }
}
