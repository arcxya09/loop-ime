package app.loop.ime

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.TextView

/** Owns the pointer until UP/CANCEL, even while voice preparation updates the rest of the keyboard. */
class HoldSpaceKey(c: Context, private val hold: (Boolean)->Unit,
    private val gestureFinished: ()->Unit, private val accessibleVoice: ()->Unit,
    private val cursorMove: (Boolean)->Unit = {},private val cursorEnabled: ()->Boolean = { false }) : TextView(c) {
    var tracking=false; private set
    var holding=false; private set
    private var pointer=-1
    private var tapAllowed=false
    private var originX=0f
    private var originY=0f
    private var lastX=0f
    private var sliding=false
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
                originX=e.x;originY=e.y;lastX=e.x;sliding=false
                // Faster voice activation with the normal system setting; respect longer accessibility delays.
                val systemDelay=ViewConfiguration.getLongPressTimeout()
                postDelayed(startHold,if(systemDelay<=500)250L else systemDelay.toLong())
            }
            MotionEvent.ACTION_MOVE -> if(tracking && !holding) {
                val i=e.findPointerIndex(pointer)
                if(i>=0 && cursorEnabled() && kotlin.math.abs(e.getY(i)-originY)<24*resources.displayMetrics.density) {
                    val x=e.getX(i);val step=18*resources.displayMetrics.density
                    if(!sliding && kotlin.math.abs(x-originX)>28*resources.displayMetrics.density) { sliding=true;tapAllowed=false;removeCallbacks(startHold);text="移动光标" }
                    if(sliding) { while(kotlin.math.abs(x-lastX)>=step) { val left=x<lastX;cursorMove(left);lastX+=if(left)-step else step };return true }
                }
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
        tracking=false;holding=false;tapAllowed=false;pointer=-1;isPressed=false;sliding=false
        text="按住说话"
        if(wasHolding)hold(false)
        if(render)gestureFinished()
    }
    override fun performClick(): Boolean = super.performClick()
    // TalkBack has no held pointer to release. Its long-click uses the existing tap-to-toggle mic.
    override fun performLongClick(): Boolean { accessibleVoice();return true }
    override fun onDetachedFromWindow() { if(tracking)finishGesture(false);removeCallbacks(startHold);super.onDetachedFromWindow() }
}
