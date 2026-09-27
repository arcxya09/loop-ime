package app.loop.ime

import android.app.Application
import android.graphics.Insets
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real Android View layout on a host runtime; not a claim of Android 17 phone validation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[37],application=Application::class)
class KeyboardInsetsTest {
    private fun all(v: View): List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList())
    private fun measure(v: View) { v.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));v.layout(0,0,v.measuredWidth,v.measuredHeight) }
    private fun insets(nav: Int,caption: Int,left: Int=0,right: Int=0)=WindowInsets.Builder()
        .setInsets(WindowInsets.Type.navigationBars(),Insets.of(0,0,right,nav))
        .setInsets(WindowInsets.Type.captionBar(),Insets.of(0,0,0,caption))
        .setInsets(WindowInsets.Type.displayCutout(),Insets.of(left,0,0,0))
        .setInsets(WindowInsets.Type.ime(),Insets.of(0,0,0,900))
        .build()

    @Test fun captionOnlySwitcherCannotOverlapLastRow() {
        val keyboard=KeyboardView(RuntimeEnvironment.getApplication()) {}
        measure(keyboard);val initial=keyboard.measuredHeight
        keyboard.dispatchApplyWindowInsets(insets(0,96));measure(keyboard)
        assertEquals(initial+96,keyboard.measuredHeight)
        for(v in all(keyboard).filter { it.isClickable }) {
            val rect=Rect(0,0,v.width,v.height);keyboard.offsetDescendantRectToMyCoords(v,rect)
            assertTrue("Clickable key overlaps switcher strip",rect.bottom<=keyboard.height-96)
        }
    }
    @Test fun gestureCaptionAndThreeButtonAreasUseMaximumAndDoNotAccumulate() {
        val keyboard=KeyboardView(RuntimeEnvironment.getApplication()) {}
        measure(keyboard);val initial=keyboard.measuredHeight
        repeat(3) { keyboard.dispatchApplyWindowInsets(insets(48,96));measure(keyboard);assertEquals(initial+96,keyboard.measuredHeight) }
        keyboard.dispatchApplyWindowInsets(insets(144,0));measure(keyboard);assertEquals(initial+144,keyboard.measuredHeight)
        keyboard.dispatchApplyWindowInsets(insets(0,0));measure(keyboard);assertEquals(initial,keyboard.measuredHeight)
    }
    @Test fun landscapeSideBarsAndVoicePanelRespectSafeArea() {
        val keyboard=KeyboardView(RuntimeEnvironment.getApplication()) {}
        val left=keyboard.paddingLeft;val right=keyboard.paddingRight
        keyboard.dispatchApplyWindowInsets(insets(0,96,80,100))
        assertEquals(left+80,keyboard.paddingLeft);assertEquals(right+100,keyboard.paddingRight)
        val bottom=keyboard.paddingBottom
        keyboard.voice(true);measure(keyboard);assertEquals(bottom,keyboard.paddingBottom)
        keyboard.panel("clipboard",emptyList());measure(keyboard);assertEquals(bottom,keyboard.paddingBottom)
    }
    @Test fun defaultDeepSeekPageHasOnlyOneEditableConfigurationField() {
        val controller=Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            val activity=controller.get()
            all(activity.window.decorView).filterIsInstance<Button>().single { it.text.toString()=="AI 连接与实时纠错" }.performClick()
            val fields=all(activity.window.decorView).filterIsInstance<EditText>()
            assertEquals(1,fields.size);assertEquals(R.id.api_key,fields.single().id)
            assertEquals("保存 Key 并测试连接",activity.findViewById<Button>(R.id.api_test_button).text.toString())
        } finally { controller.pause().stop().destroy() }
    }
}
