package app.loop.ime

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

/** Defers the tap until release so an upward swipe never deletes a character first. */
internal class DeleteGesture(private val view: View,private val emit: (String)->Unit,private val hint: (String?)->Unit) : View.OnTouchListener, View.OnAttachStateChangeListener {
    private var tracking=false
    private var moved=false
    private var armed=false
    private var repeated=false
    private var x=0f
    private var y=0f
    private val slop=ViewConfiguration.get(view.context).scaledTouchSlop
    private val threshold=36*view.resources.displayMetrics.density
    private val repeat=object: Runnable {
        override fun run() { if(tracking && !moved) { repeated=true;emit("delete");view.postDelayed(this,65) } }
    }
    init { view.setOnTouchListener(this);view.addOnAttachStateChangeListener(this) }
    fun cancel() { tracking=false;armed=false;view.isPressed=false;view.removeCallbacks(repeat);hint(null) }
    override fun onTouch(v: View,e: MotionEvent): Boolean {
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancel();tracking=true;moved=false;repeated=false;x=e.x;y=e.y;view.isPressed=true
                hint("上滑清空");view.parent?.requestDisallowInterceptTouchEvent(true);view.postDelayed(repeat,380)
            }
            MotionEvent.ACTION_MOVE -> if(tracking) {
                val dx=kotlin.math.abs(e.x-x);val up=y-e.y
                if(dx>slop || kotlin.math.abs(up)>slop) { moved=true;view.removeCallbacks(repeat) }
                armed=up>=threshold && up>dx
                hint(if(armed)"松开清空" else "上滑清空")
            }
            MotionEvent.ACTION_UP -> {
                val clear=tracking && armed && y-e.y>=threshold && y-e.y>kotlin.math.abs(e.x-x)
                val tap=tracking && !moved && !repeated && kotlin.math.abs(e.x-x)<=slop && kotlin.math.abs(e.y-y)<=slop
                cancel();if(clear)emit("delete_to_start") else if(tap)view.performClick()
            }
            MotionEvent.ACTION_CANCEL,MotionEvent.ACTION_POINTER_DOWN -> cancel()
        }
        return true
    }
    override fun onViewAttachedToWindow(v: View) {}
    override fun onViewDetachedFromWindow(v: View)=cancel()
}
