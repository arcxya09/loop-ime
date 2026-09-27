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
            "memory","phrases" -> { canvas.drawRoundRect(4f,3f,20f,21f,2f,2f,paint);line(8f,8f,16f,8f);line(8f,12f,16f,12f);line(8f,16f,13f,16f) }
            "emoji" -> { canvas.drawCircle(12f,12f,10f,paint);line(8f,8f,8f,9f);line(16f,8f,16f,9f);canvas.drawArc(7f,10f,17f,18f,0f,180f,false,paint) }
            "edit" -> { line(4f,5f,20f,5f);line(12f,5f,12f,20f);line(8f,20f,16f,20f);line(4f,3f,4f,8f);line(20f,3f,20f,8f) }
            "keyboard","layouts" -> { canvas.drawRoundRect(2f,5f,22f,19f,2f,2f,paint);for(y in listOf(9f,12f))for(x in listOf(6f,10f,14f,18f))line(x,y,x+.5f,y);line(7f,16f,17f,16f) }
            "height" -> { line(5f,3f,5f,21f);path(2f to 6f,5f to 3f,8f to 6f);path(2f to 18f,5f to 21f,8f to 18f);canvas.drawRoundRect(12f,4f,21f,20f,1f,1f,paint) }
            "settings" -> { line(3f,7f,21f,7f);line(3f,17f,21f,17f);canvas.drawCircle(8f,7f,3f,paint);canvas.drawCircle(16f,17f,3f,paint) }
            "hand" -> { canvas.drawRoundRect(7f,2f,21f,22f,3f,3f,paint);path(7f to 13f,3f to 10f,1f to 12f,6f to 21f);line(12f,18f,16f,18f) }
            "quick" -> path(13f to 2f,5f to 13f,11f to 13f,10f to 22f,20f to 9f,13f to 9f,close=true)
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
