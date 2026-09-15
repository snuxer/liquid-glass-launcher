package com.example.liquidglasslauncher

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
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var liquidBackground: LiquidBackgroundView
    private lateinit var clockText: TextView
    private lateinit var dateText: TextView
    private lateinit var appDrawerPanel: FrameLayout
    private lateinit var appDrawerRecycler: RecyclerView
    private lateinit var dock: LinearLayout
    private lateinit var openDrawerButton: View
    private lateinit var searchField: EditText

    private var allApps: List<AppInfo> = emptyList()
    private lateinit var drawerAdapter: AppAdapter

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

        liquidBackground = findViewById(R.id.liquidBackground)
        clockText = findViewById(R.id.clockText)
        dateText = findViewById(R.id.dateText)
        appDrawerPanel = findViewById(R.id.appDrawerPanel)
        appDrawerRecycler = findViewById(R.id.appDrawerRecycler)
        dock = findViewById(R.id.dock)
        openDrawerButton = findViewById(R.id.openDrawerButton)
        searchField = findViewById(R.id.searchField)

        applyGlassBlur()
        setupApps()
        setupGestures()
        setupSearch()

        openDrawerButton.setOnClickListener { openDrawer() }
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

    /**
     * Zusätzliche Weichzeichnung über dem animierten Hintergrund, ab Android 12 (API 31).
     * Sorgt dafür, dass die Farbblasen noch weicher ineinander übergehen ("Liquid"-Effekt).
     */
    private fun applyGlassBlur() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val blurEffect = RenderEffect.createBlurEffect(60f, 60f, Shader.TileMode.CLAMP)
            liquidBackground.setRenderEffect(blurEffect)
        }
    }

    private fun setupApps() {
        allApps = loadInstalledApps()

        drawerAdapter = AppAdapter(allApps) { launchApp(it) }
        appDrawerRecycler.layoutManager = GridLayoutManager(this, 4)
        appDrawerRecycler.adapter = drawerAdapter

        buildDock(allApps.take(5))
    }

    /** Baut das Dock als feste Icon-Reihe auf (kein RecyclerView -> nichts wird abgeschnitten). */
    private fun buildDock(favorites: List<AppInfo>) {
        dock.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (app in favorites) {
            val itemView = inflater.inflate(R.layout.dock_icon_item, dock, false)
            val icon = itemView.findViewById<ImageView>(R.id.dockIcon)
            icon.setImageDrawable(app.icon)
            itemView.setOnClickListener { launchApp(app) }
            dock.addView(itemView)
        }
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

    /**
     * Nach-oben-Wischen öffnet den App-Drawer. WICHTIG: onDown MUSS true zurückgeben,
     * sonst verwirft Android die Geste sofort nach dem ersten Touch-Down und onFling
     * wird nie aufgerufen (das war der Bug in der ersten Version).
     */
    private fun setupGestures() {
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val deltaY = e2.y - e1.y
                if (abs(deltaY) > 80 && abs(velocityY) > abs(velocityX)) {
                    if (deltaY < 0) openDrawer() else closeDrawer()
                    return true
                }
                return false
            }
        })

        findViewById<View>(R.id.rootLayout).setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
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
