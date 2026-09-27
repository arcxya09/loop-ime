package app.loop.ime

import android.content.Context
import android.content.res.Configuration

object UiPalette {
    fun dark(c: Context)=c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES
    fun surface(c: Context)=if(dark(c))0xff20282b.toInt() else ImeAppearance.SURFACE
    fun card(c: Context)=if(dark(c))0xff303b40.toInt() else 0xffffffff.toInt()
    fun ink(c: Context)=if(dark(c))0xffe5ece8.toInt() else 0xff343d48.toInt()
    fun muted(c: Context)=if(dark(c))0xffb0bdb9.toInt() else 0xff697780.toInt()
    fun green(c: Context)=if(dark(c))0xff8bd4b0.toInt() else 0xff286952.toInt()
    fun mint(c: Context)=if(dark(c))0xff294d3e.toInt() else 0xffdfece5.toInt()
    fun secondary(c: Context)=if(dark(c))0xff39454b.toInt() else 0xffe0e5ea.toInt()
}
