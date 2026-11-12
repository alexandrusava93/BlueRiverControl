
package ro.blueriver.control

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log

class TollAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "BlueRiverControl"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility activat")
    }

    override fun onAccessibilityEvent(
        event: AccessibilityEvent?
    ) {
        if (event == null) return

        // Prima versiune: observam ecranele.
        // Nu executam apasari automate inca.
        if (
            event.eventType ==
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            Log.d(
                TAG,
                "Ecran detectat: ${event.packageName}"
            )
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility intrerupt")
    }

    private fun findAndClick(text: String): Boolean {
        val root = rootInActiveWindow ?: return false

        val nodes = root.findAccessibilityNodeInfosByText(text)

        for (node in nodes) {
            var current: AccessibilityNodeInfo? = node

            while (current != null) {
                if (current.isClickable) {
                    return current.performAction(
                        AccessibilityNodeInfo.ACTION_CLICK
                    )
                }
                current = current.parent
            }
        }

        return false
    }
}
