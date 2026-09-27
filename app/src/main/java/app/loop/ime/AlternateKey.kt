package app.loop.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.TextView

/** A consumed long press inserts exactly one alternate; cancellation never falls back to a tap. */
class AlternateKey(c: Context,private val alternate: String,private val insert: ()->Unit): TextView(c) {
    private val corner=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=0xff7b8590.toInt();textAlign=Paint.Align.RIGHT;textSize=9f*resources.displayMetrics.density }
    private val slop=ViewConfiguration.get(c).scaledTouchSlop
    private var tracking=false
    private var pointer=-1
    private var consumed=false
    private val hold=Runnable { if(tracking && !consumed) { consumed=true;isPressed=false;performLongClick() } }
    init { isClickable=true;isLongClickable=true }
    override fun onDraw(canvas: Canvas) { super.onDraw(canvas);val d=resources.displayMetrics.density;canvas.drawText(alternate,width-6*d,11*d,corner) }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { cancel();tracking=true;pointer=e.getPointerId(0);isPressed=true;postDelayed(hold,ViewConfiguration.getLongPressTimeout().toLong()) }
            MotionEvent.ACTION_MOVE -> if(tracking) {
                val i=e.findPointerIndex(pointer)
                if(i<0 || e.getX(i)<-slop || e.getY(i)<-slop || e.getX(i)>width+slop || e.getY(i)>height+slop)cancel()
            }
            MotionEvent.ACTION_POINTER_DOWN,MotionEvent.ACTION_CANCEL -> cancel()
            MotionEvent.ACTION_POINTER_UP -> if(e.getPointerId(e.actionIndex)==pointer)cancel()
            MotionEvent.ACTION_UP -> {
                val click=tracking && !consumed && e.getPointerId(e.actionIndex)==pointer
                cancel();if(click)performClick()
            }
        }
        return true
    }
    private fun cancel() { removeCallbacks(hold);tracking=false;pointer=-1;consumed=false;isPressed=false }
    override fun performLongClick(): Boolean { consumed=true;performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);insert();return true }
    override fun performClick(): Boolean=super.performClick()
    override fun onDetachedFromWindow() { cancel();super.onDetachedFromWindow() }
}
