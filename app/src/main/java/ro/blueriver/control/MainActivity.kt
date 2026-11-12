
package ro.blueriver.control

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(40, 40, 40, 40)
        }

        val title = TextView(this).apply {
            text = "BLUE RIVER CONTROL"
            textSize = 24f
            gravity = Gravity.CENTER
        }

        val status = TextView(this).apply {
            text = "Samsung Galaxy A16\nAndroid 15\n\nVersiune test 1.0"
            textSize = 17f
            gravity = Gravity.CENTER
        }

        val accessibilityButton = Button(this).apply {
            text = "ACTIVEAZA ACCESSIBILITY"
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                )
            }
        }

        val testButton = Button(this).apply {
            text = "TEST AUTOMATIZARE"
            setOnClickListener {
                status.text =
                    "Test disponibil dupa configurarea serviciului Accessibility."
            }
        }

        layout.addView(title)
        layout.addView(status)
        layout.addView(accessibilityButton)
        layout.addView(testButton)

        setContentView(layout)
    }
}
