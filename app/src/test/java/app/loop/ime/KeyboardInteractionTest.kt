package app.loop.ime

import android.app.Activity
import android.app.Application
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.os.SystemClock
import android.view.*
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[37],application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class KeyboardInteractionTest {
    private fun all(v: View): List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList())
    private fun measure(v: View) { v.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));v.layout(0,0,v.measuredWidth,v.measuredHeight) }
    private fun preview(k: View,name: String) {
        val bitmap=Bitmap.createBitmap(k.width,k.height,Bitmap.Config.ARGB_8888);k.draw(Canvas(bitmap))
        val file=File("build/$name.png");file.parentFile!!.mkdirs()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) };bitmap.recycle()
    }
    private fun touch(v: View,action: Int,x: Float=v.width/2f,y: Float=v.height/2f) {
        val now=SystemClock.uptimeMillis();val e=MotionEvent.obtain(now,now,action,x,y,0)
        try { v.dispatchTouchEvent(e) } finally { e.recycle() }
    }
    private fun waitHold() { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout()+1L)) }
    private fun withKeyboard(body: (KeyboardView,MutableList<String>)->Unit) {
        val c=Robolectric.buildActivity(Activity::class.java).setup().visible()
        val events=mutableListOf<String>();lateinit var keyboard: KeyboardView
        keyboard=KeyboardView(c.get()) { action ->
            events+=action
            if(action=="voice_hold_start")keyboard.voice(true)
            if(action=="voice_hold_end")keyboard.voice(false)
        }
        try { c.get().setContentView(keyboard);measure(keyboard);body(keyboard,events) }
        finally { c.pause().stop().destroy() }
    }
    @Test fun chineseStartsWithNineKeysAndEnglishKeepsQwerty()=withKeyboard { k,events ->
        for(n in 2..9)assertNotNull(all(k).find { it.tag=="t9:$n" })
        assertNull(all(k).find { it.tag=="q" })
        all(k).single { it.tag=="t9:6" }.performClick();assertEquals(listOf("t9:6"),events)
        k.setMode(false);assertNotNull(all(k).find { it.tag=="q" });assertNull(all(k).find { it.tag=="t9:6" })
        k.setMode(true);assertNotNull(all(k).find { it.tag=="t9:6" })
        k.setNineKey(false);assertNotNull(all(k).find { it.tag=="q" })
        k.setMode(false,true);all(k).single { it.tag=="6" }.performClick();assertEquals("6",events.last())
    }
    @Test fun quickTapInsertsOneSpaceWithoutStartingSpeech()=withKeyboard { k,events ->
        val s=all(k).filterIsInstance<HoldSpaceKey>().single()
        touch(s,MotionEvent.ACTION_DOWN);touch(s,MotionEvent.ACTION_UP);waitHold()
        assertEquals(listOf("space"),events)
    }
    @Test fun longPressDigitsAndEnglishAlternatesInsertOnceWithoutTheNormalKey()=withKeyboard { k,events ->
        for(n in 0..9) {
            val tag=when(n) { 0->"numbers";1->"punctuation";else->"t9:$n" }
            val v=k.findViewWithTag<View>(tag);events.clear()
            touch(v,MotionEvent.ACTION_DOWN);waitHold();touch(v,MotionEvent.ACTION_UP)
            assertEquals(listOf("literal:$n"),events)
        }
        k.setMode(false);measure(k)
        val letters="qwertyuiopasdfghjklzxcvbnm";val extras="1234567890@#$%&*()-!\"':;?/"
        letters.forEachIndexed { i,c ->
            val v=k.findViewWithTag<View>(c.toString());events.clear()
            touch(v,MotionEvent.ACTION_DOWN);touch(v,MotionEvent.ACTION_UP);waitHold();assertEquals(listOf(c.toString()),events)
            events.clear();touch(v,MotionEvent.ACTION_DOWN);waitHold();touch(v,MotionEvent.ACTION_UP);waitHold()
            assertEquals(listOf("literal:${extras[i]}"),events)
        }
        events.clear();k.findViewWithTag<View>("shift").performClick();measure(k)
        k.findViewWithTag<View>("Q").performLongClick();assertEquals(listOf("literal:1"),events)
    }
    @Test fun alternateGesturesCancelOnMovementMultitouchAndDetach()=withKeyboard { k,events ->
        val v=k.findViewWithTag<View>("t9:2")
        for(cancel in listOf(MotionEvent.ACTION_CANCEL,MotionEvent.ACTION_POINTER_DOWN)) {
            touch(v,MotionEvent.ACTION_DOWN);touch(v,cancel);waitHold();touch(v,MotionEvent.ACTION_UP)
        }
        touch(v,MotionEvent.ACTION_DOWN);touch(v,MotionEvent.ACTION_MOVE,-1000f);waitHold();touch(v,MotionEvent.ACTION_UP)
        touch(v,MotionEvent.ACTION_DOWN);k.setMode(false);waitHold();touch(v,MotionEvent.ACTION_UP)
        assertTrue(events.isEmpty())
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers="zh-rCN-w360dp-h800dp-xxhdpi")
    fun allLayoutsShareExactKeyBoundsAndEnglishSwitchFollowsPeriod()=withKeyboard { k,_ ->
        fun bounds(tag: String): Rect {
            val v=k.findViewWithTag<View>(tag);return Rect(0,0,v.width,v.height).also { k.offsetDescendantRectToMyCoords(v,it) }
        }
        for(preset in KeyboardHeight.entries) {
            k.setHeightPreset(preset);k.setMode(true);measure(k)
            val height=k.height;val top=bounds("t9:2").top;val bottom=bounds("space").bottom
            for(sym in listOf(false,true)) {
                k.setMode(false,sym);measure(k)
                assertEquals(height,k.height);assertEquals(top,bounds(if(sym)"1" else "q").top);assertEquals(bottom,bounds("space").bottom)
                assertTrue(bounds("language").left>=bounds("period").right)
            }
        }
        k.setHeightPreset(KeyboardHeight.HIGH);k.setMode(false);measure(k);preview(k,"keyboard-alpha21-english")
        k.setMode(true);k.composition("ni hao");k.setCandidates(listOf("你好" to {},"你们" to {},"您好" to {},"你好呀" to {},"拟好" to {}));measure(k)
        val toolbar=k.findViewWithTag<ViewGroup>("keyboard_toolbar");val strip=k.findViewWithTag<View>("candidate_strip")
        assertEquals(toolbar.width,strip.width);assertEquals(0,strip.left)
        for(i in 0 until toolbar.childCount)if(toolbar.getChildAt(i)!==strip)assertEquals(View.GONE,toolbar.getChildAt(i).visibility)
        preview(k,"keyboard-alpha21-full-width-candidates")
        val candidate=all(strip).filterIsInstance<TextView>().single { it.text=="你好" }
        candidate.performLongClick()
        val menu=org.robolectric.shadows.ShadowPopupMenu.getLatestPopupMenu()
        assertNotNull(menu);assertTrue((0 until menu.menu.size()).any { menu.menu.getItem(it).title=="展开候选" })
        menu.dismiss();k.endComposition();k.setCandidates(emptyList());measure(k)
        assertTrue(k.findViewWithTag<View>("tools").isShown);assertTrue(strip.width<toolbar.width)
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers="zh-rCN-w360dp-h800dp-xxhdpi")
    fun composingToolbarShowsPinyinAndWordsAtTheSameTotalHeight()=withKeyboard { k,_ ->
        val before=k.height
        k.composition("ni hao · 64 426");k.setCandidates(listOf("你" to {},"你好" to {},"你好呀" to {},"拟好" to {}),setOf("你好","你好呀"));measure(k)
        val strip=all(k).single { it.tag=="candidate_strip" }
        val shown=all(strip).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }
        assertEquals(listOf("ni hao · 64 426","你","你好","你好呀","拟好"),shown)
        assertEquals(before,k.height);assertEquals(44*3,strip.height)
        preview(k,"keyboard-alpha19-pinyin-candidates")
        k.setCandidates(emptyList());measure(k)
        assertTrue(k.findViewWithTag<View>("keyboard_preedit").isShown)
        k.endComposition();measure(k)
        assertFalse(k.findViewWithTag<View>("keyboard_preedit").isShown);assertEquals(before,k.height)
    }
    @Test fun lateAiUpdateDoesNotMoveTheWordUnderAnActiveFinger()=withKeyboard { k,_ ->
        val clicked=mutableListOf<String>()
        k.composition("64426");k.setCandidates(listOf("你" to { clicked+="你" },"拟好" to { clicked+="拟好" }));measure(k)
        val scroll=all(k.findViewWithTag("candidate_strip")).filterIsInstance<android.widget.HorizontalScrollView>().single()
        val second=all(scroll).filterIsInstance<TextView>().single { it.text=="拟好" }
        val r=Rect(0,0,second.width,second.height);scroll.offsetDescendantRectToMyCoords(second,r)
        touch(scroll,MotionEvent.ACTION_DOWN,r.centerX().toFloat(),r.centerY().toFloat())
        k.setCandidates(listOf("你" to {},"你好" to { clicked+="你好" },"拟好" to {}),setOf("你好"))
        assertTrue(second.isAttachedToWindow)
        touch(scroll,MotionEvent.ACTION_UP,r.centerX().toFloat(),r.centerY().toFloat());shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("拟好"),clicked)
        assertFalse(second.isAttachedToWindow)
    }
    @Test fun speechActivatesAt250MillisecondsWhileShorterPressStillInsertsSpace()=withKeyboard { k,events ->
        val s=all(k).filterIsInstance<HoldSpaceKey>().single()
        touch(s,MotionEvent.ACTION_DOWN);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(249))
        assertTrue(events.isEmpty());touch(s,MotionEvent.ACTION_UP)
        assertEquals(listOf("space"),events);events.clear()
        touch(s,MotionEvent.ACTION_DOWN);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        assertEquals(listOf("voice_hold_start"),events);touch(s,MotionEvent.ACTION_UP)
        assertEquals(listOf("voice_hold_start","voice_hold_end"),events)
    }
    @Test fun heldSpaceSurvivesVoiceRenderAndEndsExactlyOnceOnRelease()=withKeyboard { k,events ->
        val s=all(k).filterIsInstance<HoldSpaceKey>().single()
        touch(s,MotionEvent.ACTION_DOWN);waitHold()
        assertEquals(listOf("voice_hold_start"),events);assertTrue(s.isAttachedToWindow)
        k.setEnter("发送");k.status("正在加载模型");k.setCandidates(emptyList())
        assertTrue(s.isAttachedToWindow);assertTrue(s.holding)
        touch(s,MotionEvent.ACTION_UP);waitHold()
        assertEquals(listOf("voice_hold_start","voice_hold_end"),events)
        assertFalse(s.holding);assertFalse(s.isAttachedToWindow)
        assertEquals(1,all(k).filterIsInstance<HoldSpaceKey>().size)
    }
    @Test fun cancelStopsRecordingAndDoesNotInsertASpaceOnLaterUp()=withKeyboard { k,events ->
        val s=all(k).filterIsInstance<HoldSpaceKey>().single()
        touch(s,MotionEvent.ACTION_DOWN);waitHold();touch(s,MotionEvent.ACTION_CANCEL);touch(s,MotionEvent.ACTION_UP)
        assertEquals(listOf("voice_hold_start","voice_hold_end"),events)
    }
    @Test fun detachedKeyStopsRecording()=withKeyboard { k,events ->
        val s=all(k).filterIsInstance<HoldSpaceKey>().single()
        touch(s,MotionEvent.ACTION_DOWN);waitHold();(s.parent as ViewGroup).removeView(s)
        assertEquals(listOf("voice_hold_start","voice_hold_end"),events)
    }
    @Test fun movementBeforeThresholdCancelsTapButMovementWhileHoldingStillEndsOnUp()=withKeyboard { k,events ->
        var s=all(k).filterIsInstance<HoldSpaceKey>().single()
        touch(s,MotionEvent.ACTION_DOWN);touch(s,MotionEvent.ACTION_MOVE,-1000f);waitHold();touch(s,MotionEvent.ACTION_UP)
        assertTrue(events.isEmpty())
        s=all(k).filterIsInstance<HoldSpaceKey>().single()
        touch(s,MotionEvent.ACTION_DOWN);waitHold();touch(s,MotionEvent.ACTION_MOVE,-1000f);touch(s,MotionEvent.ACTION_UP,-1000f)
        assertEquals(listOf("voice_hold_start","voice_hold_end"),events)
    }
    @Test fun secondFingerStopsHoldWithoutActivatingAnotherKey()=withKeyboard { k,events ->
        val s=all(k).filterIsInstance<HoldSpaceKey>().single()
        val r=Rect(0,0,s.width,s.height);k.offsetDescendantRectToMyCoords(s,r)
        touch(k,MotionEvent.ACTION_DOWN,r.centerX().toFloat(),r.centerY().toFloat());waitHold()
        val props=Array(2) { MotionEvent.PointerProperties().apply { id=it;toolType=MotionEvent.TOOL_TYPE_FINGER } }
        val coords=Array(2) { MotionEvent.PointerCoords().apply { x=if(it==0)r.centerX().toFloat() else 30f;y=if(it==0)r.centerY().toFloat() else 30f;pressure=1f;size=1f } }
        val now=SystemClock.uptimeMillis()
        val event=MotionEvent.obtain(now,now,MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,props,coords,0,0,1f,1f,0,0,0,0)
        try { k.dispatchTouchEvent(event) } finally { event.recycle() }
        touch(k,MotionEvent.ACTION_UP,r.centerX().toFloat(),r.centerY().toFloat())
        assertEquals(listOf("voice_hold_start","voice_hold_end"),events)
    }
    @Test fun lightImeThemeAndSystemStripUseKeyboardSurface() {
        val c=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val themed=ContextThemeWrapper(c.get(),R.style.LoopImeTheme)
            val attrs=themed.obtainStyledAttributes(intArrayOf(android.R.attr.navigationBarColor,android.R.attr.windowLightNavigationBar,android.R.attr.enforceNavigationBarContrast))
            assertEquals(ImeAppearance.SURFACE,attrs.getColor(0,0));assertTrue(attrs.getBoolean(1,false));assertFalse(attrs.getBoolean(2,true));attrs.recycle()
            ImeAppearance.apply(c.get().window)
            assertFalse(c.get().window.isNavigationBarContrastEnforced)
            assertTrue(c.get().window.insetsController!!.systemBarsAppearance and WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS != 0)
            assertEquals(ImeAppearance.SURFACE,(KeyboardView(themed){}.background as ColorDrawable).color)
        } finally { c.pause().stop().destroy() }
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers="zh-rCN-w360dp-h800dp-xxhdpi")
    fun referenceNineKeyGeometryHasPunctuationColumnAndTallEnter()=withKeyboard { k,_ ->
        fun bounds(tag: String): Rect {
            val v=all(k).single { it.tag==tag };return Rect(0,0,v.width,v.height).also { k.offsetDescendantRectToMyCoords(v,it) }
        }
        val first=bounds("punctuation");val abc=bounds("t9:2");val ghi=bounds("t9:4");val pqrs=bounds("t9:7")
        val left=bounds("punctuation_column");val delete=bounds("delete");val retype=bounds("retype");val enter=bounds("enter")
        assertTrue(left.right<first.left);assertEquals(first.top,abc.top);assertTrue(first.right<abc.left)
        assertEquals(first.left,ghi.left);assertEquals(first.left,pqrs.left)
        assertEquals(first.top,delete.top);assertEquals(ghi.top,retype.top);assertEquals(pqrs.top,enter.top)
        assertEquals(bounds("language").bottom,enter.bottom);assertTrue(enter.height()>delete.height()*2)
        assertEquals(bounds("symbols").bottom,enter.bottom)
        assertNull(all(k).find { it.tag=="switch_ime" })
        assertEquals(enter.bottom,k.height-k.paddingBottom)
        assertTrue(k.height/k.resources.displayMetrics.density<=270f)
        val toolbar=all(k).single { it.tag=="keyboard_toolbar" }
        val strip=all(k).single { it.tag=="candidate_strip" }
        assertEquals(toolbar.height,strip.height)
        assertEquals(toolbar.bottom+(4*k.resources.displayMetrics.density).toInt(),first.top)
        all(k).single { it.tag=="retype" }.performClick()
        k.status("简体九宫格 · 长按空格说话");k.setCandidates(listOf("你好" to {},"你们" to {},"呢" to {}));measure(k)
        preview(k,"keyboard-alpha8")
        val notice=all(k).single { it.tag=="keyboard_notice" } as TextView
        k.status("记忆保存失败：存储空间不足")
        assertEquals(View.VISIBLE,notice.visibility)
        assertTrue(notice.text.contains("记忆保存失败"))
        assertTrue(all(k.findViewWithTag("candidate_strip")).filterIsInstance<TextView>().any { it.isShown && it.text=="你好" })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3501))
        assertEquals(View.GONE,notice.visibility)
        k.status("简体九宫格 · 长按空格说话")
        k.setCandidates(emptyList());measure(k);preview(k,"keyboard-alpha8-idle")
        k.setMode(false);k.status("英文 · 长按空格说话");measure(k);preview(k,"keyboard-alpha8-english")
        k.setMode(true);k.voice(true);k.status("正在听 · 百炼云端");measure(k);preview(k,"keyboard-alpha8-voice")
        val voiceHeight=k.height
        k.voice(false);k.status("语音已结束");k.setCandidates(listOf("你好" to {}));k.setPredictions(listOf("世界" to {}));measure(k)
        assertEquals(voiceHeight,k.height);preview(k,"keyboard-alpha20-voice-finished")
    }
    @Test fun voiceKeepsTheSameSpaceAndKeyboardHeightForToolbarAndHold()=withKeyboard { k,events ->
        val space=all(k).filterIsInstance<HoldSpaceKey>().single();val height=k.height
        k.voice(true);measure(k)
        assertSame(space,all(k).filterIsInstance<HoldSpaceKey>().single());assertEquals(height,k.height)
        k.voice(false);measure(k)
        touch(space,MotionEvent.ACTION_DOWN);waitHold();measure(k)
        assertSame(space,all(k).filterIsInstance<HoldSpaceKey>().single());assertEquals(height,k.height)
        touch(space,MotionEvent.ACTION_UP);measure(k)
        assertSame(space,all(k).filterIsInstance<HoldSpaceKey>().single());assertEquals(height,k.height)
        assertEquals(listOf("voice_hold_start","voice_hold_end"),events)
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers="zh-rCN-w360dp-h800dp-xxhdpi")
    fun threePersistedHeightsScaleKeysAndPanelsWithOneFixedToolbar()=withKeyboard { k,_ ->
        val density=k.resources.displayMetrics.density
        val prefs=Prefs(k.context)
        assertEquals(KeyboardHeight.HIGH,prefs.keyboardHeight)
        assertEquals(KeyboardHeight.HIGH,KeyboardHeight.from("unknown-old-value"))
        val oldHeight=k.height
        for(preset in KeyboardHeight.entries) {
            prefs.keyboardHeight=preset
            k.setHeightPreset(Prefs(k.context).keyboardHeight);k.setMode(true)
            k.status("简体九宫格 · 长按空格说话");measure(k)
            assertEquals(((preset.padDp+58)*density).toInt(),k.height)
            assertEquals((44*density).toInt(),all(k).single { it.tag=="keyboard_toolbar" }.height)
            assertTrue(all(k).single { it.tag=="space" }.height>=36*density)
            val enter=all(k).single { it.tag=="enter" };val rect=Rect(0,0,enter.width,enter.height)
            k.offsetDescendantRectToMyCoords(enter,rect);assertEquals(k.height-k.paddingBottom,rect.bottom)
            assertNull(all(k).find { it.tag=="switch_ime" })
            preview(k,"keyboard-alpha8-${preset.value}")
            k.setMode(false);measure(k);assertEquals(((preset.padDp+58)*density).toInt(),k.height)
            k.setMode(false,true);measure(k);assertEquals(((preset.padDp+58)*density).toInt(),k.height)
            k.panel("高度",listOf("高" to {},"中" to {},"低" to {}));measure(k)
            assertEquals(((preset.padDp+58)*density).toInt(),k.height)
        }
        prefs.keyboardHeight=KeyboardHeight.HIGH;k.setHeightPreset(prefs.keyboardHeight);k.setMode(true);measure(k)
        assertEquals(oldHeight,k.height)
    }
    @Test fun changingHeightDuringAHoldDefersLayoutUntilRelease()=withKeyboard { k,events ->
        val s=all(k).filterIsInstance<HoldSpaceKey>().single();val before=k.height
        touch(s,MotionEvent.ACTION_DOWN);waitHold();k.setHeightPreset(KeyboardHeight.LOW);measure(k)
        assertSame(s,all(k).filterIsInstance<HoldSpaceKey>().single());assertEquals(before,k.height)
        touch(s,MotionEvent.ACTION_UP);measure(k)
        assertTrue(k.height<before);assertEquals(listOf("voice_hold_start","voice_hold_end"),events)
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers="zh-rCN-w320dp-h800dp-xxhdpi")
    fun largeSystemFontKeepsLanguageAndEditorActionsOnOneLineWithoutASecondSwitcher() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val c=controller.get().createConfigurationContext(Configuration(controller.get().resources.configuration).apply { fontScale=1.6f })
            val keyboard=KeyboardView(c) {};keyboard.setEnter("下一项");keyboard.status("简体九宫格 · 长按空格说话")
            controller.get().setContentView(keyboard)
            for(preset in KeyboardHeight.entries) {
                keyboard.setHeightPreset(preset)
                keyboard.measure(View.MeasureSpec.makeMeasureSpec(960,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
                keyboard.layout(0,0,keyboard.measuredWidth,keyboard.measuredHeight)
                for(tag in listOf("language","enter","retype","t9:9","space")) {
                    val label=all(keyboard).single { it.tag==tag } as TextView
                    assertEquals("$preset $tag wraps",1,label.layout.lineCount)
                    assertEquals("$preset $tag is clipped",0,label.layout.getEllipsisCount(0))
                    assertTrue(label.paint.measureText(label.text.toString())<=label.width-label.paddingLeft-label.paddingRight)
                    assertTrue("$preset $tag clipped vertically",label.layout.height<=label.height-label.paddingTop-label.paddingBottom)
                }
                preview(keyboard,"keyboard-alpha8-large-font-${preset.value}")
            }
            assertNull(all(keyboard).find { it.tag=="switch_ime" })
        } finally { controller.pause().stop().destroy() }
    }
}
