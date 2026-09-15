package com.example.liquidglasslauncher

import android.content.Context

/**
 * Speichert die Dock-Belegung und die auf dem Homescreen angehefteten Apps
 * dauerhaft in SharedPreferences, damit beides einen Neustart übersteht.
 */
object LauncherPrefs {
    private const val PREFS_NAME = "liquid_launcher_prefs"
    private const val KEY_DOCK = "dock_packages"
    private const val KEY_PINNED = "pinned_icons"

    fun getDockPackages(context: Context): List<String>? {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DOCK, null) ?: return null
        val list = raw.split("|").filter { it.isNotBlank() }
        return list.ifEmpty { null }
    }

    fun saveDockPackages(context: Context, packages: List<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DOCK, packages.joinToString("|"))
            .apply()
    }

    data class PinnedIcon(val packageName: String, val x: Float, val y: Float)

    fun getPinnedIcons(context: Context): List<PinnedIcon> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PINNED, null) ?: return emptyList()
        return raw.split(";").filter { it.isNotBlank() }.mapNotNull { entry ->
            val parts = entry.split(",")
            if (parts.size == 3) {
                val x = parts[1].toFloatOrNull()
                val y = parts[2].toFloatOrNull()
                if (x != null && y != null) PinnedIcon(parts[0], x, y) else null
            } else null
        }
    }

    fun savePinnedIcons(context: Context, icons: List<PinnedIcon>) {
        val raw = icons.joinToString(";") { "${it.packageName},${it.x},${it.y}" }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PINNED, raw)
            .apply()
    }
}
