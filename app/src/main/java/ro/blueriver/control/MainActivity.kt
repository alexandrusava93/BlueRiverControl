package ro.blueriver.control

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var tokenInput: EditText
    private lateinit var commandsButton: Button
    private lateinit var overlayButton: Button
    private lateinit var accessibilityButton: Button
    private lateinit var wakeButton: Button

    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            refreshLabels()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(CommandService.PREFS, MODE_PRIVATE)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }

        val title = TextView(this).apply {
            text = "BLUE RIVER CONTROL"
            textSize = 22f
            gravity = Gravity.CENTER
        }

        status = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 24)
        }

        tokenInput = EditText(this).apply {
            hint = "Cod telefon (ex: B459BLE)"
            setText(prefs.getString(CommandService.KEY_TOKEN, ""))
            isSingleLine = true
            gravity = Gravity.CENTER
        }

        val saveButton = Button(this).apply {
            text = "SALVEAZA CODUL"
            setOnClickListener {
                val code = tokenInput.text.toString().trim().uppercase()
                prefs.edit().putString(CommandService.KEY_TOKEN, code).apply()
                Toast.makeText(
                    this@MainActivity,
                    if (code.isEmpty()) "Cod sters" else "Cod salvat: " + code,
                    Toast.LENGTH_SHORT
                ).show()

                if (CommandService.isRunning) {
                    stopService(Intent(this@MainActivity, CommandService::class.java))
                    startCommandsService()
                }
            }
        }

        commandsButton = Button(this).apply {
            setOnClickListener {
                if (CommandService.isRunning) {
                    stopService(Intent(this@MainActivity, CommandService::class.java))
                    CommandService.isRunning = false
                } else {
                    startCommandsService()
                }
                refreshLabels()
            }
        }

        overlayButton = Button(this).apply {
            setOnClickListener {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + packageName)
                    )
                )
            }
        }

        accessibilityButton = Button(this).apply {
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(
                    this@MainActivity,
                    "Alege Blue River Control si activeaza serviciul.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        wakeButton = Button(this).apply {
            setOnClickListener {
                val prefs = getSharedPreferences(CommandService.PREFS, MODE_PRIVATE)
                val next = !prefs.getBoolean(CommandService.KEY_WAKE_SCREEN, true)
                prefs.edit().putBoolean(CommandService.KEY_WAKE_SCREEN, next).apply()
                refreshLabels()
                Toast.makeText(
                    this@MainActivity,
                    if (next) "Ecranul va porni automat la comanda LIVE" else "Ecranul nu va porni automat",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        val tollButton = Button(this).apply {
            text = "DESCHIDE eTollRO"
            setOnClickListener {
                val launchIntent = packageManager.getLaunchIntentForPackage("ro.cnair.tollro")

                if (launchIntent != null) {
                    try {
                        startActivity(launchIntent)
                    } catch (e: Exception) {
                        Toast.makeText(
                            this@MainActivity,
                            "Eroare la deschiderea eTollRo: " + (e.message ?: ""),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "eTollRo nu a fost gasita. Verifica instalarea.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        layout.addView(title)
        layout.addView(status)
        layout.addView(tokenInput)
        layout.addView(saveButton)
        layout.addView(commandsButton)
        layout.addView(overlayButton)
        layout.addView(accessibilityButton)
        layout.addView(wakeButton)
        layout.addView(tollButton)

        setContentView(layout)
        refreshLabels()
    }

    override fun onResume() {
        super.onResume()
        refreshLabels()
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 2000)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    private fun startCommandsService() {
        val saved = getSharedPreferences(CommandService.PREFS, MODE_PRIVATE)
            .getString(CommandService.KEY_TOKEN, "")
            ?.trim()
            .orEmpty()

        if (saved.isEmpty()) {
            Toast.makeText(this, "Adauga intai codul telefonului.", Toast.LENGTH_SHORT).show()
            return
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }

        startForegroundService(Intent(this, CommandService::class.java))
        Toast.makeText(this, "Comenzile au pornit.", Toast.LENGTH_SHORT).show()
    }

    private fun refreshLabels() {
        status.text = CommandService.lastStatus
        commandsButton.text =
            if (CommandService.isRunning) "OPRESTE COMENZILE" else "PORNESTE COMENZILE"
        overlayButton.text =
            if (Settings.canDrawOverlays(this)) "PORNIRE APLICATII: PERMIS" else "PERMITE PORNIREA APLICATIILOR"
        accessibilityButton.text =
            if (TollAccessibilityService.isEnabled(this)) "AUTOMATIZARE: ACTIVA" else "ACTIVEAZA AUTOMATIZAREA"
        wakeButton.text =
            if (getSharedPreferences(CommandService.PREFS, MODE_PRIVATE)
                    .getBoolean(CommandService.KEY_WAKE_SCREEN, true)
            ) "PORNESTE ECRANUL: ACTIV" else "PORNESTE ECRANUL: OPRIT"
    }
}
