package com.example.liquidglasslauncher

import android.view.MotionEvent
import android.view.View

/** Sanftes Einfedern beim Antippen – macht Icons taktil, ohne Klick/Long-Click zu stören. */
fun View.applyPressBounce() {
    setOnTouchListener { v, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN ->
                v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(100).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
        }
        false
    }
}
