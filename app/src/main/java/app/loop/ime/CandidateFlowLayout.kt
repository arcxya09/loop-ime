package app.loop.ime

import android.content.Context
import android.view.View
import android.view.ViewGroup

/** Wrap words at their measured width so long names do not disappear in fixed columns. */
internal class CandidateFlowLayout(c: Context): ViewGroup(c) {
    private val gap=(4*resources.displayMetrics.density).toInt()
    override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
        val width=MeasureSpec.getSize(widthMeasureSpec)
        var x=0;var y=0;var rowHeight=0
        for(i in 0 until childCount) {
            val child=getChildAt(i)
            child.measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.AT_MOST),MeasureSpec.makeMeasureSpec(child.layoutParams.height,MeasureSpec.EXACTLY))
            if(x>0 && x+child.measuredWidth>width) { x=0;y+=rowHeight+gap;rowHeight=0 }
            x+=child.measuredWidth+gap;rowHeight=maxOf(rowHeight,child.measuredHeight)
        }
        setMeasuredDimension(width,resolveSize(y+rowHeight,heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean,l: Int,t: Int,r: Int,b: Int) {
        var x=0;var y=0;var rowHeight=0
        for(i in 0 until childCount) {
            val child=getChildAt(i)
            if(x>0 && x+child.measuredWidth>width) { x=0;y+=rowHeight+gap;rowHeight=0 }
            child.layout(x,y,x+child.measuredWidth,y+child.measuredHeight)
            x+=child.measuredWidth+gap;rowHeight=maxOf(rowHeight,child.measuredHeight)
        }
    }
}
