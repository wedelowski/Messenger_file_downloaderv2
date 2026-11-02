package com.overlaysaver.app

import android.app.Activity
import android.app.AppOpsManager
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.Settings
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

    private val usageAccessLauncher =
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

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
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

        if (!hasUsageAccess()) {
            statusText.text =
                "Find \"Overlay Saver\" in the list and turn on Usage access \u2014 " +
                "this is how the app can tell when Messenger is the one open, " +
                "so the button only appears there. Then come back."
            usageAccessLauncher.launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            return
        }

        // Overlay + usage-access permissions are granted; now ask for screen-capture permission.
        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }
}
