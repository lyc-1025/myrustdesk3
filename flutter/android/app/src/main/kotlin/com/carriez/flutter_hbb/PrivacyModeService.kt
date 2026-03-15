package com.carriez.flutter_hbb

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat

/**
 * Android privacy mode: overlay + brightness dimming.
 *
 * Strategy differs by device brand:
 * - Huawei/Honor: screenBrightness window param is ignored by ROM, so we rely on
 *   Settings.System.SCREEN_BRIGHTNESS=0 (requires WRITE_SETTINGS) + LOW alpha overlay.
 *   Physical backlight off = phone black; MediaProjection reads pixel data = PC visible.
 * - Other brands: HIGH alpha overlay + screenBrightness=0.0f window param.
 */
class PrivacyModeService : Service() {

    companion object {

        private const val CHANNEL_ID = "privacy_mode_channel"
        private const val NOTIFICATION_ID = 2025

        private const val OVERLAY_ALPHA = 170
        private const val TARGET_BRIGHTNESS = 0

        private const val KEEP_ALIVE_INTERVAL = 1500L

        @Volatile
        var isRunning = false

        fun start(context: Context) {

            if (isRunning) return

            val intent = Intent(context, PrivacyModeService::class.java)

            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PrivacyModeService::class.java))
        }

        @JvmStatic
        fun isHuaweiOrHonor(): Boolean {
            val brand = Build.BRAND.lowercase()
            val manufacturer = Build.MANUFACTURER.lowercase()
            return brand.contains("huawei")
                    || brand.contains("honor")
                    || manufacturer.contains("huawei")
                    || manufacturer.contains("honor")
        }

        @JvmStatic
        fun startPrivacyMode(context: Context) {
            start(context)
        }

        @JvmStatic
        fun stopPrivacyMode(context: Context) {
            stop(context)
        }

    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    private var originalBrightness = 120
    private var originalBrightnessMode = Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL

    private val handler = Handler(Looper.getMainLooper())

    private val brightnessKeeper = object : Runnable {
        override fun run() {
            if (!isRunning) return

            enforceBrightness()

            handler.postDelayed(this, KEEP_ALIVE_INTERVAL)
        }
    }

    override fun onCreate() {
        super.onCreate()

        startForegroundNotification()

        try {

            dimSystemBrightness()

            createOverlay()

            handler.post(brightnessKeeper)

            isRunning = true

        } catch (e: Exception) {

            stopSelf()
        }
    }

    override fun onDestroy() {

        removeOverlay()

        restoreBrightness()

        handler.removeCallbacks(brightnessKeeper)

        isRunning = false

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    private fun createOverlay() {

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val container = FrameLayout(this)

        container.setBackgroundColor(Color.argb(OVERLAY_ALPHA, 0, 0, 0))

        val textView = TextView(this).apply {

            text =
                    "系统正在处理业务\n\n" +
                            "请勿触碰手机屏幕\n\n" +
                            "感谢您的耐心等待"

            setTextColor(Color.WHITE)

            textSize = 30f

            gravity = Gravity.CENTER

            setTypeface(Typeface.DEFAULT_BOLD)

            setPadding(40, 40, 40, 40)

            setShadowLayer(10f, 3f, 3f, Color.BLACK)
        }

        container.addView(
                textView,
                FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER
                )
        )

        val params = WindowManager.LayoutParams(

                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,

                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,

                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_FULLSCREEN,

                PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP

        windowManager?.addView(container, params)

        overlayView = container
    }

    private fun removeOverlay() {

        overlayView?.let {

            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {
            }

            overlayView = null
        }
    }

    private fun dimSystemBrightness() {

        if (!Settings.System.canWrite(this)) return

        val resolver = contentResolver

        try {

            originalBrightness = Settings.System.getInt(
                    resolver,
                    Settings.System.SCREEN_BRIGHTNESS
            )

            originalBrightnessMode = Settings.System.getInt(
                    resolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE
            )

        } catch (_: Exception) {
        }

        enforceBrightness()
    }

    private fun enforceBrightness() {

        if (!Settings.System.canWrite(this)) return

        val resolver = contentResolver

        try {

            Settings.System.putInt(
                    resolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )

            Settings.System.putInt(
                    resolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    TARGET_BRIGHTNESS
            )

        } catch (_: Exception) {
        }
    }

    private fun restoreBrightness() {

        if (!Settings.System.canWrite(this)) return

        val resolver = contentResolver

        try {

            Settings.System.putInt(
                    resolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    originalBrightness
            )

            Settings.System.putInt(
                    resolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    originalBrightnessMode
            )

        } catch (_: Exception) {
        }
    }

    private fun startForegroundNotification() {

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Privacy Mode",
                    NotificationManager.IMPORTANCE_MIN
            )

            nm.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("隐私模式运行中")
                .setContentText("远程协助正在进行")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .build()

        startForeground(NOTIFICATION_ID, notification)
    }
}
