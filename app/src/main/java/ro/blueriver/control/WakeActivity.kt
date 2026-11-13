package ro.blueriver.control

import android.app.Activity
import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager

/**
 * Fereastra invizibila care aprinde ecranul si ridica blocarea telefonului
 * cand dispeceratul apasa PORNESTE LIVE. Fara ea, pasii de automatizare
 * nu se pot apasa si sesiunea nu porneste.
 */
class WakeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (Build.VERSION.SDK_INT >= 26) {
            val keyguard = getSystemService(KeyguardManager::class.java)
            if (keyguard?.isKeyguardLocked == true) {
                keyguard.requestDismissKeyguard(this, null)
            }
        }

        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 1200L)
    }
}
