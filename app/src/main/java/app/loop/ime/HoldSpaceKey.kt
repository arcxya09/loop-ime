package app.loop.ime

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.TextView

/** Owns the pointer until UP/CANCEL, even while voice preparation updates the rest of the keyboard. */
class HoldSpaceKey(c: Context, private val hold: (Boolean)->Unit,
    private val gestureFinished: ()->Unit, private val accessibleVoice: ()->Unit) : TextView(c) {
    var tracking=false; private set
    var holding=false; private set
    private var pointer=-1
    private var tapAllowed=false
    private val slop=ViewConfiguration.get(c).scaledTouchSlop
    private val startHold=Runnable {
        if(tracking && tapAllowed) {
            holding=true;tapAllowed=false
            text="松开结束";performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            hold(true)
        }
    }
    init { tag="space";isClickable=true;isLongClickable=true;contentDescription="空格，长按说话，松开结束" }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelGesture();tracking=true;pointer=e.getPointerId(0);tapAllowed=true;isPressed=true
                // Faster voice activation with the normal system setting; respect longer accessibility delays.
                val systemDelay=ViewConfiguration.getLongPressTimeout()
                postDelayed(startHold,if(systemDelay<=500)250L else systemDelay.toLong())
            }
            MotionEvent.ACTION_MOVE -> if(tracking && !holding) {
                val i=e.findPointerIndex(pointer)
                if(i<0 || e.getX(i)<-slop || e.getY(i)<-slop || e.getX(i)>width+slop || e.getY(i)>height+slop) {
                    tapAllowed=false;isPressed=false;removeCallbacks(startHold)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN,MotionEvent.ACTION_CANCEL -> cancelGesture()
            MotionEvent.ACTION_POINTER_UP -> if(e.getPointerId(e.actionIndex)==pointer)cancelGesture()
            MotionEvent.ACTION_UP -> if(tracking) {
                val click=tapAllowed && !holding && e.getPointerId(e.actionIndex)==pointer
                finishGesture()
                if(click)performClick()
            }
        }
        return true
    }
    fun cancelGesture() { if(tracking)finishGesture() else removeCallbacks(startHold) }
    private fun finishGesture(render: Boolean=true) {
        removeCallbacks(startHold)
        val wasHolding=holding
        tracking=false;holding=false;tapAllowed=false;pointer=-1;isPressed=false
        text="按住说话"
        if(wasHolding)hold(false)
        if(render)gestureFinished()
    }
    override fun performClick(): Boolean = super.performClick()
    // TalkBack has no held pointer to release. Its long-click uses the existing tap-to-toggle mic.
    override fun performLongClick(): Boolean { accessibleVoice();return true }
    override fun onDetachedFromWindow() { if(tracking)finishGesture(false);removeCallbacks(startHold);super.onDetachedFromWindow() }
}
