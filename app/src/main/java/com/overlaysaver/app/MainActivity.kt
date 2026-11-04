package com.overlaysaver.app

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var projectionManager: MediaProjectionManager

    private val overlayPermissionLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
            checkPermissionsAndProceed()
        }

    private val accessibilityLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
            checkPermissionsAndProceed()
        }

    private val screenCaptureLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val serviceIntent = Intent(this, OverlayService::class.java).apply {
                    putExtra(OverlayService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(OverlayService.EXTRA_RESULT_DATA, result.data)
                }
                startForegroundService(serviceIntent)
                statusText.text =
                    "Set up. The Save button will now appear automatically whenever " +
                    "Messenger is open, and hide everywhere else."
            } else {
                statusText.text = "Screen capture permission was not granted."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        findViewById<Button>(R.id.startButton).setOnClickListener {
            checkPermissionsAndProceed()
        }

        findViewById<Button>(R.id.stopButton).setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
            statusText.text = "Overlay stopped."
        }
    }

    private fun hasAccessibilityPermission(): Boolean {
        val expectedId = "$packageName/${ForegroundAccessibilityService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expectedId, ignoreCase = true)) return true
        }
        return false
    }

    private fun checkPermissionsAndProceed() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            statusText.text = "Allow \"display over other apps\" for Overlay Saver, then come back."
            overlayPermissionLauncher.launch(intent)
            return
        }

        if (!hasAccessibilityPermission()) {
            statusText.text =
                "You'll land on a list of accessibility services \u2014 find \"Overlay Saver\" " +
                "in the list, tap it, and turn it on. This is how the app knows the moment " +
                "Messenger is open; it's set up to only ever see Messenger, nothing else. " +
                "Then come back."
            accessibilityLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }
}
