package app.bulwark.android

import android.app.Activity
import android.os.Build
import android.view.WindowManager

/**
 * Applied to every Bulwark window before any content is shown: blocks screenshots, screen
 * recording, casting and the recent-apps preview (spec section 4, "Screen and UI leaks").
 */
object SecureWindow {
    fun apply(activity: Activity, secure: Boolean = true) {
        if (secure) {
            activity.window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.setRecentsScreenshotEnabled(!secure)
        }
    }
}
