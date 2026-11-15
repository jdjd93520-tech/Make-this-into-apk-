package com.example.aimassistdemo

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager

class ScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var worker: HandlerThread? = null
    private var handler: Handler? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (projection != null) return START_NOT_STICKY
        createNotificationChannel()
        val notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID).setContentTitle("Screen tracker active")
                .setContentText("Screen frames are being processed locally")
                .setSmallIcon(android.R.drawable.ic_menu_view).setOngoing(true).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setContentTitle("Screen tracker active")
                .setContentText("Screen frames are being processed locally")
                .setSmallIcon(android.R.drawable.ic_menu_view).build()
        }
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTIFICATION_ID, notification)

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode != Activity.RESULT_OK || data == null) { stopSelf(); return START_NOT_STICKY }
        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
            startCapture()
            onState?.invoke("SCREEN • SHARING     |     TRACKER • TAP AN OBJECT")
        } catch (e: Exception) {
            onState?.invoke("CAPTURE ERROR • ${e.message ?: "unable to start"}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startCapture() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels.coerceAtMost(1600)
        val scale = width.toFloat() / metrics.widthPixels
        val height = (metrics.heightPixels * scale).toInt().coerceAtLeast(1)
        val density = (metrics.densityDpi * scale).toInt().coerceAtLeast(1)
        worker = HandlerThread("ScreenFrameReader").also { it.start() }
        handler = Handler(worker!!.looper)
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader!!.setOnImageAvailableListener({ source ->
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width
                val padded = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(buffer)
                val clean = Bitmap.createBitmap(padded, 0, 0, width, height)
                if (clean !== padded) padded.recycle()
                onFrame?.invoke(clean)
            } catch (_: Exception) {
                // A dropped frame is harmless; the next ImageReader callback will retry.
            } finally { image.close() }
        }, handler)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { onState?.invoke("SCREEN • CAPTURE STOPPED"); stopSelf() }
        }, handler)
        virtualDisplay = projection?.createVirtualDisplay(
            "AimAssistScreenPreview", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, handler
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Screen capture", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        virtualDisplay?.release(); virtualDisplay = null
        reader?.close(); reader = null
        projection?.stop(); projection = null
        worker?.quitSafely(); worker = null; handler = null
        stopForeground(true)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_RESULT_CODE = "capture_result_code"
        const val EXTRA_RESULT_DATA = "capture_result_data"
        private const val CHANNEL_ID = "screen_capture"
        private const val NOTIFICATION_ID = 3102
        @Volatile var onFrame: ((Bitmap) -> Unit)? = null
        @Volatile var onState: ((String) -> Unit)? = null
    }
}
