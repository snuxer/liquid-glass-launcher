package com.example.liquidglasslauncher

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
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
import android.view.DragEvent
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
import android.widget.Toast
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
    private lateinit var homeIconContainer: FrameLayout
    private lateinit var openDrawerButton: View
    private lateinit var searchField: EditText

    private var allApps: List<AppInfo> = emptyList()
    private lateinit var drawerAdapter: AppAdapter

    // Aktuelle Dock-Belegung (veränderbar per Drag & Drop / Long-Press)
    private val dockApps = mutableListOf<AppInfo>()

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
        homeIconContainer = findViewById(R.id.homeIconContainer)
        openDrawerButton = findViewById(R.id.openDrawerButton)
        searchField = findViewById(R.id.searchField)

        applyGlassBlur()
        setupApps()
        setupGestures()
        setupSearch()
        setupHomeDragTarget()
        setupDockDragTarget()
        loadPinnedIcons()

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

    private fun applyGlassBlur() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val blurEffect = RenderEffect.createBlurEffect(60f, 60f, Shader.TileMode.CLAMP)
            liquidBackground.setRenderEffect(blurEffect)
        }
    }

    // ---------- App-Liste & Drawer ----------

    private fun setupApps() {
        allApps = loadInstalledApps()

        drawerAdapter = AppAdapter(
            apps = allApps,
            onClick = { launchApp(it) },
            onLongClick = { app, view -> startDragFromDrawer(app, view) }
        )
        appDrawerRecycler.layoutManager = GridLayoutManager(this, 4)
        appDrawerRecycler.adapter = drawerAdapter

        val savedDock = LauncherPrefs.getDockPackages(this)
        val initialDock = if (savedDock != null) {
            savedDock.mapNotNull { pkg -> allApps.find { it.packageName == pkg } }
        } else {
            allApps.take(5)
        }
        dockApps.clear()
        dockApps.addAll(initialDock)
        rebuildDockViews()
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
                    activityName = it.activityInfo.name,
                    icon = it.loadIcon(pm)
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
    }

    /**
     * Öffnet eine App zuverlässig: baut zuerst ein explizites Intent auf die genaue
     * Launcher-Activity (statt sich nur auf getLaunchIntentForPackage zu verlassen,
     * das bei manchen Apps null liefert). Schlägt das fehl, wird als Fallback doch
     * getLaunchIntentForPackage versucht; klappt auch das nicht, gibt's eine
     * verständliche Meldung statt eines stillen Nichtstuns.
     */
    private fun launchApp(app: AppInfo) {
        val explicitIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setClassName(app.packageName, app.activityName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(explicitIntent)
            closeDrawer()
            return
        } catch (e: Exception) {
            // fällt durch zum Fallback unten
        }

        val fallbackIntent = packageManager.getLaunchIntentForPackage(app.packageName)
        if (fallbackIntent != null) {
            try {
                fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(fallbackIntent)
                closeDrawer()
                return
            } catch (e: ActivityNotFoundException) {
                // fällt durch zur Fehlermeldung unten
            }
        }

        Toast.makeText(this, "${app.label} konnte nicht geöffnet werden", Toast.LENGTH_SHORT).show()
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

    // ---------- Dock ----------

    private fun rebuildDockViews() {
        dock.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for ((index, app) in dockApps.withIndex()) {
            val itemView = inflater.inflate(R.layout.dock_icon_item, dock, false)
            val icon = itemView.findViewById<ImageView>(R.id.dockIcon)
            icon.setImageDrawable(app.icon)
            itemView.setOnClickListener { launchApp(app) }
            itemView.setOnLongClickListener {
                AlertDialog.Builder(this)
                    .setTitle(app.label)
                    .setMessage("Aus dem Dock entfernen?")
                    .setPositiveButton("Entfernen") { _, _ ->
                        if (index < dockApps.size) {
                            dockApps.removeAt(index)
                            persistDock()
                            rebuildDockViews()
                        }
                    }
                    .setNegativeButton("Abbrechen", null)
                    .show()
                true
            }
            dock.addView(itemView)
        }
    }

    private fun persistDock() {
        LauncherPrefs.saveDockPackages(this, dockApps.map { it.packageName })
    }

    private fun setupDockDragTarget() {
        dock.setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> true
                DragEvent.ACTION_DROP -> {
                    val app = event.localState as? AppInfo ?: return@setOnDragListener false
                    val targetIndex = findNearestDockSlot(event.x)
                    if (dockApps.isEmpty()) {
                        dockApps.add(app)
                    } else {
                        dockApps[targetIndex] = app
                    }
                    persistDock()
                    rebuildDockViews()
                    true
                }
                else -> true
            }
        }
    }

    private fun findNearestDockSlot(x: Float): Int {
        var closestIndex = 0
        var closestDistance = Float.MAX_VALUE
        for (i in 0 until dock.childCount) {
            val child = dock.getChildAt(i)
            val center = child.left + child.width / 2f
            val distance = abs(center - x)
            if (distance < closestDistance) {
                closestDistance = distance
                closestIndex = i
            }
        }
        return closestIndex
    }

    // ---------- Homescreen-Icons (Anheften per Drag & Drop) ----------

    private fun startDragFromDrawer(app: AppInfo, view: View) {
        // Drawer sofort unsichtbar machen (ohne Animation), damit der Homescreen
        // während des Ziehens sichtbar ist und Drops dort ankommen können.
        appDrawerPanel.visibility = View.INVISIBLE
        drawerOpen = false
        searchField.text.clear()

        val clipData = ClipData.newPlainText("app_package", app.packageName)
        val shadow = View.DragShadowBuilder(view)
        view.startDragAndDrop(clipData, shadow, app, 0)
    }

    private fun setupHomeDragTarget() {
        homeIconContainer.setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> true
                DragEvent.ACTION_DROP -> {
                    val app = event.localState as? AppInfo ?: return@setOnDragListener false
                    addPinnedIcon(app, event.x, event.y, persist = true)
                    true
                }
                else -> true
            }
        }
    }

    private fun addPinnedIcon(app: AppInfo, x: Float, y: Float, persist: Boolean) {
        val view = LayoutInflater.from(this).inflate(R.layout.item_app, homeIconContainer, false)
        val icon = view.findViewById<ImageView>(R.id.appIcon)
        val label = view.findViewById<TextView>(R.id.appLabel)
        icon.setImageDrawable(app.icon)
        label.text = app.label

        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        params.leftMargin = x.toInt().coerceAtLeast(0)
        params.topMargin = y.toInt().coerceAtLeast(0)
        view.layoutParams = params

        view.setOnClickListener { launchApp(app) }
        view.setOnLongClickListener {
            AlertDialog.Builder(this)
                .setTitle(app.label)
                .setMessage("Vom Homescreen entfernen?")
                .setPositiveButton("Entfernen") { _, _ ->
                    homeIconContainer.removeView(view)
                    persistPinnedIcons()
                }
                .setNegativeButton("Abbrechen", null)
                .show()
            true
        }

        homeIconContainer.addView(view)
        if (persist) persistPinnedIcons()
    }

    private fun persistPinnedIcons() {
        val icons = mutableListOf<LauncherPrefs.PinnedIcon>()
        for (i in 0 until homeIconContainer.childCount) {
            val child = homeIconContainer.getChildAt(i)
            val label = child.findViewById<TextView>(R.id.appLabel)?.text?.toString()
            val app = allApps.find { it.label == label } ?: continue
            val lp = child.layoutParams as FrameLayout.LayoutParams
            icons.add(LauncherPrefs.PinnedIcon(app.packageName, lp.leftMargin.toFloat(), lp.topMargin.toFloat()))
        }
        LauncherPrefs.savePinnedIcons(this, icons)
    }

    private fun loadPinnedIcons() {
        val saved = LauncherPrefs.getPinnedIcons(this)
        for (pinned in saved) {
            val app = allApps.find { it.packageName == pinned.packageName } ?: continue
            addPinnedIcon(app, pinned.x, pinned.y, persist = false)
        }
    }

    // ---------- Swipe-Geste für den App-Drawer ----------

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
