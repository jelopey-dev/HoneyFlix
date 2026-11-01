package com.honeystream.diag

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

/**
 * HoneyFlix diagnostic: a small corner readout of the last remote presses the app received,
 * who handled them, and what had focus. Used to track down remotes that do nothing on some
 * devices (onn 4K Pro). Only active when BuildConfig.HONEY_KEY_DIAGNOSTICS is true.
 */
object KeyDiagnostics {
    private var view: TextView? = null
    private val lines = ArrayDeque<String>()

    fun install(activity: Activity) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val tv = TextView(activity).apply {
            setTextColor(Color.parseColor("#FFC94A"))
            setBackgroundColor(Color.parseColor("#CC120D06"))
            textSize = 11f
            setPadding(18, 12, 18, 12)
            isFocusable = false
            isFocusableInTouchMode = false
            text = "HoneyFlix key test: press any remote button"
        }
        root.addView(tv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, 24, 24) })
        view = tv
    }

    fun record(activity: Activity, event: KeyEvent, handledBy: String, handled: Boolean) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        val focus = activity.currentFocus
        val focusName = focus?.let { it.javaClass.simpleName + (if (it.hasFocus()) "" else "(no focus)") } ?: "nothing"
        val line = "${KeyEvent.keyCodeToString(event.keyCode).removePrefix("KEYCODE_")} " +
            "(${event.keyCode}, src=0x${Integer.toHexString(event.source)}) → " +
            (if (handled) "handled by $handledBy" else "NOT handled") + " · focus: $focusName"
        lines.addFirst(line)
        while (lines.size > 6) lines.removeLast()
        view?.text = lines.joinToString("\n")
    }
}
