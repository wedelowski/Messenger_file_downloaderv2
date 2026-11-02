package com.overlaysaver.app

import android.app.*
import android.app.AlertDialog
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.*
import android.widget.PopupMenu
import android.widget.TextView
import java.io.OutputStream

class OverlayService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val CHANNEL_ID = "overlay_saver_channel"
        const val PREFS = "overlay_prefs"
        const val KEY_X = "pos_x"
        const val KEY_Y = "pos_y"

        // Regular Messenger and Messenger Lite. The "windowed version" the user means is
        // this same app running in Android split-screen / freeform multi-window mode \u2014
        // it still reports one of these as the focused package, so no extra handling needed.
        val MESSENGER_PACKAGES = setOf(
            "com.facebook.orca",
            "com.facebook.mlite"
        )
        const val POLL_INTERVAL_MS = 700L
    }

    private lateinit var windowManager: WindowManager
    private lateinit var saveButton: TextView
    private lateinit var dotsButton: TextView
    private lateinit var fadeText: TextView
    private lateinit var saveParams: WindowManager.LayoutParams
    private lateinit var dotsParams: WindowManager.LayoutParams
    private lateinit var fadeParams: WindowManager.LayoutParams

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    private val handler = Handler(Looper.getMainLooper())
    private var viewsVisible = false
    private lateinit var usageStatsManager: UsageStatsManager

    private val pollRunnable = object : Runnable {
        override fun run() {
            updateVisibilityForForegroundApp()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, buildNotification())

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity_RESULT_CANCELED) ?: return START_NOT_STICKY
        val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
        if (resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        val metrics = DisplayMetrics()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

        setupOverlayViews()
        hideAllViews() // start hidden; the poll loop reveals them only inside Messenger
        handler.post(pollRunnable)
        return START_STICKY
    }

    /** Looks at recent usage events to find whichever app currently has foreground focus. */
    private fun getForegroundPackage(): String? {
        val end = System.currentTimeMillis()
        val start = end - 10_000 // look back 10s, plenty to catch the latest switch
        val events = usageStatsManager.queryEvents(start, end)
        var lastPackage: String? = null
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
            ) {
                lastPackage = event.packageName
            }
        }
        return lastPackage
    }

    private fun updateVisibilityForForegroundApp() {
        val fg = getForegroundPackage()
        val shouldShow = fg != null && fg in MESSENGER_PACKAGES
        if (shouldShow && !viewsVisible) {
            showAllViews()
        } else if (!shouldShow && viewsVisible) {
            hideAllViews()
        }
    }

    private fun showAllViews() {
        viewsVisible = true
        saveButton.visibility = View.VISIBLE
        dotsButton.visibility = View.VISIBLE
        // fadeText stays GONE until there's actually a hint to show
    }

    private fun hideAllViews() {
        viewsVisible = false
        saveButton.visibility = View.GONE
        dotsButton.visibility = View.GONE
        fadeText.visibility = View.GONE
    }

    // Workaround constant name clash avoidance
    private val Activity_RESULT_CANCELED = Activity.RESULT_CANCELED

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Overlay Saver", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        return NotificationCompatBuilder(this).build()
    }

    // Minimal notification builder without the androidx dependency conflicts
    private fun NotificationCompatBuilder(context: Context) =
        Notification.Builder(context, CHANNEL_ID)
            .setContentTitle("Overlay Saver running")
            .setContentText("Tap the floating button to save what's on screen.")
            .setSmallIcon(android.R.drawable.ic_menu_save)

    private fun setupOverlayViews() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedY = prefs.getInt(KEY_Y, screenHeight / 3)

        val overlayType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        // --- Save button ---
        saveButton = TextView(this).apply {
            text = "\u2B07"  // down arrow
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF0866FF.toInt())
            setPadding(28, 20, 28, 20)
        }
        saveParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 8
            y = savedY
        }
        windowManager.addView(saveButton, saveParams)
        makeDraggable(saveButton, saveParams) { newY ->
            dotsParams.y = newY + 90
            windowManager.updateViewLayout(dotsButton, dotsParams)
            prefs.edit().putInt(KEY_Y, newY).apply()
        }
        saveButton.setOnClickListener { onSaveTapped() }

        // --- 3-dot menu button, anchored under the save button ---
        dotsButton = TextView(this).apply {
            text = "\u22EE" // vertical ellipsis
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x99000000.toInt())
            setPadding(28, 12, 28, 12)
        }
        dotsParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 8
            y = savedY + 90
        }
        windowManager.addView(dotsButton, dotsParams)
        dotsButton.setOnClickListener { showMenu() }

        // --- Fade-out hint text ---
        fadeText = TextView(this).apply {
            text = "Select an image or video to download it"
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xCC000000.toInt())
            setPadding(20, 10, 20, 10)
            visibility = View.GONE
        }
        fadeParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = savedY + 160
        }
        windowManager.addView(fadeText, fadeParams)
    }

    /** Drag-to-reposition vertically along the right edge. onMoved reports the new y. */
    private fun makeDraggable(
        view: View,
        params: WindowManager.LayoutParams,
        onMoved: (Int) -> Unit
    ) {
        var startY = 0f
        var startParamY = 0
        var moved = false
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    startParamY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (event.rawY - startY).toInt()
                    if (kotlin.math.abs(dy) > 12) moved = true
                    params.y = (startParamY + dy).coerceIn(0, screenHeight - 100)
                    windowManager.updateViewLayout(view, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        onMoved(params.y)
                    } else {
                        v.performClick()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun showMenu() {
        val popup = PopupMenu(this, dotsButton)
        popup.menu.add("Move to top")
        popup.menu.add("Move to middle")
        popup.menu.add("Move to bottom")
        popup.menu.add("Save full screen now")
        popup.menu.add("Self-destruct (delete app)")
        popup.setOnMenuItemClickListener { item ->
            val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            when (item.title) {
                "Move to top" -> setButtonY(80, prefs)
                "Move to middle" -> setButtonY(screenHeight / 2 - 100, prefs)
                "Move to bottom" -> setButtonY(screenHeight - 300, prefs)
                "Save full screen now" -> captureScreen()
                "Self-destruct (delete app)" -> confirmSelfDestructStep1()
            }
            true
        }
        popup.show()
    }

    /** First of two confirmations before uninstalling. */
    private fun confirmSelfDestructStep1() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Delete Overlay Saver?")
            .setMessage(
                "This removes the app and its settings from your phone. " +
                "Anything already saved to Pictures/OverlaySaver stays, since that's " +
                "regular phone storage, not part of the app."
            )
            .setPositiveButton("Continue") { _, _ -> confirmSelfDestructStep2() }
            .setNegativeButton("Cancel", null)
            .create()
            .also { it.window?.setType(overlayDialogType()) }
            .show()
    }

    /** Second, final confirmation. */
    private fun confirmSelfDestructStep2() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Are you sure?")
            .setMessage("This can't be undone. Overlay Saver will be uninstalled now.")
            .setPositiveButton("Delete it") { _, _ -> performSelfDestruct() }
            .setNegativeButton("Cancel", null)
            .create()
            .also { it.window?.setType(overlayDialogType()) }
            .show()
    }

    private fun overlayDialogType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun performSelfDestruct() {
        // Android always shows its own system uninstall confirmation on top of this \u2014
        // that's a third, OS-level check that can't be skipped, by design.
        val uninstallIntent = Intent(Intent.ACTION_DELETE).apply {
            data = android.net.Uri.parse("package:$packageName")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(uninstallIntent)
        stopSelf()
    }

    private fun setButtonY(y: Int, prefs: android.content.SharedPreferences) {
        saveParams.y = y
        dotsParams.y = y + 90
        fadeParams.y = y + 160
        windowManager.updateViewLayout(saveButton, saveParams)
        windowManager.updateViewLayout(dotsButton, dotsParams)
        windowManager.updateViewLayout(fadeText, fadeParams)
        prefs.edit().putInt(KEY_Y, y).apply()
    }

    private fun onSaveTapped() {
        // This build can't detect which single on-screen element is "selected" without
        // an Accessibility Service (a much more invasive permission). It captures what's
        // currently visible on screen instead. If you open a photo/video full-screen first,
        // that's effectively your "selection".
        captureScreen()
    }

    private fun showFadeHint() {
        fadeText.visibility = View.VISIBLE
        fadeText.alpha = 1f
        fadeText.animate().alpha(0f).setStartDelay(1000).setDuration(400)
            .withEndAction { fadeText.visibility = View.GONE }.start()
    }

    private fun captureScreen() {
        val projection = mediaProjection
        if (projection == null) {
            showFadeHint()
            return
        }

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, android.graphics.PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection.createVirtualDisplay(
            "OverlaySaverCapture", screenWidth, screenHeight, screenDensity,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, handler
        )

        handler.postDelayed({
            val image: Image? = imageReader?.acquireLatestImage()
            if (image == null) {
                showFadeHint()
                cleanupCapture()
                return@postDelayed
            }
            val bitmap = imageToBitmap(image)
            image.close()
            saveBitmap(bitmap)
            cleanupCapture()
        }, 200)
    }

    private fun imageToBitmap(image: Image): android.graphics.Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * screenWidth
        val bitmap = android.graphics.Bitmap.createBitmap(
            screenWidth + rowPadding / pixelStride, screenHeight, android.graphics.Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        return android.graphics.Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
    }

    private fun saveBitmap(bitmap: android.graphics.Bitmap) {
        val filename = "overlay-saver-${System.currentTimeMillis()}.png"
        val resolver = contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/OverlaySaver")
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            val out: OutputStream? = resolver.openOutputStream(uri)
            out?.use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            handler.post { toast("Saved to Pictures/OverlaySaver") }
        } else {
            handler.post { toast("Could not save image") }
        }
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun cleanupCapture() {
        virtualDisplay?.release()
        imageReader?.close()
        virtualDisplay = null
        imageReader = null
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(pollRunnable)
        cleanupCapture()
        mediaProjection?.stop()
        if (::windowManager.isInitialized) {
            if (::saveButton.isInitialized) windowManager.removeView(saveButton)
            if (::dotsButton.isInitialized) windowManager.removeView(dotsButton)
            if (::fadeText.isInitialized) windowManager.removeView(fadeText)
        }
    }
}
