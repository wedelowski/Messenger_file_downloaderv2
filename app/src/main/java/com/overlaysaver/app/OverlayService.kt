package com.overlaysaver.app

import android.app.*
import android.app.AlertDialog
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
        const val KEY_Y = "pos_y"

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

    private val pollRunnable = object : Runnable {
        override fun run() {
            updateVisibilityForForegroundApp()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, buildNotification())

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: return START_NOT_STICKY
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

        setupOverlayViews()
        hideAllViews()
        handler.post(pollRunnable)
        setupPersistentCapture()
        return START_STICKY
    }

    private fun setupPersistentCapture() {
        val projection = mediaProjection ?: return
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection.createVirtualDisplay(
            "OverlaySaverCapture", screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, handler
        )
    }

    private fun dp(value: Int): Int = (value * screenDensity / 160f).toInt()

    private fun roundedDrawable(colorInt: Int, radiusDp: Int): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(colorInt)
        }
    }

    private fun updateVisibilityForForegroundApp() {
        val fg = ForegroundAccessibilityService.currentForegroundPackage
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
    }

    private fun hideAllViews() {
        viewsVisible = false
        saveButton.visibility = View.GONE
        dotsButton.visibility = View.GONE
        fadeText.visibility = View.GONE
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Overlay Saver", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Overlay Saver running")
            .setContentText("Tap the floating button to save what's on screen.")
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .build()
    }

    private fun setupOverlayViews() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedY = prefs.getInt(KEY_Y, screenHeight / 3)

        val overlayType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        saveButton = TextView(this).apply {
            text = "\u2B07"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            background = roundedDrawable(0xFF0866FF.toInt(), 18)
            elevation = dp(4).toFloat()
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        saveParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(8)
            y = savedY
        }
        windowManager.addView(saveButton, saveParams)
        saveButton.setOnClickListener { onSaveTapped() }

        dotsButton = TextView(this).apply {
            text = "\u22EE"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            background = roundedDrawable(0xB3222222.toInt(), 14)
            elevation = dp(3).toFloat()
            setPadding(dp(20), dp(10), dp(20), dp(10))
        }
        dotsParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(8)
            y = savedY
        }
        windowManager.addView(dotsButton, dotsParams)
        dotsButton.setOnClickListener { showMenu() }

        fadeText = TextView(this).apply {
            text = "Select an image or video to download it"
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            background = roundedDrawable(0xCC000000.toInt(), 10)
            setPadding(dp(14), dp(8), dp(14), dp(8))
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
            x = dp(16)
            y = savedY
        }
        windowManager.addView(fadeText, fadeParams)

        saveButton.post { relayoutStack(savedY, prefs) }
        dotsButton.post { relayoutStack(savedY, prefs) }

        makeDraggable(saveButton, saveParams) { newY ->
            relayoutStack(newY, prefs)
        }
    }

    private fun relayoutStack(baseY: Int, prefs: android.content.SharedPreferences) {
        if (saveButton.height == 0 || dotsButton.height == 0) return

        saveParams.y = baseY
        dotsParams.y = baseY + saveButton.height + dp(6)
        fadeParams.y = dotsParams.y + dotsButton.height + dp(6)

        windowManager.updateViewLayout(saveButton, saveParams)
        windowManager.updateViewLayout(dotsButton, dotsParams)
        windowManager.updateViewLayout(fadeText, fadeParams)
        prefs.edit().putInt(KEY_Y, baseY).apply()
    }

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
                    val newY = (startParamY + dy).coerceIn(0, screenHeight - dp(200))
                    params.y = newY
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
                "Move to top" -> relayoutStack(dp(60), prefs)
                "Move to middle" -> relayoutStack(screenHeight / 2 - dp(80), prefs)
                "Move to bottom" -> relayoutStack(screenHeight - dp(220), prefs)
                "Save full screen now" -> captureScreen()
                "Self-destruct (delete app)" -> confirmSelfDestructStep1()
            }
            true
        }
        popup.show()
    }

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
        val uninstallIntent = Intent(Intent.ACTION_DELETE).apply {
            data = android.net.Uri.parse("package:$packageName")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(uninstallIntent)
        stopSelf()
    }

    private fun onSaveTapped() {
        captureScreen()
    }

    private fun showFadeHint() {
        fadeText.visibility = View.VISIBLE
        fadeText.alpha = 1f
        fadeText.animate().alpha(0f).setStartDelay(1000).setDuration(400)
            .withEndAction { fadeText.visibility = View.GONE }.start()
    }

    private fun captureScreen() {
        if (imageReader == null || virtualDisplay == null) {
            setupPersistentCapture()
            showFadeHint()
            return
        }

        val image: Image? = imageReader?.acquireLatestImage()
        if (image == null) {
            showFadeHint()
            return
        }
        val bitmap = imageToBitmap(image)
        image.close()
        saveBitmap(bitmap)
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
