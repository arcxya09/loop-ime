package app.loop.ime

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.*
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[37],application=Application::class,qualifiers="zh-rCN-w360dp-h800dp-xxhdpi")
class CandidatePanelTest {
    private fun all(v: View): List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList())
    private fun measure(v: View) { v.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));v.layout(0,0,v.measuredWidth,v.measuredHeight) }
    private fun words(v: View)=all(v).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }
    private fun fixture(body: (KeyboardView,MutableList<String>)->Unit) {
        val life=Robolectric.buildActivity(Activity::class.java).setup().visible()
        try { val events=mutableListOf<String>();val k=KeyboardView(life.get(),events::add);life.get().setContentView(k);measure(k);body(k,events) }
        finally { life.pause().stop().destroy() }
    }
    @Test fun cloudResultsAndErrorsLeaveLocalWordsVisibleAndInPlace()=fixture { k,_ ->
        k.composition("9264");k.setCandidates(listOf("王" to {},"往" to {},"王小明" to {}));measure(k)
        val strip=k.findViewWithTag<View>("candidate_strip");val before=words(strip)
        k.setPredictions(listOf("王老师" to {}));k.status("AI 候选超时，稍后重试");measure(k)
        assertEquals(before,words(strip));assertTrue(words(k.findViewWithTag("cloud_predictions")).contains("王老师"))
        assertEquals(View.VISIBLE,k.findViewWithTag<View>("keyboard_notice").visibility)
        k.setPredictions(emptyList());assertEquals(before,words(strip))
    }
    @Test fun backgroundAiFailuresNeverResizeOrMoveCandidatesAndKeys()=fixture { k,events ->
        for(nine in listOf(true,false)) {
            k.setNineKey(nine);k.composition(if(nine)"64426" else "nihao")
            var chosen=false
            k.setCandidates(listOf("你好" to { chosen=true },"你们" to {}));measure(k)
            val strip=k.findViewWithTag<View>("candidate_strip")
            val body=k.findViewWithTag<View>("keyboard_body")
            val height=k.height;val top=body.top;val width=strip.width
            val before=words(strip)
            for(error in listOf("AI 候选超时，稍后重试","AI：连接失败","AI 暂无匹配候选")) {
                k.aiStatus(error);measure(k)
                assertEquals(height,k.height);assertEquals(top,body.top);assertEquals(width,strip.width)
                assertEquals(before,words(strip));assertEquals(View.GONE,k.findViewWithTag<View>("keyboard_notice").visibility)
                val tools=k.findViewWithTag<View>("tools")
                assertTrue(tools.contentDescription.contains(error));assertTrue(tools.performLongClick())
                assertEquals(error,org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
            }
            all(strip).filterIsInstance<TextView>().single { it.text=="你好" }.performClick();assertTrue(chosen)
            k.findViewWithTag<View>("tools").performClick();assertEquals("tools",events.last())
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(9))
            measure(k);assertEquals(height,k.height)
            assertEquals("Loop 工具与设置",k.findViewWithTag<View>("tools").contentDescription)
        }
    }
    @Test fun backgroundAiFailurePreservesAnActionableNotice()=fixture { k,_ ->
        var undone=false
        k.status("AI 已纠错 · 点击撤销") { undone=true };measure(k)
        val height=k.height
        k.aiStatus("AI：连接失败");measure(k)
        assertEquals(height,k.height)
        k.findViewWithTag<View>("keyboard_status").performClick();assertTrue(undone)
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun expandedWordsScrollAndLoadMoreWithoutGrowingTheKeyboard()=fixture { k,events ->
        val clicked=mutableListOf<Int>()
        val candidates=(0 until 65).map { "候选词$it" to { clicked.add(it);Unit } }
        k.composition("9264");k.setCandidates(candidates.take(30));k.candidatePaging(true);measure(k)
        val height=k.height;k.findViewWithTag<View>("expand_candidates").performClick();measure(k)
        val panel=k.findViewWithTag<View>("expanded_candidates")
        assertEquals(height,k.height);assertTrue(panel.isShown)
        all(panel).filterIsInstance<TextView>().single { it.text=="更多候选词" }.performClick()
        assertEquals(listOf("more_candidates"),events)
        k.candidatePaging(false);k.setCandidates(candidates);measure(k)
        val scroll=panel as android.widget.ScrollView
        assertTrue(scroll.getChildAt(0).height>scroll.height)
        scroll.scrollTo(0,scroll.getChildAt(0).height);measure(k)
        val bitmap=Bitmap.createBitmap(k.width,k.height,Bitmap.Config.ARGB_8888);k.draw(Canvas(bitmap))
        File("build/candidates-alpha14-expanded.png").apply { parentFile!!.mkdirs();outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) } };bitmap.recycle()
        all(panel).filterIsInstance<TextView>().single { it.text=="候选词64" }.performClick()
        assertEquals(listOf(64),clicked);assertEquals(View.GONE,panel.visibility)
    }
    @Test fun downwardGestureExpandsAndDoesNotChooseTheTouchedWord()=fixture { k,_ ->
        var selected=false;k.setCandidates(listOf("你好" to { selected=true }));measure(k)
        val scroll=all(k.findViewWithTag("candidate_strip")).filterIsInstance<android.widget.HorizontalScrollView>().single()
        val time=SystemClock.uptimeMillis()
        for((action,y) in listOf(MotionEvent.ACTION_DOWN to 15f,MotionEvent.ACTION_MOVE to 100f,MotionEvent.ACTION_UP to 100f)) {
            val e=MotionEvent.obtain(time,time,action,30f,y,0);try { scroll.dispatchTouchEvent(e) } finally { e.recycle() }
        }
        assertFalse(selected);assertEquals(View.VISIBLE,k.findViewWithTag<View>("expanded_candidates").visibility)
    }
}
