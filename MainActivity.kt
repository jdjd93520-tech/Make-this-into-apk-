package com.example.aimassistdemo

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.graphics.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.*
import kotlin.math.*

class MainActivity : Activity() {
    private lateinit var preview: ScreenPreview
    private lateinit var status: TextView
    private lateinit var projectionManager: MediaProjectionManager
    private var strength = 65
    private var speed = 55
    private val captureRequest = 4108

    // A 15-minute touch-session timer. Touches are observed only by the demo's own pad.
    private val sessionDurationMs = 15L * 60L * 1000L
    private val timerHandler = Handler(Looper.getMainLooper())
    private var sessionRemainingMs = sessionDurationMs
    private var sessionStartedAt = 0L
    private var sessionRunning = false
    private var touchEventCount = 0
    private var touchDownAt = 0L
    private var lastTouchAction = "NO TOUCH YET"
    private var lastTouchX = 0
    private var lastTouchY = 0
    private lateinit var sessionTimer: TextView
    private lateinit var touchReadout: TextView
    private lateinit var touchCounter: TextView
    private lateinit var eventReadout: TextView
    private lateinit var sessionSummary: TextView

    private val timerTick = object : Runnable {
        override fun run() {
            if (!sessionRunning) return
            val elapsed = SystemClock.elapsedRealtime() - sessionStartedAt
            sessionRemainingMs = (sessionRemainingMs - elapsed).coerceAtLeast(0L)
            sessionStartedAt = SystemClock.elapsedRealtime()
            if (sessionRemainingMs == 0L) {
                finishSession()
            } else {
                updateSessionDisplay()
                timerHandler.postDelayed(this, 200L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(13, 18, 29)
        window.navigationBarColor = Color.rgb(13, 18, 29)
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(12))
            setBackgroundColor(Color.rgb(13, 18, 29))
        }
        page.addView(TextView(this).apply {
            text = "SCREEN TRACKER  /  DEMO"
            textSize = 21f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        })
        page.addView(TextView(this).apply {
            text = "Share your screen, then tap an object in the preview to track it"
            textSize = 12f; setTextColor(Color.rgb(156, 173, 198)); setPadding(0, dp(5), 0, dp(10))
        })
        preview = ScreenPreview(this).apply {
            onSelectionChanged = { selected ->
                status.text = if (selected) "TRACKER • OBJECT SELECTED" else "TRACKER • TAP AN OBJECT IN THE PREVIEW"
            }
        }
        page.addView(preview, LinearLayout.LayoutParams(-1, dp(220)))

        status = TextView(this).apply {
            text = "SCREEN • NOT SHARING     |     TRACKER • IDLE"
            textSize = 11f; setTextColor(Color.rgb(156, 173, 198)); setPadding(0, dp(10), 0, dp(7))
        }
        page.addView(status)
        page.addView(makeButton("START SCREEN SHARING", true) { requestScreenCapture() },
            LinearLayout.LayoutParams(-1, dp(48)))
        page.addView(makeButton("STOP SCREEN SHARING", false) { stopCapture() },
            LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(6) })
        addSlider(page, "TRACKING STRENGTH", strength) { strength = it; preview.trackingStrength = it }
        addSlider(page, "TRACKING SPEED", speed) { speed = it; preview.trackingSpeed = it }
        page.addView(TextView(this).apply {
            text = "TOUCH PAD • IN-APP DEMO ONLY"
            textSize = 11f
            setTextColor(Color.WHITE)
            setPadding(0, dp(8), 0, dp(4))
            typeface = Typeface.DEFAULT_BOLD
        })
        touchReadout = TextView(this).apply {
            text = "Touch the pad and drag to move the marker"
            textSize = 10f
            setTextColor(Color.rgb(156, 173, 198))
            setPadding(0, 0, 0, dp(4))
        }
        page.addView(touchReadout)
        page.addView(LocalTouchPad(this).apply {
            onTouchEventChanged = { x, y, action, heldMs ->
                recordTouchEvent(x, y, action, heldMs)
            }
        }, LinearLayout.LayoutParams(-1, dp(92)))

        page.addView(TextView(this).apply {
            text = "15-MINUTE TOUCH SESSION"
            textSize = 12f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(12), 0, dp(4))
        })
        sessionTimer = TextView(this).apply {
            text = "15:00"
            textSize = 32f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(69, 230, 174))
            setPadding(0, dp(2), 0, dp(4))
        }
        page.addView(sessionTimer)

        val controlsTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }
        controlsTop.addView(makeButton("START", true) { startSession() },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(4) })
        controlsTop.addView(makeButton("PAUSE", false) { pauseSession() },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(4) })
        page.addView(controlsTop)

        val controlsBottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }
        controlsBottom.addView(makeButton("RESUME", false) { resumeSession() },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(4); topMargin = dp(5) })
        controlsBottom.addView(makeButton("RESET", false) { resetSession() },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(4); topMargin = dp(5) })
        page.addView(controlsBottom)

        touchCounter = TextView(this).apply {
            text = "TOUCH EVENTS • 0"
            textSize = 11f
            setTextColor(Color.WHITE)
            setPadding(0, dp(8), 0, dp(2))
        }
        page.addView(touchCounter)
        eventReadout = TextView(this).apply {
            text = "Last event: none"
            textSize = 10f
            setTextColor(Color.rgb(156, 173, 198))
        }
        page.addView(eventReadout)
        sessionSummary = TextView(this).apply {
            text = "Session summary will appear when the 15 minutes finish."
            textSize = 10f
            setTextColor(Color.rgb(156, 173, 198))
            setPadding(0, dp(6), 0, dp(4))
        }
        page.addView(sessionSummary)
        page.addView(TextView(this).apply {
            text = "LOCAL PREVIEW ONLY • Frames are processed on-device. No images are uploaded and no touches are sent to other apps."
            textSize = 10f; setTextColor(Color.rgb(255, 190, 110)); setPadding(0, dp(8), 0, 0)
        })
        val scroller = ScrollView(this).apply {
            isFillViewport = true
            addView(page)
        }
        setContentView(scroller)
        updateSessionDisplay()

        ScreenCaptureService.onFrame = { bitmap -> runOnUiThread { preview.setFrame(bitmap); status.text = preview.statusText() } }
        ScreenCaptureService.onState = { message -> runOnUiThread { status.text = message } }
    }

    private fun requestScreenCapture() {
        try { startActivityForResult(projectionManager.createScreenCaptureIntent(), captureRequest) }
        catch (e: Exception) { status.text = "SCREEN CAPTURE ERROR • ${e.message ?: "unavailable"}" }
    }

    @Deprecated("MediaProjection permission result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == captureRequest) {
            if (resultCode == RESULT_OK && data != null) {
                val service = Intent(this, ScreenCaptureService::class.java).apply {
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
                }
                if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
                status.text = "SCREEN • STARTING CAPTURE…"
            } else status.text = "SCREEN • PERMISSION DECLINED"
        }
    }

    private fun stopCapture() {
        stopService(Intent(this, ScreenCaptureService::class.java))
        preview.clearSelection()
        status.text = "SCREEN • STOPPED"
    }

    override fun onDestroy() {
        timerHandler.removeCallbacks(timerTick)
        ScreenCaptureService.onFrame = null
        ScreenCaptureService.onState = null
        super.onDestroy()
    }

    private fun startSession() {
        sessionRemainingMs = sessionDurationMs
        touchEventCount = 0
        touchDownAt = 0L
        lastTouchAction = "SESSION STARTED"
        sessionRunning = true
        sessionStartedAt = SystemClock.elapsedRealtime()
        sessionSummary.text = "Session running. Touch the pad to record down, move, hold and release events."
        timerHandler.removeCallbacks(timerTick)
        updateSessionDisplay()
        timerHandler.postDelayed(timerTick, 200L)
    }

    private fun pauseSession() {
        if (!sessionRunning) return
        val elapsed = SystemClock.elapsedRealtime() - sessionStartedAt
        sessionRemainingMs = (sessionRemainingMs - elapsed).coerceAtLeast(0L)
        sessionRunning = false
        timerHandler.removeCallbacks(timerTick)
        lastTouchAction = "SESSION PAUSED"
        updateSessionDisplay()
    }

    private fun resumeSession() {
        if (sessionRunning || sessionRemainingMs <= 0L) return
        sessionRunning = true
        sessionStartedAt = SystemClock.elapsedRealtime()
        lastTouchAction = "SESSION RESUMED"
        timerHandler.removeCallbacks(timerTick)
        updateSessionDisplay()
        timerHandler.postDelayed(timerTick, 200L)
    }

    private fun resetSession() {
        sessionRunning = false
        timerHandler.removeCallbacks(timerTick)
        sessionRemainingMs = sessionDurationMs
        touchEventCount = 0
        touchDownAt = 0L
        lastTouchAction = "SESSION RESET"
        sessionSummary.text = "Session summary will appear when the 15 minutes finish."
        updateSessionDisplay()
    }

    private fun finishSession() {
        sessionRunning = false
        sessionRemainingMs = 0L
        timerHandler.removeCallbacks(timerTick)
        lastTouchAction = "SESSION COMPLETE"
        sessionSummary.text = "15-minute session complete • Recorded touch events: $touchEventCount"
        updateSessionDisplay()
    }

    private fun recordTouchEvent(x: Int, y: Int, action: String, heldMs: Long) {
        lastTouchX = x
        lastTouchY = y
        if (action == "DOWN") touchDownAt = SystemClock.elapsedRealtime()
        if (sessionRunning) touchEventCount++
        val holdText = if (action == "UP" || action == "CANCEL") " • held ${heldMs}ms" else ""
        touchReadout.text = "TOUCH • x: $x  y: $y  • $action$holdText"
        lastTouchAction = "$action at ($x, $y)$holdText"
        updateSessionDisplay()
    }

    private fun updateSessionDisplay() {
        val ms = sessionRemainingMs.coerceAtLeast(0L)
        val minutes = ms / 60000L
        val seconds = (ms % 60000L) / 1000L
        if (::sessionTimer.isInitialized) {
            sessionTimer.text = String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
            sessionTimer.setTextColor(if (sessionRunning) Color.rgb(69, 230, 174) else Color.rgb(255, 190, 110))
        }
        if (::touchCounter.isInitialized) {
            touchCounter.text = "TOUCH EVENTS • $touchEventCount"
        }
        if (::eventReadout.isInitialized) {
            eventReadout.text = "Last event: $lastTouchAction"
        }
    }

    private fun addSlider(parent: LinearLayout, label: String, initial: Int, changed: (Int) -> Unit) {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, 0) }
        val heading = TextView(this).apply { text = "$label • $initial%"; textSize = 11f; setTextColor(Color.WHITE) }
        val seek = SeekBar(this).apply {
            max = 100; progress = initial
            progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(67, 190, 157))
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.rgb(67, 190, 157))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, user: Boolean) { heading.text = "$label • $p%"; changed(p) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        wrap.addView(heading); wrap.addView(seek); parent.addView(wrap)
    }

    private fun makeButton(label: String, primary: Boolean, action: () -> Unit) = Button(this).apply {
        text = label; textSize = 13f; setTextColor(Color.WHITE)
        backgroundTintList = android.content.res.ColorStateList.valueOf(if (primary) Color.rgb(29, 130, 111) else Color.rgb(48, 60, 79))
        setOnClickListener { action() }
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

/** Preview + simple local color-template tracker. Tap to select a patch; it searches nearby frames. */
private class ScreenPreview(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var frame: Bitmap? = null
    private var template: IntArray? = null
    private var templateW = 25
    private var templateH = 25
    private var trackedX = 0f
    private var trackedY = 0f
    private var selected = false
    private var frameRect = RectF()
    var trackingStrength = 65
    var trackingSpeed = 55
    var onSelectionChanged: ((Boolean) -> Unit)? = null

    fun setFrame(bitmap: Bitmap) {
        val previous = frame
        frame = bitmap
        if (selected) track(bitmap)
        if (previous != null && previous !== bitmap && !previous.isRecycled) previous.recycle()
        invalidate()
    }

    fun clearSelection() { selected = false; template = null; invalidate() }
    fun statusText() = if (selected) "SCREEN • SHARING     |     TRACKER • FOLLOWING SELECTED PATCH" else "SCREEN • SHARING     |     TRACKER • TAP AN OBJECT"

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(20, 29, 44))
        val bitmap = frame
        if (bitmap == null) {
            paint.color = Color.rgb(156, 173, 198); paint.textSize = 13f
            canvas.drawText("Screen preview appears here after permission", dp(16).toFloat(), height / 2f, paint)
            return
        }
        val scale = min(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val dw = bitmap.width * scale; val dh = bitmap.height * scale
        val left = (width - dw) / 2f; val top = (height - dh) / 2f
        frameRect.set(left, top, left + dw, top + dh)
        canvas.drawBitmap(bitmap, null, frameRect, paint)
        if (selected) {
            val sx = frameRect.left + trackedX * scale
            val sy = frameRect.top + trackedY * scale
            val rw = templateW * scale; val rh = templateH * scale
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f; paint.color = Color.rgb(69, 230, 174)
            canvas.drawRoundRect(sx - rw / 2f, sy - rh / 2f, sx + rw / 2f, sy + rh / 2f, 7f, 7f, paint)
            paint.style = Paint.Style.FILL; paint.textSize = 11f
            canvas.drawText("SELECTED OBJECT", frameRect.left + 8f, frameRect.top + 18f, paint)
        }
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.action == android.view.MotionEvent.ACTION_UP) {
            val bitmap = frame ?: return true
            if (!frameRect.contains(event.x, event.y)) return true
            val x = ((event.x - frameRect.left) / frameRect.width() * bitmap.width).roundToInt().coerceIn(0, bitmap.width - 1)
            val y = ((event.y - frameRect.top) / frameRect.height() * bitmap.height).roundToInt().coerceIn(0, bitmap.height - 1)
            select(bitmap, x, y)
            performClick()
            return true
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun select(bitmap: Bitmap, x: Int, y: Int) {
        templateW = min(25, bitmap.width).let { if (it % 2 == 0) it - 1 else it }
        templateH = min(25, bitmap.height).let { if (it % 2 == 0) it - 1 else it }
        val pixels = IntArray(templateW * templateH)
        bitmap.getPixels(pixels, 0, templateW, (x - templateW / 2).coerceIn(0, bitmap.width - templateW),
            (y - templateH / 2).coerceIn(0, bitmap.height - templateH), templateW, templateH)
        template = pixels; trackedX = x.toFloat(); trackedY = y.toFloat(); selected = true
        onSelectionChanged?.invoke(true); invalidate()
    }

    private fun track(bitmap: Bitmap) {
        val ref = template ?: return
        val radius = (45 + trackingStrength).coerceIn(45, 145)
        val step = if (trackingSpeed > 75) 7 else 10
        val halfW = templateW / 2; val halfH = templateH / 2
        val minX = (trackedX.toInt() - radius).coerceIn(halfW, bitmap.width - halfW - 1)
        val maxX = (trackedX.toInt() + radius).coerceIn(halfW, bitmap.width - halfW - 1)
        val minY = (trackedY.toInt() - radius).coerceIn(halfH, bitmap.height - halfH - 1)
        val maxY = (trackedY.toInt() + radius).coerceIn(halfH, bitmap.height - halfH - 1)
        val sample = IntArray(templateW * templateH)
        var bestScore = Long.MAX_VALUE; var bestX = trackedX.toInt(); var bestY = trackedY.toInt()
        var cy = minY
        while (cy <= maxY) {
            var cx = minX
            while (cx <= maxX) {
                bitmap.getPixels(sample, 0, templateW, cx - halfW, cy - halfH, templateW, templateH)
                var score = 0L
                var i = 0
                while (i < sample.size) {
                    val a = sample[i]; val b = ref[i]
                    score += abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))
                    i += 3 // subsample pixels to keep tracking lightweight
                }
                if (score < bestScore) { bestScore = score; bestX = cx; bestY = cy }
                cx += step
            }
            cy += step
        }
        val confidenceGate = (templateW * templateH / 3L) * (255L * 3L) * (100 - trackingStrength).coerceAtLeast(10) / 100L
        if (bestScore < confidenceGate) {
            val blend = (trackingSpeed / 100f * 0.85f).coerceIn(0.08f, 0.9f)
            trackedX += (bestX - trackedX) * blend
            trackedY += (bestY - trackedY) * blend
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}


/**
 * A touch interaction pad confined to this Activity.
 * It visualizes finger position and never dispatches input to other apps.
 */
private class LocalTouchPad(context: Context) : View(context) {
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(29, 40, 58)
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(67, 190, 157)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(69, 230, 174)
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(156, 173, 198)
        textSize = dp(10).toFloat()
    }

    private var markerX = 0.5f
    private var markerY = 0.5f
    private var pressed = false
    var onTouchEventChanged: ((Int, Int, String, Long) -> Unit)? = null
    private var downAt = 0L

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bounds = RectF(1f, 1f, width - 1f, height - 1f)
        canvas.drawRoundRect(bounds, dp(10).toFloat(), dp(10).toFloat(), backgroundPaint)
        canvas.drawRoundRect(bounds, dp(10).toFloat(), dp(10).toFloat(), borderPaint)
        val cx = markerX * width
        val cy = markerY * height
        canvas.drawLine(cx - dp(13), cy, cx + dp(13), cy, markerPaint)
        canvas.drawLine(cx, cy - dp(13), cx, cy + dp(13), markerPaint)
        canvas.drawCircle(cx, cy, dp(5).toFloat(), markerPaint)
        canvas.drawText(if (pressed) "FINGER DOWN" else "TOUCH / DRAG HERE",
            dp(10).toFloat(), height - dp(9).toFloat(), textPaint)
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        val actionName = when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> "DOWN"
            android.view.MotionEvent.ACTION_MOVE -> "MOVE"
            android.view.MotionEvent.ACTION_UP -> "UP"
            android.view.MotionEvent.ACTION_CANCEL -> "CANCEL"
            else -> return true
        }
        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            downAt = SystemClock.elapsedRealtime()
        }
        markerX = (event.x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
        markerY = (event.y / height.coerceAtLeast(1)).coerceIn(0f, 1f)
        pressed = actionName == "DOWN" || actionName == "MOVE"
        val heldMs = if (downAt > 0L) (SystemClock.elapsedRealtime() - downAt).coerceAtLeast(0L) else 0L
        onTouchEventChanged?.invoke(
            (markerX * 1000).toInt(),
            (markerY * 1000).toInt(),
            actionName,
            heldMs
        )
        if (actionName == "UP" || actionName == "CANCEL") {
            downAt = 0L
            performClick()
        }
        invalidate()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
