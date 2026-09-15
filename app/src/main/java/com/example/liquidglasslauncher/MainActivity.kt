package com.example.liquidglasslauncher

import android.app.WallpaperManager
import android.app.WallpaperInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var wallpaperImage: ImageView
    private lateinit var clockText: TextView
    private lateinit var dateText: TextView
    private lateinit var appDrawerPanel: FrameLayout
    private lateinit var appDrawerRecycler: RecyclerView
    private lateinit var dockRecycler: RecyclerView
    private lateinit var searchField: EditText

    private var allApps: List<AppInfo> = emptyList()
    private lateinit var drawerAdapter: AppAdapter
    private lateinit var dockAdapter: AppAdapter

    private var drawerOpen = false

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            clockHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        wallpaperImage = findViewById(R.id.wallpaperImage)
        clockText = findViewById(R.id.clockText)
        dateText = findViewById(R.id.dateText)
        appDrawerPanel = findViewById(R.id.appDrawerPanel)
        appDrawerRecycler = findViewById(R.id.appDrawerRecycler)
        dockRecycler = findViewById(R.id.dockRecycler)
        searchField = findViewById(R.id.searchField)

        loadWallpaper()
        applyGlassBlur()
        setupApps()
        setupGestures()
        setupSearch()
    }

    override fun onResume() {
        super.onResume()
        clockHandler.post(clockRunnable)
    }

    override fun onPause() {
        super.onPause()
        clockHandler.removeCallbacks(clockRunnable)
    }

    private fun updateClock() {
        val now = Date()
        clockText.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        dateText.text = SimpleDateFormat("EEEE, d. MMMM", Locale.getDefault()).format(now)
    }

    /** Liest das aktuelle System-Wallpaper aus, damit der Home-Screen "echt" wirkt. */
    private fun loadWallpaper() {
        try {
            val wm = WallpaperManager.getInstance(this)
            val drawable = wm.drawable
            if (drawable != null) {
                wallpaperImage.setImageDrawable(drawable)
            }
        } catch (e: SecurityException) {
            // Kein Zugriff (z. B. Live-Wallpaper ohne Permission) -> Fallback-Gradient bleibt sichtbar
        }
    }

    /**
     * Wendet einen echten Blur-Effekt (RenderEffect) auf das Wallpaper an, ab Android 12 (API 31).
     * Das ist der eigentliche "Liquid Glass"-Effekt: der Hintergrund schimmert unscharf durch
     * die transluzenten Karten. Auf älteren Geräten bleibt es bei der Transparenz ohne Weichzeichnung.
     */
    private fun applyGlassBlur() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val blurEffect = RenderEffect.createBlurEffect(40f, 40f, Shader.TileMode.CLAMP)
            wallpaperImage.setRenderEffect(blurEffect)
        }
    }

    private fun setupApps() {
        allApps = loadInstalledApps()

        drawerAdapter = AppAdapter(allApps) { launchApp(it) }
        appDrawerRecycler.layoutManager = GridLayoutManager(this, 4)
        appDrawerRecycler.adapter = drawerAdapter

        val favorites = allApps.take(5)
        dockAdapter = AppAdapter(favorites) { launchApp(it) }
        dockRecycler.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        dockRecycler.adapter = dockAdapter
    }

    private fun loadInstalledApps(): List<AppInfo> {
        val pm = packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null)
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)

        val resolveInfos = pm.queryIntentActivities(mainIntent, PackageManager.MATCH_ALL)
        return resolveInfos
            .map {
                AppInfo(
                    label = it.loadLabel(pm).toString(),
                    packageName = it.activityInfo.packageName,
                    icon = it.loadIcon(pm)
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
    }

    private fun launchApp(app: AppInfo) {
        val launchIntent = packageManager.getLaunchIntentForPackage(app.packageName)
        if (launchIntent != null) {
            startActivity(launchIntent)
            closeDrawer()
        }
    }

    private fun setupSearch() {
        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.lowercase(Locale.getDefault()).orEmpty()
                val filtered = if (query.isBlank()) allApps
                else allApps.filter { it.label.lowercase(Locale.getDefault()).contains(query) }
                drawerAdapter.updateData(filtered)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    /** Nach-oben-Wischen auf dem Home-Screen öffnet den App-Drawer, wie bei den meisten Launchern. */
    private fun setupGestures() {
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val deltaY = e2.y - e1.y
                if (abs(deltaY) > 100 && abs(velocityY) > abs(velocityX)) {
                    if (deltaY < 0) openDrawer() else closeDrawer()
                    return true
                }
                return false
            }
        })

        findViewById<View>(R.id.rootLayout).setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
        }
    }

    private fun openDrawer() {
        if (drawerOpen) return
        drawerOpen = true
        appDrawerPanel.visibility = View.VISIBLE
        appDrawerPanel.translationY = appDrawerPanel.height.toFloat()
        appDrawerPanel.alpha = 0f
        appDrawerPanel.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(260)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun closeDrawer() {
        if (!drawerOpen) return
        drawerOpen = false
        appDrawerPanel.animate()
            .translationY(appDrawerPanel.height.toFloat())
            .alpha(0f)
            .setDuration(220)
            .withEndAction { appDrawerPanel.visibility = View.INVISIBLE }
            .start()
        searchField.text.clear()
    }

    override fun onBackPressed() {
        if (drawerOpen) {
            closeDrawer()
        } else {
            super.onBackPressed()
        }
    }
}
