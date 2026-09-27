package app.loop.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Small vector glyphs with touch targets independent of the system text size. */
class KeyboardIcon(c: Context,var glyph: String) : View(c) {
    var tint: Int=0xff46515e.toInt()
        set(value) { field=value;invalidate() }
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style=Paint.Style.STROKE;strokeWidth=1.65f;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND
    }
    init { isClickable=true;isFocusable=false }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size=20f*resources.displayMetrics.density
        canvas.save();canvas.translate((width-size)/2f,(height-size)/2f);canvas.scale(size/24f,size/24f)
        paint.color=tint;paint.style=Paint.Style.STROKE
        fun line(x1: Float,y1: Float,x2: Float,y2: Float)=canvas.drawLine(x1,y1,x2,y2,paint)
        fun path(vararg points: Pair<Float,Float>,close: Boolean=false) {
            val p=Path();points.forEachIndexed { i,(x,y) -> if(i==0)p.moveTo(x,y) else p.lineTo(x,y) };if(close)p.close();canvas.drawPath(p,paint)
        }
        when(glyph) {
            "tools" -> {
                val p=Path().apply { moveTo(12f,12f);cubicTo(8f,5f,2f,5f,2f,12f);cubicTo(2f,19f,8f,19f,12f,12f);cubicTo(16f,5f,22f,5f,22f,12f);cubicTo(22f,19f,16f,19f,12f,12f) };canvas.drawPath(p,paint)
            }
            "ai_settings" -> {
                path(10f to 3f,12.5f to 9.5f,19f to 12f,12.5f to 14.5f,10f to 21f,7.5f to 14.5f,1f to 12f,7.5f to 9.5f,close=true)
                line(19f,2f,19f,7f);line(16.5f,4.5f,21.5f,4.5f)
            }
            "mic" -> {
                canvas.drawRoundRect(9f,2f,15f,14f,3f,3f,paint)
                canvas.drawArc(6f,6f,18f,18f,0f,180f,false,paint)
                line(12f,18f,12f,22f);line(8.5f,22f,15.5f,22f)
            }
            "clipboard" -> {
                path(8f to 5f,5f to 5f,5f to 22f,19f to 22f,19f to 5f,16f to 5f)
                canvas.drawRoundRect(8f,2f,16f,7f,1.5f,1.5f,paint);line(9f,12f,15f,12f);line(9f,16f,14f,16f)
            }
            "hide", "expand_candidates" -> path(6f to 9f,12f to 15f,18f to 9f)
            "collapse_candidates" -> path(6f to 15f,12f to 9f,18f to 15f)
            "delete" -> {
                path(9f to 5f,21f to 5f,21f to 19f,9f to 19f,2f to 12f,close=true)
                line(12f,9f,17f,15f);line(17f,9f,12f,15f)
            }
            "stop" -> { paint.style=Paint.Style.FILL;canvas.drawRoundRect(6f,6f,18f,18f,2f,2f,paint) }
            "cancel" -> { line(6f,6f,18f,18f);line(18f,6f,6f,18f) }
        }
        canvas.restore()
    }
}
