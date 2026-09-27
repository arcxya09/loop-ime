package app.loop.ime

import android.graphics.Color
import android.view.Window
import android.view.WindowInsetsController
import android.view.WindowManager

object ImeAppearance {
    const val SURFACE: Int = 0xffeef0f3.toInt()
    @Suppress("DEPRECATION")
    fun apply(window: Window) {
        window.setDecorFitsSystemWindows(false)
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        // The keyboard paints underneath the transparent IME caption strip on edge-to-edge Android.
        // Older/OEM three-button paths still use this matching navigation bar color.
        window.navigationBarColor=UiPalette.surface(window.context)
        window.navigationBarDividerColor=Color.TRANSPARENT
        window.isNavigationBarContrastEnforced=false
        window.insetsController?.setSystemBarsAppearance(
            if(UiPalette.dark(window.context))0 else WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
    }
}
