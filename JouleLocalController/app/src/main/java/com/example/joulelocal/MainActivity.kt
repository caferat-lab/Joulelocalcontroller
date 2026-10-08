package com.example.joulelocal

import android.Manifest
import android.app.Activity
import android.os.Bundle
import android.os.Build
import android.content.pm.PackageManager
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var current: TextView
    private lateinit var target: EditText
    private lateinit var hours: EditText
    private lateinit var minutes: EditText
    private lateinit var start: Button
    private lateinit var stop: Button

    private var lastFeed = 1L
    private var lastSequence = 0L
    private lateinit var joule: JouleBleManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()

        joule = JouleBleManager(
            this,
            onStatus = { runOnUiThread { status.text = it } },
            onTemperature = { temp, feed, seq ->
                lastFeed = feed
                lastSequence = seq
                runOnUiThread {
                    current.text = "Current water temperature\n%.1f °C   /   %.1f °F".format(
                        temp, temp * 9f / 5f + 32f
                    )
                }
            },
            onReady = {
                runOnUiThread {
                    start.isEnabled = true
                    stop.isEnabled = true
                }
            }
        )

        findViewById<Button>(1001).setOnClickListener {
            requestBluetoothPermissionsThenScan()
        }
        start.setOnClickListener {
    val t = target.text.toString().toFloatOrNull()
    if (t == null || t !in 0f..100f) {
        status.text = "Enter a target temperature from 0–100°C."
        return@setOnClickListener
    }
    
    val h = hours.text.toString().toLongOrNull() ?: 0L
    val m = minutes.text.toString().toLongOrNull() ?: 0L

    if (h < 0 || m < 0 || m > 59) {
        status.text = "Enter a valid cooking time."
        return@setOnClickListener
    }

    val cookTimeSeconds = h * 3600L + m * 60L

    joule.startCook(
        t,
        cookTimeSeconds,
        lastFeed,
        lastSequence
    )
}

stop.setOnClickListener {
        joule.stopCook(lastFeed, lastSequence)
}   
}
    
private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 32, 28, 28)
            setBackgroundColor(Color.rgb(16, 18, 20))
        }

        fun tv(text: String, size: Float, bold: Boolean = false): TextView =
            TextView(this).apply {
                this.text = text
                textSize = size
                setTextColor(Color.rgb(244, 246, 248))
                if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, 8, 0, 8)
            }

        root.addView(tv("JOULE", 30f, true))
        root.addView(tv("Local Bluetooth Controller", 16f))

        status = tv("Tap CONNECT to find your Joule.", 15f)
        status.setTextColor(Color.rgb(174, 183, 194))
        root.addView(status)

        val connect = Button(this).apply {
            id = 1001
            text = "CONNECT TO JOULE"
        }
        root.addView(connect)

        current = tv("Current water temperature\n—", 24f, true)
        current.gravity = Gravity.CENTER
        current.setPadding(0, 30, 0, 30)
        root.addView(current)

        target = EditText(this).apply {
            hint = "Target °C (e.g. 55.0)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("55.0")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        root.addView(target)

        val timerLabel = tv("Cooking time", 14f)
        root.addView(timerLabel)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        hours = EditText(this).apply {
            hint = "Hours"; inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        }
        minutes = EditText(this).apply {
            hint = "Minutes"; inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        }
        row.addView(hours, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(minutes, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row)

        start = Button(this).apply { text = "START COOKING"; isEnabled = false }
        stop = Button(this).apply { text = "STOP"; isEnabled = false }
        root.addView(start)
        root.addView(stop)

        root.addView(tv(
            "First connection: the Joule may flash its top button. Press it when the app asks. " +
            "The authorization key is then saved locally for future connections.",
            13f
        ))

        setContentView(root)
    }

    private fun requestBluetoothPermissionsThenScan() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                needed += Manifest.permission.BLUETOOTH_SCAN
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                needed += Manifest.permission.BLUETOOTH_CONNECT
        } else if (Build.VERSION.SDK_INT >= 23 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 77)
        } else {
            joule.startScan()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 77 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) joule.startScan()
        else status.text = "Bluetooth permission is required to find the Joule."
    }

    override fun onDestroy() {
        joule.disconnect()
        super.onDestroy()
    }
}
