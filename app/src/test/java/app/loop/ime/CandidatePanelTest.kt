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
        val strip=k.findViewWithTag<View>("candidate_strip");val before=words(strip);val height=k.height
        k.setPredictions(listOf("王老师" to {}));k.status("AI 候选超时，稍后重试");measure(k)
        assertEquals(before,words(strip).take(before.size));assertTrue(words(k.findViewWithTag("cloud_predictions")).contains("王老师"))
        assertEquals(height,k.height);assertEquals(View.GONE,k.findViewWithTag<View>("keyboard_notice").visibility)
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
    @Test fun allFeedbackAndPredictionsStayInsideTheToolbarAcrossLayoutsAndHeights()=fixture { k,_ ->
        for(preset in KeyboardHeight.entries)for(nine in listOf(true,false)) {
            k.setHeightPreset(preset);k.setNineKey(nine);k.setMode(true);measure(k)
            val height=k.height
            val body=k.findViewWithTag<View>("keyboard_body")
            val location=IntArray(2);body.getLocationInWindow(location);val top=location[1]
            for(message in listOf("语音已结束","正在完成尾句…","识别失败","请下载离线模型","未插入的语音","记忆保存失败","AI 已纠错 · 点击撤销","任意新提示","Network error")) {
                for(compose in listOf(false,true)) {
                    if(compose)k.composition("ni hao") else k.endComposition()
                    k.setCandidates(listOf("个人词" to {}));measure(k)
                    val scroll=all(k.findViewWithTag("candidate_strip")).filterIsInstance<android.widget.HorizontalScrollView>().single()
                    val word=all(scroll).filterIsInstance<TextView>().single { it.text=="个人词" }
                    val before=IntArray(2);word.getLocationInWindow(before)
                    k.status(message);k.setPredictions(listOf("云端续写" to {}));measure(k)
                    assertEquals("$preset $nine $message",height,k.height)
                    body.getLocationInWindow(location);assertEquals(top,location[1])
                    val after=IntArray(2);all(scroll).filterIsInstance<TextView>().single { it.text=="个人词" }.getLocationInWindow(after)
                    assertArrayEquals(before,after)
                    if(compose)assertTrue(k.findViewWithTag<View>("keyboard_preedit").isShown)
                    k.setCandidates(emptyList());measure(k);assertEquals(height,k.height)
                    assertTrue(words(k.findViewWithTag("cloud_predictions")).contains("云端续写"))
                    k.setPredictions(emptyList());measure(k);assertEquals(height,k.height)
                }
            }
            k.panel("未插入的语音",listOf("重试" to {}));measure(k);assertEquals(height,k.height)
            k.setMode(false);measure(k);assertEquals(height,k.height)
            k.setMode(false,true);measure(k);assertEquals(height,k.height)
        }
    }
    @Test fun actionableFeedbackSurvivesTypingAndTimersWithoutHidingPinyin()=fixture { k,_ ->
        var retries=0
        k.status("处理未插入的语音") { retries++ }
        k.composition("ni hao");k.setCandidates(listOf("你好" to {}));k.aiStatus("连接失败");measure(k)
        val height=k.height
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(10))
        assertTrue(k.findViewWithTag<View>("keyboard_preedit").isShown)
        assertTrue(k.findViewWithTag<View>("tools").performLongClick());assertEquals(1,retries)
        k.endComposition();measure(k)
        assertEquals(height,k.height);assertTrue(k.findViewWithTag<View>("keyboard_notice").isShown)
        k.findViewWithTag<View>("keyboard_notice").performClick();assertEquals(2,retries)
        k.status("语音已结束")
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(4))
        measure(k);assertEquals(height,k.height);assertFalse(k.findViewWithTag<View>("keyboard_notice").isShown)
    }
    @Test fun predictionsDeferDuringATapAndClearAcrossVoiceTransitions()=fixture { k,_ ->
        var chosen=false
        k.setPredictions(listOf("云端词" to { chosen=true }));measure(k)
        val scroll=all(k.findViewWithTag("candidate_strip")).filterIsInstance<android.widget.HorizontalScrollView>().single()
        val word=all(scroll).filterIsInstance<TextView>().single { it.text=="云端词" }
        val rect=android.graphics.Rect(0,0,word.width,word.height);scroll.offsetDescendantRectToMyCoords(word,rect)
        fun touch(action: Int) {
            val time=SystemClock.uptimeMillis();val e=MotionEvent.obtain(time,time,action,rect.centerX().toFloat(),rect.centerY().toFloat(),0)
            try { scroll.dispatchTouchEvent(e) } finally { e.recycle() }
        }
        touch(MotionEvent.ACTION_DOWN);k.setPredictions(listOf("替换词" to {}));k.status("语音已结束");measure(k)
        assertTrue(word.isAttachedToWindow);touch(MotionEvent.ACTION_UP)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertTrue(chosen)
        assertTrue(words(scroll).contains("替换词"))
        touch(MotionEvent.ACTION_DOWN);k.setPredictions(listOf("过期词" to {}))
        k.voice(true);k.voice(false);k.status("语音已结束");k.setCandidates(listOf("个人词" to {}))
        touch(MotionEvent.ACTION_CANCEL);org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();measure(k)
        assertFalse(words(scroll).contains("过期词"));assertTrue(words(scroll).contains("个人词"))
        k.setPredictions(listOf("展开云端" to { chosen=false }));k.findViewWithTag<View>("expand_candidates").performClick();measure(k)
        all(k.findViewWithTag("expanded_candidates")).filterIsInstance<TextView>().single { it.text=="展开云端" }.performClick()
        assertFalse(chosen)
    }
    @Test fun committingAndDeletingNeverPromoteTheVoiceHintToANotice()=fixture { k,_ ->
        measure(k);val height=k.height;val top=k.findViewWithTag<View>("keyboard_body").top
        repeat(5) {
            k.composition("ni hao · 64 426");k.setCandidates(listOf("你好" to {}));measure(k)
            assertEquals(height,k.height)
            k.endComposition();k.setCandidates(listOf("个人词" to {}));measure(k)
            assertEquals(height,k.height);assertEquals(top,k.findViewWithTag<View>("keyboard_body").top)
            assertEquals(View.GONE,k.findViewWithTag<View>("keyboard_notice").visibility)
            assertEquals(View.GONE,k.findViewWithTag<View>("keyboard_preedit").visibility)
            k.status("长按空格说话，松开结束");measure(k)
            assertEquals(height,k.height);assertEquals(View.GONE,k.findViewWithTag<View>("keyboard_notice").visibility)
            k.setCandidates(emptyList());measure(k);assertEquals(height,k.height)
        }
    }
    @Test fun pinyinRemainsVisibleWhileTypingDeletingAndReceivingBackgroundErrors()=fixture { k,_ ->
        for(nine in listOf(true,false)) {
            k.setNineKey(nine);measure(k);val height=k.height
            for(input in listOf("n","ni","ni hao","ni h","ni")) {
                k.composition(input);k.setCandidates(listOf("你" to {}));k.aiStatus("连接失败");measure(k)
                val preedit=k.findViewWithTag<TextView>("keyboard_preedit")
                assertTrue(preedit.isShown);assertEquals(input,preedit.text.toString());assertEquals(height,k.height)
                val scroll=all(k.findViewWithTag("candidate_strip")).filterIsInstance<android.widget.HorizontalScrollView>().single()
                assertTrue(preedit.bottom<=scroll.top)
            }
            k.composition("unknown");k.setCandidates(emptyList());measure(k)
            assertTrue(k.findViewWithTag<View>("keyboard_preedit").isShown)
            k.endComposition();measure(k);assertEquals(height,k.height)
            k.composition("ni hao");k.setMode(false);measure(k)
            assertFalse(k.findViewWithTag<View>("keyboard_preedit").isShown)
            k.setMode(true)
        }
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
