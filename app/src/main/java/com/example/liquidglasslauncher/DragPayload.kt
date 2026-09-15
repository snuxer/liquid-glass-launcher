package com.example.liquidglasslauncher

import android.view.View

/** Woher kommt die gerade gezogene App? Bestimmt, was beim Drop passieren soll. */
sealed class DragPayload {
    data class FromDrawer(val app: AppInfo) : DragPayload()
    data class FromDock(val app: AppInfo, val originIndex: Int) : DragPayload()
    data class FromHome(val app: AppInfo, val view: View) : DragPayload()
}
