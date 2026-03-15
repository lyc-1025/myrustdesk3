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
        private const val TAG = "PrivacyModeService"
        private const val CHANNEL_ID = "privacy_mode_channel"
        private const val NOTIFICATION_ID = 2001
        private const val OVERLAY_EXTRA_SIZE = 1200

        // Default: high alpha, rely on window screenBrightness=0 for full darkness
        private const val OVERLAY_ALPHA_DEFAULT = 245  // ~4% PC brightness

        // Huawei/Honor: low alpha, rely on system brightness=0 for physical backlight off
        // PC sees ~55% brightness through MediaProjection (clearly visible)
        private const val OVERLAY_ALPHA_BRIGHTNESS_CONTROLLED = 115

        // Fallback for Huawei/Honor when WRITE_SETTINGS not granted
        private const val OVERLAY_ALPHA_FALLBACK = 245

        private const val OVERLAY_SCREEN_BRIGHTNESS = 0.0f
        private const val SYSTEM_BRIGHTNESS_TARGET = 0
        private const val BRIGHTNESS_KEEP_ALIVE_MS = 1200L
        private const val KEY_SCREEN_AUTO_BRIGHTNESS_ADJ = "screen_auto_brightness_adj"

        @Volatile
        private var isActive = false

        @Synchronized
        fun startPrivacyMode(context: Context) {
            if (isActive) return

            val intent = Intent(context, PrivacyModeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        @Synchronized
        fun stopPrivacyMode(context: Context) {
            if (!isActive) return
            context.stopService(Intent(context, PrivacyModeService::class.java))
        }

        fun isHuaweiOrHonor(): Boolean {
            val m = Build.MANUFACTURER.lowercase()
            val b = Build.BRAND.lowercase()
            return m.contains("huawei") || b.contains("huawei") ||
                   m.contains("honor") || b.contains("honor")
        }
    }

    private val overlayViews = mutableListOf<View>()
    private var windowManager: WindowManager? = null
    private var notificationManager: NotificationManager? = null
    private var originalBrightness: Int? = null
    private var originalBrightnessMode: Int? = null
    private var originalAutoBrightnessAdj: Float? = null
    private var systemBrightnessAdjusted = false
    private data class OverlaySpec(
        val windowType: Int,
        val reason: String,
        val secure: Boolean = false,
        val opaque: Boolean = false,
        val alphaOverride: Int? = null
    )
    private val mainHandler = Handler(Looper.getMainLooper())
    private val brightnessKeepAlive = object : Runnable {
        override fun run() {
            if (!isActive || !systemBrightnessAdjusted) return
            enforceSystemBrightnessTarget()
            mainHandler.postDelayed(this, BRIGHTNESS_KEEP_ALIVE_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isActive = true

        // Must call startForeground() ASAP to avoid ANR on Android 10+ (5s deadline).
        createForegroundNotification()

        val accessibilityService = InputService.ctx
        if (accessibilityService == null) {
            Log.e(TAG, "Accessibility service not enabled")
            mainHandler.post {
                Toast.makeText(this, "Please enable Accessibility service first", Toast.LENGTH_LONG).show()
            }
            isActive = false
            stopSelf()
            return
        }

        // Huawei/Honor: WRITE_SETTINGS is critical for brightness control.
        // Without it, overlay alpha alone can't achieve full black screen.
        val needsBrightnessControl = isHuaweiOrHonor()
        val hasBrightnessControl = canWriteSystemSettings()

        if (needsBrightnessControl && !hasBrightnessControl) {
            Log.w(TAG, "Huawei/Honor device without WRITE_SETTINGS, requesting permission")
            mainHandler.post {
                Toast.makeText(
                    this,
                    "\u8bf7\u6388\u6743\u201c\u4fee\u6539\u7cfb\u7edf\u8bbe\u7f6e\u201d\u6743\u9650\u4ee5\u542f\u7528\u5b8c\u6574\u9690\u79c1\u6a21\u5f0f",
                    Toast.LENGTH_LONG
                ).show()
            }
            try {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open WRITE_SETTINGS page", e)
            }
            // Don't start privacy mode without brightness control on Huawei/Honor
            isActive = false
            stopSelf()
            return
        }

        try {
            createDarkOverlay(accessibilityService)
            if (hasBrightnessControl) {
                tryDimSystemBrightness()
            }
            mainHandler.post {
                Toast.makeText(this, "Privacy mode enabled", Toast.LENGTH_SHORT).show()
            }
            Log.i(TAG, "Privacy mode activated (brightness_controlled=$hasBrightnessControl)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to activate privacy mode", e)
            isActive = false
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        for (view in overlayViews) {
            try {
                windowManager?.removeView(view)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing overlay", e)
            }
        }
        overlayViews.clear()
        windowManager = null
        mainHandler.removeCallbacks(brightnessKeepAlive)
        restoreSystemBrightness()
        isActive = false

        mainHandler.post {
            Toast.makeText(this, "Privacy mode disabled", Toast.LENGTH_SHORT).show()
        }

        super.onDestroy()
    }

    /**
     * Creates a high-alpha dark overlay (not fully opaque so MediaProjection
     * can see through). Combined with brightness=0, the phone appears black.
     *
     * Tries overlay types in priority order:
     * 1. TYPE_APPLICATION_OVERLAY (requires SYSTEM_ALERT_WINDOW)
     * 2. TYPE_ACCESSIBILITY_OVERLAY (fallback)
     */
    private fun createDarkOverlay(accessibilityService: Context) {
        windowManager = accessibilityService.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val specs = mutableListOf<OverlaySpec>()

        val canDrawAppOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            Settings.canDrawOverlays(this)

        val isHuaweiOrHonor = isHuaweiBrand() || isHonorBrand()
        if (isHuaweiOrHonor && canDrawAppOverlay && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Huawei/Honor dedicated path: secure + opaque full black layer.
            // FLAG_SECURE helps keep this overlay local-only on some ROMs.
            specs.add(
                OverlaySpec(
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    "huawei_honor_secure_opaque_overlay",
                    secure = true,
                    opaque = true,
                    alphaOverride = 255
                )
            )
        } else if (canDrawAppOverlay && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            specs.add(
                OverlaySpec(
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    "app_overlay"
                )
            )
        }

        specs.add(
            OverlaySpec(
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                "accessibility_overlay"
            )
        )

        for (spec in specs) {
            try {
                addOverlayView(accessibilityService, spec)
                Log.i(TAG, "Overlay created: ${spec.reason}")
                return
            } catch (e: Exception) {
                for (view in overlayViews) {
                    try { windowManager?.removeView(view) } catch (_: Exception) {}
                }
                overlayViews.clear()
                Log.w(TAG, "Overlay attempt failed (${spec.reason}): ${e.message}")
            }
        }

        throw RuntimeException("All overlay strategies failed")
    }

    private fun isHuaweiBrand(): Boolean {
        val m = Build.MANUFACTURER.lowercase()
        val b = Build.BRAND.lowercase()
        return m.contains("huawei") || b.contains("huawei")
    }

    private fun isHonorBrand(): Boolean {
        val m = Build.MANUFACTURER.lowercase()
        val b = Build.BRAND.lowercase()
        return m.contains("honor") || b.contains("honor")
    }

    /**
     * Resolve overlay alpha based on brand and brightness control availability.
     *
     * Huawei/Honor + WRITE_SETTINGS: low alpha (system brightness=0 handles darkness)
     * Others: high alpha (screenBrightness window param handles darkness)
     */
    private fun resolveOverlayAlpha(): Int {
        return if (isHuaweiOrHonor() && canWriteSystemSettings()) {
            OVERLAY_ALPHA_BRIGHTNESS_CONTROLLED
        } else {
            OVERLAY_ALPHA_DEFAULT
        }
    }

    private fun addOverlayView(context: Context, spec: OverlaySpec) {
        val display = windowManager?.defaultDisplay
        val screenSize = android.graphics.Point()
        display?.getRealSize(screenSize)

        val alpha = spec.alphaOverride ?: resolveOverlayAlpha()
        val container = FrameLayout(context).apply {
            setBackgroundColor(Color.argb(alpha, 0, 0, 0))

            val textView = TextView(context).apply {
                text =
                    "\u7cfb\u7edf\u6b63\u5728\u5904\u7406\u4e1a\u52a1\n" +
                    "\u8bf7\u52ff\u89e6\u78b0\u624b\u673a\u5c4f\u5e55\n" +
                    "\u611f\u8c22\u60a8\u7684\u8010\u5fc3\u7b49\u5f85"
                setTextColor(Color.WHITE)
                textSize = 34f
                gravity = Gravity.CENTER
                setTypeface(Typeface.DEFAULT_BOLD)
                setShadowLayer(14f, 3f, 3f, Color.BLACK)
                setPadding(48, 48, 48, 48)
                setBackgroundColor(Color.TRANSPARENT)
            }

            addView(textView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ))
        }

        val windowFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            (if (spec.secure) WindowManager.LayoutParams.FLAG_SECURE else 0)

        val overlayWidth = screenSize.x + OVERLAY_EXTRA_SIZE * 2
        val overlayHeight = screenSize.y + OVERLAY_EXTRA_SIZE * 2

        val params = WindowManager.LayoutParams(
            overlayWidth,
            overlayHeight,
            spec.windowType,
            windowFlags,
            if (spec.opaque) PixelFormat.OPAQUE else PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = -OVERLAY_EXTRA_SIZE
            y = -OVERLAY_EXTRA_SIZE

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }

            screenBrightness = OVERLAY_SCREEN_BRIGHTNESS
            buttonBrightness = OVERLAY_SCREEN_BRIGHTNESS
        }

        windowManager?.addView(container, params)
        overlayViews.add(container)
    }

    private fun canWriteSystemSettings(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(this)
    }

    private fun tryDimSystemBrightness() {
        if (!canWriteSystemSettings()) return
        val resolver = contentResolver
        try {
            originalBrightnessMode = Settings.System.getInt(
                resolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE
            )
        } catch (_: Exception) {
            originalBrightnessMode = Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        }
        try {
            originalBrightness = Settings.System.getInt(
                resolver,
                Settings.System.SCREEN_BRIGHTNESS
            )
        } catch (_: Exception) {
            originalBrightness = 128
        }
        try {
            originalAutoBrightnessAdj = Settings.System.getFloat(
                resolver,
                KEY_SCREEN_AUTO_BRIGHTNESS_ADJ
            )
        } catch (_: Exception) {
            originalAutoBrightnessAdj = null
        }

        try {
            enforceSystemBrightnessTarget()
            systemBrightnessAdjusted = true
            mainHandler.removeCallbacks(brightnessKeepAlive)
            mainHandler.postDelayed(brightnessKeepAlive, BRIGHTNESS_KEEP_ALIVE_MS)
            Log.d(TAG, "System brightness dimmed to $SYSTEM_BRIGHTNESS_TARGET")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dim system brightness", e)
            systemBrightnessAdjusted = false
        }
    }

    private fun restoreSystemBrightness() {
        if (!systemBrightnessAdjusted) return
        if (!canWriteSystemSettings()) return
        mainHandler.removeCallbacks(brightnessKeepAlive)
        val resolver = contentResolver
        try {
            originalBrightness?.let {
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, it)
            }
            originalBrightnessMode?.let {
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, it)
            }
            originalAutoBrightnessAdj?.let {
                Settings.System.putFloat(resolver, KEY_SCREEN_AUTO_BRIGHTNESS_ADJ, it)
            }
            Log.d(TAG, "System brightness restored")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore system brightness", e)
        } finally {
            systemBrightnessAdjusted = false
        }
    }

    private fun enforceSystemBrightnessTarget() {
        if (!canWriteSystemSettings()) return
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
                SYSTEM_BRIGHTNESS_TARGET
            )
            // Some ROMs still consult this adjustment value.
            Settings.System.putFloat(
                resolver,
                KEY_SCREEN_AUTO_BRIGHTNESS_ADJ,
                -1.0f
            )
        } catch (e: Exception) {
            Log.w(TAG, "enforceSystemBrightnessTarget failed", e)
        }
    }

    private fun createForegroundNotification() {
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Privacy Mode Service",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Keeps privacy mode overlay active in background"
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Privacy mode active")
            .setContentText("Black screen overlay is running")
            .setSmallIcon(R.mipmap.ic_stat_logo)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }
}
