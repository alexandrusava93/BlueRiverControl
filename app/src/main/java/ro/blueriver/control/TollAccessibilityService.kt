package ro.blueriver.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Serviciu de accesibilitate care apasa automat pasii din aplicatia eTollRo
 * (pasii sunt configurati din aplicatia web) pana la pornirea sesiunii live.
 */
class TollAccessibilityService : AccessibilityService() {

    class Step(val text: String, val waitBeforeMs: Long, val waitAfterMs: Long)

    companion object {
        private const val TAG = "BlueRiverControl"
        private const val ETOLL_PACKAGE = "ro.cnair.tollro"
        private const val STEP_TIMEOUT_MS = 25_000L
        private const val TOTAL_TIMEOUT_MS = 150_000L

        @Volatile
        private var instance: TollAccessibilityService? = null

        @Volatile
        var finished = false

        @Volatile
        var resultMessage: String? = null

        @Volatile
        var running = false

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(':').any { it.contains(context.packageName, ignoreCase = true) }
        }

        fun runSteps(steps: List<Step>): Boolean {
            val service = instance ?: return false
            return service.startRun(steps)
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    private var steps: List<Step> = emptyList()
    private var index = 0
    private var startedAt = 0L
    private var stepStartedAt = 0L
    private var waitingUntil = 0L
    private var clicked = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return

            val now = System.currentTimeMillis()
            if (now - startedAt > TOTAL_TIMEOUT_MS) {
                finish(
                    if (steps.isEmpty()) "Nu sunt pasi configurati"
                    else "Timp expirat la pasul " + (index + 1) + " (" + steps[index].text + ")"
                )
                return
            }

            if (now < waitingUntil) {
                handler.postDelayed(this, 300)
                return
            }

            if (index >= steps.size) {
                finish("Sesiune live pornita (" + steps.size + " pasi)")
                return
            }

            val step = steps[index]

            if (!clicked) {
                // cat timp eTollRo nu este pe ecran, asteptam fara sa consumam timpul pasului
                if (!etollOnScreen()) {
                    stepStartedAt = now
                    handler.postDelayed(this, 500)
                    return
                }
                if (now - stepStartedAt > STEP_TIMEOUT_MS) {
                    finish("Nu am gasit \"" + step.text + "\". Pe ecran: " + screenSummary())
                    return
                }
                if (clickStep(step.text)) {
                    clicked = true
                    waitingUntil = now + step.waitAfterMs.coerceAtLeast(500L)
                }
            } else if (now >= waitingUntil) {
                index++
                clicked = false
                stepStartedAt = now
                waitingUntil = now + (steps.getOrNull(index)?.waitBeforeMs ?: 0L)
            }

            handler.postDelayed(this, 400)
        }
    }

    private fun startRun(list: List<Step>): Boolean {
        if (running) return false
        if (list.isEmpty()) return false

        steps = list
        index = 0
        clicked = false
        startedAt = System.currentTimeMillis()
        stepStartedAt = startedAt
        waitingUntil = startedAt + list[0].waitBeforeMs
        finished = false
        resultMessage = null
        running = true

        handler.post(tick)
        return true
    }

    private fun finish(message: String) {
        running = false
        finished = true
        resultMessage = message
        handler.removeCallbacks(tick)
        Log.d(TAG, "Automatizare: " + message)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accesibilitate activata")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            Log.d(TAG, "Ecran detectat: " + event.packageName)
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accesibilitate intrerupta")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        if (running) finish("Automatizare intrerupta")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        if (running) finish("Automatizare intrerupta")
        super.onDestroy()
    }

    private fun etollOnScreen(): Boolean =
        rootInActiveWindow?.packageName?.toString() == ETOLL_PACKAGE

    // Apasa primul element din eTollRo care contine textul dat.
    // Comparatia ignora majusculele si diacriticele (Porneste = Pornește),
    // iar variantele se separa prin |
    private fun clickStep(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val variants = text.split("|").map { normalize(it.trim()) }.filter { it.isNotEmpty() }
        if (variants.isEmpty()) return false

        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectNodes(root, nodes)

        for (variant in variants) {
            for (node in nodes) {
                val pkg = node.packageName?.toString()
                if (pkg != null && pkg != ETOLL_PACKAGE) continue
                val label = normalize(nodeLabel(node))
                if (label.isEmpty()) continue
                if (label.contains(variant) && tapNode(node)) return true
            }
        }

        return false
    }

    private fun collectNodes(node: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        out.add(node)
        for (i in 0 until node.childCount) {
            collectNodes(node.getChild(i), out)
        }
    }

    private fun nodeLabel(node: AccessibilityNodeInfo): String {
        val parts = mutableListOf<String>()
        node.text?.let { parts.add(it.toString()) }
        node.contentDescription?.let { parts.add(it.toString()) }
        return parts.joinToString(" ")
    }

    // Textele vizibile pe ecran, ca sa stim ce sa trecem in pasi
    private fun screenSummary(): String {
        val root = rootInActiveWindow ?: return "ecran necunoscut"
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectNodes(root, nodes)
        val texts = nodes.mapNotNull { it.text?.toString()?.trim() }
            .filter { it.isNotEmpty() && it.length < 40 }
            .distinct()
            .take(6)
        return if (texts.isEmpty()) "niciun text citibil" else texts.joinToString(" / ")
    }

    private fun normalize(value: String): String {
        val lower = value.lowercase()
        val builder = StringBuilder(lower.length)
        for (ch in lower) {
            builder.append(
                when (ch) {
                    'a', 'A' -> 'a'
                    'ă', 'â', 'á', 'à', 'ä', 'ã', 'ą' -> 'a'
                    'î', 'í', 'ì', 'ï', 'ı' -> 'i'
                    'ș', 'ş', 'ś', 's' -> 's'
                    'ț', 'ţ', 't' -> 't'
                    'é', 'è', 'ê', 'ë', 'ę' -> 'e'
                    'ó', 'ò', 'ô', 'ö', 'õ' -> 'o'
                    'ú', 'ù', 'û', 'ü' -> 'u'
                    'ç', 'ć' -> 'c'
                    'ñ' -> 'n'
                    else -> ch
                }
            )
        }
        return builder.toString()
    }

    private fun tapNode(node: AccessibilityNodeInfo?): Boolean {
        var current = node
        var depth = 0

        while (current != null && depth < 6) {
            if (current.isClickable &&
                current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ) {
                return true
            }
            current = current.parent
            depth++
        }

        // Fallback: apasare pe centrul elementului gasit
        var target = node
        depth = 0
        while (target != null && depth < 4) {
            val rect = Rect()
            target.getBoundsInScreen(rect)
            if (rect.width() > 0 && rect.height() > 0) {
                return tapPoint(rect.exactCenterX(), rect.exactCenterY())
            }
            target = target.parent
            depth++
        }

        return false
    }

    private fun tapPoint(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        return dispatchGesture(gesture, null, null)
    }
}
