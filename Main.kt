package com.example.combopress

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Activity
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.*
import android.widget.Button


class ComboService : AccessibilityService() {

    companion object {
        @Volatile var instance: ComboService? = null
        const val PREFS = "combo"
        const val KEY = "steps"
        const val DEFAULT = """# tap:   x, y, holdMs, delayAfterMs
# swipe: x1, y1, x2, y2, durationMs, delayAfterMs
900,1800,60,80
800,1650,60,120
700,1500,60,150
400,1700,500,0,
"""
    }

    data class Step(val x1: Float, val y1: Float, val x2: Float, val y2: Float,
                    val dur: Long, val delay: Long)

    private val handler = Handler(Looper.getMainLooper())
    private var button: Button? = null
    private var running = false

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onUnbind(intent: Intent?): Boolean {
        hideButton(); instance = null
        return super.onUnbind(intent)
    }

    private fun parse(text: String): List<Step> = text.lines().mapNotNull { raw ->
        val line = raw.substringBefore('#').trim().trimEnd(',')
        if (line.isEmpty()) return@mapNotNull null
        val p = line.split(',').map { it.trim().toFloatOrNull() ?: return@mapNotNull null }
        when (p.size) {
            4 -> Step(p[0], p[1], p[0], p[1], p[2].toLong(), p[3].toLong())
            6 -> Step(p[0], p[1], p[2], p[3], p[4].toLong(), p[5].toLong())
            else -> null
        }
    }

    fun runCombo() {
        if (running) return
        val text = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY, DEFAULT) ?: DEFAULT
        val steps = parse(text)
        if (steps.isEmpty()) return
        running = true
        runStep(steps, 0)
    }

    private fun runStep(steps: List<Step>, i: Int) {
        if (i >= steps.size) { running = false; return }
        val s = steps[i]
        val path = Path().apply {
            moveTo(s.x1, s.y1)
            if (s.x1 != s.x2 || s.y1 != s.y2) lineTo(s.x2, s.y2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, maxOf(1L, s.dur)))
            .build()
        val ok = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                handler.postDelayed({ runStep(steps, i + 1) }, s.delay)
            }
            override fun onCancelled(g: GestureDescription?) { running = false }
        }, null)
        if (!ok) running = false
    }

    fun isButtonShown() = button != null

    fun showButton() {
        if (button != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 40; y = 300 }

        val b = Button(this).apply { text = "COMBO"; alpha = 0.85f }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        b.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY; startX = lp.x; startY = lp.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX; val dy = ev.rawY - downY
                    if (Math.abs(dx) > 15 || Math.abs(dy) > 15) moved = true
                    if (moved) {
                        lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt()
                        wm.updateViewLayout(b, lp)
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) runCombo()
            }
            true
        }
        wm.addView(b, lp)
        button = b
    }

    fun hideButton() {
        val b = button ?: return
        (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(b)
        button = null
    }
}



class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(ComboService.PREFS, MODE_PRIVATE)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        root.addView(TextView(this).apply {
            text = "1) Turn on 'Single Press Combo' in Accessibility settings.\n" +
                   "2) Edit your combo below and Save.\n" +
                   "3) Tap 'Show / hide floating button', then open your game and tap COMBO. " +
                   "Drag the button to move it.\n\n" +
                   "Tip: Settings > Developer options > Pointer location shows the x,y of your touches."
        })

        val edit = EditText(this).apply {
            setText(prefs.getString(ComboService.KEY, ComboService.DEFAULT))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            gravity = Gravity.TOP
            minLines = 8
            typeface = android.graphics.Typeface.MONOSPACE
        }
        root.addView(edit, LinearLayout.LayoutParams(-1, -2))

        fun btn(label: String, action: () -> Unit) = Button(this).apply {
            text = label; setOnClickListener { action() }
            root.addView(this, LinearLayout.LayoutParams(-1, -2))
        }

        btn("Open Accessibility settings") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        btn("Save combo") {
            prefs.edit().putString(ComboService.KEY, edit.text.toString()).apply()
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        }
        btn("Show / hide floating button") {
            val s = ComboService.instance
            if (s == null) Toast.makeText(this, "Enable the accessibility service first", Toast.LENGTH_LONG).show()
            else if (s.isButtonShown()) s.hideButton() else s.showButton()
        }

        setContentView(ScrollView(this).apply { addView(root) })
    }
}
