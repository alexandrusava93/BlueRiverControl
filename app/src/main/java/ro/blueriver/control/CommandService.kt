package ro.blueriver.control

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.PowerManager
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Serviciu care intreaba serverul la fiecare 10 secunde daca exista comenzi
 * de la dispecerat (deschiderea aplicatiei eTollRo sau pornirea sesiunii live)
 * si raporteaza rezultatul.
 */
class CommandService : Service() {

    companion object {
        private const val TAG = "BlueRiverControl"
        private const val CHANNEL_SERVICE = "comenzi_serviciu"
        private const val CHANNEL_ALERT = "comenzi_alerta"
        private const val NOTIF_SERVICE = 1
        private const val NOTIF_ALERT = 2
        private const val POLL_URL = "https://glossy-fleet-pilot-pro.base44.app/functions/phoneCommand"
        private const val POLL_INTERVAL_MS = 10_000L
        private const val RETRY_INTERVAL_MS = 30_000L
        private const val ETOLL_PACKAGE = "ro.cnair.tollro"
        private const val ETOLL_OPEN_WAIT_MS = 6_000L
        private const val WAKE_WAIT_MS = 2_000L

        const val PREFS = "blue_river_control"
        const val KEY_TOKEN = "token"
        const val KEY_WAKE_SCREEN = "wake_screen"

        @Volatile
        var isRunning = false

        @Volatile
        var lastStatus = "Comenzile sunt oprite."
    }

    private var worker: Thread? = null

    @Volatile
    private var active = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                "Legatura cu dispeceratul",
                NotificationManager.IMPORTANCE_LOW
            )
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERT,
                "Comenzi primite",
                NotificationManager.IMPORTANCE_HIGH
            )
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_SERVICE, serviceNotification())

        if (worker == null) {
            active = true
            isRunning = true
            lastStatus = "Comenzile sunt pornite. Astept dispeceratul."
            worker = Thread { pollLoop() }.also {
                it.isDaemon = true
                it.start()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        active = false
        isRunning = false
        lastStatus = "Comenzile sunt oprite."
        super.onDestroy()
    }

    private fun pollLoop() {
        while (active) {
            val token = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_TOKEN, "")
                ?.trim()
                ?.uppercase()
                .orEmpty()

            if (token.isEmpty()) {
                lastStatus = "Adauga codul telefonului."
                sleep(5000L)
                continue
            }

            try {
                val body = httpGet(POLL_URL + "?token=" + encode(token) + "&action=poll")
                val commands = JSONObject(body).optJSONArray("commands")
                handleCommands(token, commands)
                sleep(POLL_INTERVAL_MS)
            } catch (e: Exception) {
                lastStatus = "Fara conexiune: " + (e.message ?: "")
                Log.w(TAG, "Interogarea a esuat: " + e.message)
                sleep(RETRY_INTERVAL_MS)
            }
        }
    }

    private fun handleCommands(token: String, commands: JSONArray?) {
        if (commands == null || commands.length() == 0) {
            if (!lastStatus.startsWith("Rezultat")) {
                lastStatus = "Conectat. Astept comenzi."
            }
            return
        }

        for (i in 0 until commands.length()) {
            val command = commands.optJSONObject(i) ?: continue
            val id = command.optString("id")
            val action = command.optString("action")
            if (id.isEmpty()) continue

            when (action) {
                "launch_etoll" -> {
                    lastStatus = "Comanda primita: deschid eTollRo."
                    val outcome = launchEtoll()
                    if (!Settings.canDrawOverlays(this)) {
                        showAlertNotification(outcome.second)
                    }
                    acknowledge(token, id, outcome.first, outcome.second)
                    lastStatus = "Rezultat: " + outcome.second
                }

                "start_live" -> {
                    lastStatus = "Comanda primita: pornesc sesiunea live."
                    val outcome = try {
                        startLive(command.optJSONArray("steps"))
                    } finally {
                        TollAccessibilityService.hideBusy()
                    }
                    acknowledge(token, id, outcome.first, outcome.second)
                    lastStatus = "Rezultat: " + outcome.second
                }

                else -> {
                    acknowledge(token, id, "error", "Comanda necunoscuta: " + action)
                    lastStatus = "Comanda necunoscuta: " + action
                }
            }
        }
    }

    private fun launchEtoll(): Pair<String, String> {
        val launchIntent = packageManager.getLaunchIntentForPackage(ETOLL_PACKAGE)
            ?: return "error" to "eTollRo nu este instalata"

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            startActivity(launchIntent)
            "done" to "eTollRo a pornit"
        } catch (e: Exception) {
            "error" to ("eTollRo nu a pornit: " + (e.message ?: ""))
        }
    }

    // Deschide eTollRo si apasa automat pasii primiti de la dispecerat
    private fun startLive(stepsJson: JSONArray?): Pair<String, String> {
        if (!TollAccessibilityService.isEnabled(this)) {
            return "error" to "Activeaza automatizarea (Accesibilitate) in Blue River Control"
        }

        val steps = mutableListOf<TollAccessibilityService.Step>()
        if (stepsJson != null) {
            for (i in 0 until stepsJson.length()) {
                val step = stepsJson.optJSONObject(i) ?: continue
                val text = step.optString("text").trim()
                if (text.isEmpty()) continue
                steps.add(
                    TollAccessibilityService.Step(
                        text,
                        step.optLong("wait_before", 1500L),
                        step.optLong("wait_after", 2000L),
                        // Numarul remorcii se cauta in lista, care poate fi derulata
                        step.optBoolean("scroll", false)
                    )
                )
            }
        }

        if (steps.isEmpty()) {
            return "error" to "Nu sunt pasi configurati pentru sesiunea live"
        }

        // Aprindem ecranul si ridicam blocarea inainte de automatizare:
        // cu ecranul stins, pasii nu se pot apasa si sesiunea nu porneste.
        wakeScreen()
        sleep(WAKE_WAIT_MS)

        // Cat timp ruleaza pasii, pe ecran apare un mesaj si nu se poate apasa nimic
        TollAccessibilityService.showBusy("Se pornește sesiunea live…")

        val launchIntent = packageManager.getLaunchIntentForPackage(ETOLL_PACKAGE)
            ?: return "error" to "eTollRo nu este instalata"

        // Pornim eTollRo de la primul ei ecran, chiar daca aplicatia era deja deschisa
        // pe alta pagina - altfel pasii de automatizare nu se regasesc pe ecran.
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

        try {
            startActivity(launchIntent)
        } catch (e: Exception) {
            return "error" to ("eTollRo nu a pornit: " + (e.message ?: ""))
        }

        // Aplicatia are nevoie de cateva secunde pana se deschide
        sleep(ETOLL_OPEN_WAIT_MS)

        if (!TollAccessibilityService.runSteps(steps)) {
            return "error" to "Automatizarea nu a pornit. Verifica accesibilitatea."
        }

        val startedAt = System.currentTimeMillis()
        while (!TollAccessibilityService.finished &&
            System.currentTimeMillis() - startedAt < 160_000L
        ) {
            sleep(1000L)
        }

        val message = TollAccessibilityService.resultMessage ?: "Timp expirat"
        val ok = TollAccessibilityService.finished && message.startsWith("Sesiune")

        return (if (ok) "done" else "error") to message
    }

    // Aprinde ecranul si ridica blocarea telefonului (setare din ecranul aplicatiei).
    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        if (!getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_WAKE_SCREEN, true)) return

        try {
            getSystemService(PowerManager::class.java).newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "BlueRiverControl:ecran"
            ).acquire(90_000L)
        } catch (e: Exception) {
            Log.w(TAG, "Ecranul nu a pornit: " + e.message)
        }

        try {
            startActivity(
                Intent(this, WakeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Fereastra de trezire nu a pornit: " + e.message)
        }
    }

    private fun acknowledge(token: String, id: String, status: String, message: String) {
        try {
            httpGet(
                POLL_URL + "?token=" + encode(token) +
                    "&action=ack&id=" + encode(id) +
                    "&status=" + encode(status) +
                    "&result=" + encode(message.take(280))
            )
        } catch (e: Exception) {
            Log.w(TAG, "Confirmarea nu a plecat: " + e.message)
        }
    }

    private fun httpGet(address: String): String {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 15000
        connection.readTimeout = 15000

        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("raspuns " + code)
            }
            return text
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun sleep(milliseconds: Long) {
        try {
            Thread.sleep(milliseconds)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun serviceNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_SERVICE)
            .setContentTitle("Blue River Control")
            .setContentText("Astept comenzi de la dispecerat")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun showAlertNotification(message: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(ETOLL_PACKAGE)
        val target = launchIntent ?: Intent(this, MainActivity::class.java)
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val action = PendingIntent.getActivity(this, NOTIF_ALERT, target, PendingIntent.FLAG_IMMUTABLE)

        val notification = Notification.Builder(this, CHANNEL_ALERT)
            .setContentTitle("Comanda: deschide eTollRo")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .setContentIntent(action)
            .build()

        getSystemService(NotificationManager::class.java).notify(NOTIF_ALERT, notification)
    }
}
