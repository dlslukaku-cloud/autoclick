package com.apkgod.autoclicker.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import com.apkgod.autoclicker.ActionRepository
import com.apkgod.autoclicker.ActionTypes
import com.apkgod.autoclicker.MacroAction
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

class AutoClickAccessibilityService : AccessibilityService() {
    companion object { const val ACTION_REFRESH = "com.apkgod.autoclicker.REFRESH" }

    private val handler = Handler(Looper.getMainLooper())
    private val screenshotExecutor = Executors.newSingleThreadExecutor()
    private var macroThread: Thread? = null
    @Volatile private var running = false
    private var overlay: LinearLayout? = null
    private var overlayButton: Button? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) { if (intent?.action == ACTION_REFRESH) refreshRunState() }
    }

    override fun onServiceConnected() { super.onServiceConnected(); registerReceiverCompat(); showOverlay(); refreshRunState() }
    override fun onInterrupt() = stopMacro()
    override fun onDestroy() { stopMacro(); screenshotExecutor.shutdownNow(); removeOverlay(); runCatching { unregisterReceiver(receiver) }; super.onDestroy() }

    private fun registerReceiverCompat() {
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, IntentFilter(ACTION_REFRESH), Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") run { registerReceiver(receiver, IntentFilter(ACTION_REFRESH)) }
    }

    private fun refreshRunState() { if (ActionRepository.isRunning(this)) startMacro() else stopMacro() }

    private fun startMacro() {
        if (running) return
        val actions = ActionRepository.loadActions(this)
        if (actions.isEmpty()) { ActionRepository.setRunning(this, false); return }
        running = true; overlayButton?.text = "■"
        macroThread = Thread {
            var cycles = 0
            val maxCycles = ActionRepository.repeatCount(this)
            while (running && (maxCycles == 0 || cycles < maxCycles)) {
                val current = ActionRepository.loadActions(this)
                if (current.isEmpty()) break
                for (action in current) {
                    if (!running || Thread.currentThread().isInterrupted) break
                    execute(action)
                }
                cycles++
            }
            handler.post { running = false; ActionRepository.setRunning(this, false); overlayButton?.text = "▶" }
        }.also { it.start() }
    }

    private fun stopMacro() { running = false; macroThread?.interrupt(); macroThread = null; handler.post { overlayButton?.text = "▶" } }

    private fun execute(a: MacroAction) {
        when (a.type) {
            ActionTypes.TAP -> tap(a.x, a.y, 45)
            ActionTypes.DOUBLE_TAP -> { tap(a.x, a.y, 45); sleep(90); tap(a.x, a.y, 45) }
            ActionTypes.LONG_PRESS -> tap(a.x, a.y, a.durationMs.coerceAtLeast(500))
            ActionTypes.SWIPE -> swipe(a.x, a.y, a.x2, a.y2, a.durationMs.coerceIn(80, 15000))
            ActionTypes.WAIT -> sleep(a.durationMs)
            ActionTypes.IMAGE -> findAndClick(a)
        }
        sleep(a.delayMs)
    }

    private fun tap(x: Float, y: Float, duration: Long) {
        val path = android.graphics.Path().apply { moveTo(x, y) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build(), null, null)
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long) {
        val path = android.graphics.Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build(), null, null)
    }

    private fun findAndClick(a: MacroAction) {
        if (Build.VERSION.SDK_INT < 30) return
        val path = a.imagePath ?: return
        val template = BitmapFactory.decodeFile(path) ?: return
        val deadline = System.currentTimeMillis() + a.timeoutMs
        try {
            while (running && System.currentTimeMillis() < deadline) {
                val screen = screenshot()
                if (screen != null) {
                    val point = TemplateMatcher.find(screen, template, a)
                    screen.recycle()
                    if (point != null) { handler.post { tap(point.first, point.second, 45) }; return }
                }
                sleep(220)
            }
        } finally { template.recycle() }
    }

    private fun screenshot(): Bitmap? {
        val latch = CountDownLatch(1)
        var result: Bitmap? = null
        screenshotExecutor.execute {
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, screenshotExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(s: ScreenshotResult) {
                        runCatching {
                            val buffer: HardwareBuffer = s.hardwareBuffer
                            val hw = Bitmap.wrapHardwareBuffer(buffer, s.colorSpace)
                            result = hw?.copy(Bitmap.Config.ARGB_8888, false)
                            hw?.recycle(); buffer.close()
                        }
                        latch.countDown()
                    }
                    override fun onFailure(errorCode: Int) { latch.countDown() }
                })
            } catch (_: Throwable) { latch.countDown() }
        }
        runCatching { latch.await(2200, TimeUnit.MILLISECONDS) }
        return result
    }

    private fun sleep(ms: Long) { try { Thread.sleep(ms.coerceAtLeast(0)) } catch (_: InterruptedException) { Thread.currentThread().interrupt() } }

    private fun showOverlay() {
        if (overlay != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val box = LinearLayout(this).apply { gravity = Gravity.CENTER }
        val b = Button(this).apply {
            text = "▶"; textSize = 14f
            setOnClickListener {
                if (running) { ActionRepository.setRunning(this@AutoClickAccessibilityService, false); stopMacro() }
                else { ActionRepository.setRunning(this@AutoClickAccessibilityService, true); startMacro() }
            }
        }
        box.addView(b, LinearLayout.LayoutParams(64, 64))
        val p = WindowManager.LayoutParams(64, 64, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, android.graphics.PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.END; x = 12; y = 180 }
        runCatching { wm.addView(box, p) }.onSuccess { overlay = box; overlayButton = b }
    }

    private fun removeOverlay() { val wm = getSystemService(WINDOW_SERVICE) as WindowManager; overlay?.let { runCatching { wm.removeView(it) } }; overlay = null; overlayButton = null }
    private fun toast(s: String) = handler.post { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    private object TemplateMatcher {
        data class Gray(val w: Int, val h: Int, val p: IntArray)

        fun find(screen: Bitmap, template: Bitmap, a: MacroAction): Pair<Float, Float>? {
            if (template.width < 6 || template.height < 6 || template.width > screen.width || template.height > screen.height) return null
            val left = a.regionLeft.coerceIn(0, screen.width - template.width)
            val top = a.regionTop.coerceIn(0, screen.height - template.height)
            val right = if (a.regionRight > left) min(a.regionRight, screen.width - template.width) else screen.width - template.width
            val bottom = if (a.regionBottom > top) min(a.regionBottom, screen.height - template.height) else screen.height - template.height
            val tw = min(24, template.width); val th = min(24, template.height)
            val tpl = gray(template, tw, th)
            val sx = template.width.toFloat() / tw; val sy = template.height.toFloat() / th
            val step = max(5, min(template.width, template.height) / 7)
            var best = -1.0; var bx = -1; var by = -1
            var y = top
            while (y <= bottom) {
                var x = left
                while (x <= right) {
                    val score = similarity(screen, x, y, tpl, sx, sy)
                    if (score > best) { best = score; bx = x; by = y }
                    x += step
                }
                y += step
            }
            if (bx < 0 || best < a.confidence) return null
            var rx = bx; var ry = by; var refined = best
            for (yy in max(top, by - step)..min(bottom, by + step)) for (xx in max(left, bx - step)..min(right, bx + step)) {
                val score = similarity(screen, xx, yy, tpl, sx, sy)
                if (score > refined) { refined = score; rx = xx; ry = yy }
            }
            return if (refined >= a.confidence) Pair(rx + template.width / 2f, ry + template.height / 2f) else null
        }

        private fun similarity(screen: Bitmap, x: Int, y: Int, tpl: Gray, sx: Float, sy: Float): Double {
            var mse = 0.0; var n = 0
            for (j in 0 until tpl.h) for (i in 0 until tpl.w) {
                val c = screen.getPixel(min(screen.width - 1, x + (i * sx).toInt()), min(screen.height - 1, y + (j * sy).toInt()))
                val g = .299 * Color.red(c) + .587 * Color.green(c) + .114 * Color.blue(c)
                val d = g - tpl.p[j * tpl.w + i]; mse += d * d; n++
            }
            return 1.0 - (mse / max(1, n)) / 65025.0
        }

        private fun gray(b: Bitmap, w: Int, h: Int): Gray {
            val p = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                val c = b.getPixel(min(b.width - 1, x * b.width / w), min(b.height - 1, y * b.height / h))
                p[y * w + x] = (.299 * Color.red(c) + .587 * Color.green(c) + .114 * Color.blue(c)).toInt()
            }
            return Gray(w, h, p)
        }
    }
}
