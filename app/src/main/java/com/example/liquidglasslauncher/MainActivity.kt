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
import android.view.HapticFeedbackConstants
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
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var liquidBackground: LiquidBackgroundView
    private lateinit var clockText: TextView
    private lateinit var dateText: TextView
    private lateinit var clockCard: View
    private lateinit var removeZone: View
    private lateinit var appDrawerPanel: FrameLayout
    private lateinit var appDrawerRecycler: RecyclerView
    private lateinit var dock: LinearLayout
    private lateinit var homeIconContainer: FrameLayout
    private lateinit var openDrawerButton: View
    private lateinit var searchField: EditText
    private lateinit var clearSearchButton: View
    private lateinit var recentLabel: View
    private lateinit var recentAppsRow: LinearLayout
    private lateinit var drawerHandle: View

    private var allApps: List<AppInfo> = emptyList()
    private lateinit var drawerAdapter: AppAdapter

    private val dockApps = mutableListOf<AppInfo>()
    private var drawerOpen = false
    private var gridCellPx = 0f

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
        clockCard = findViewById(R.id.clockCard)
        removeZone = findViewById(R.id.removeZone)
        appDrawerPanel = findViewById(R.id.appDrawerPanel)
        appDrawerRecycler = findViewById(R.id.appDrawerRecycler)
        dock = findViewById(R.id.dock)
        homeIconContainer = findViewById(R.id.homeIconContainer)
        openDrawerButton = findViewById(R.id.openDrawerButton)
        searchField = findViewById(R.id.searchField)
        clearSearchButton = findViewById(R.id.clearSearchButton)
        recentLabel = findViewById(R.id.recentLabel)
        recentAppsRow = findViewById(R.id.recentAppsRow)
        drawerHandle = findViewById(R.id.drawerHandle)

        gridCellPx = 92f * resources.displayMetrics.density

        applyGlassBlur()
        setupApps()
        setupGestures()
        setupSearch()
        setupGlobalDragUi()
        setupHomeDragTarget()
        setupDockDragTarget()
        setupRemoveZone()
        setupDrawerSwipeDownToClose()
        loadPinnedIcons()
        refreshRecentRow()

        openDrawerButton.setOnClickListener { openDrawer() }
        drawerHandle.setOnClickListener { closeDrawer() }
    }

    /**
     * Das App-Raster fängt vertikale Wischgesten normalerweise selbst als Scroll ab,
     * daher kommt ein Swipe-nach-unten nie beim allgemeinen Gesture-Detector an.
     * Dieser Listener erkennt gezielt: Liste steht ganz oben UND Finger zieht weiter
     * nach unten -> das ist kein Scroll mehr, sondern der Wunsch, den Drawer zu schließen.
     */
    private fun setupDrawerSwipeDownToClose() {
        appDrawerRecycler.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            private var downY = 0f
            private var closing = false

            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downY = e.y
                        closing = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val pulledDown = e.y - downY
                        val alreadyAtTop = !rv.canScrollVertically(-1)
                        if (alreadyAtTop && pulledDown > 60) {
                            closing = true
                            return true
                        }
                    }
                }
                return false
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                if (closing && (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL)) {
                    closeDrawer()
                    closing = false
                }
            }
        })

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
            val blurEffect = RenderEffect.createBlurEffect(35f, 35f, Shader.TileMode.CLAMP)
            liquidBackground.setRenderEffect(blurEffect)
        }
    }

    // ---------- App-Liste & Drawer ----------

    private fun setupApps() {
        allApps = loadInstalledApps()

        drawerAdapter = AppAdapter(
            apps = allApps,
            onClick = { launchApp(it) },
            onLongClick = { app, view -> startDrag(DragPayload.FromDrawer(app), view) }
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

    private fun launchApp(app: AppInfo) {
        val explicitIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setClassName(app.packageName, app.activityName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(explicitIntent)
            afterLaunch(app)
            return
        } catch (e: Exception) {
            // fällt durch zum Fallback unten
        }

        val fallbackIntent = packageManager.getLaunchIntentForPackage(app.packageName)
        if (fallbackIntent != null) {
            try {
                fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(fallbackIntent)
                afterLaunch(app)
                return
            } catch (e: ActivityNotFoundException) {
                // fällt durch zur Fehlermeldung unten
            }
        }

        Toast.makeText(this, "${app.label} konnte nicht geöffnet werden", Toast.LENGTH_SHORT).show()
    }

    private fun afterLaunch(app: AppInfo) {
        closeDrawer()
        LauncherPrefs.recordLaunch(this, app.packageName)
        refreshRecentRow()
    }

    private fun setupSearch() {
        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.lowercase(Locale.getDefault()).orEmpty()
                val filtered = if (query.isBlank()) allApps
                else allApps.filter { it.label.lowercase(Locale.getDefault()).contains(query) }
                drawerAdapter.updateData(filtered)
                clearSearchButton.visibility = if (query.isBlank()) View.GONE else View.VISIBLE
                val showRecents = query.isBlank()
                recentLabel.visibility = if (showRecents && recentAppsRow.childCount > 0) View.VISIBLE else View.GONE
                recentAppsRow.visibility = if (showRecents && recentAppsRow.childCount > 0) View.VISIBLE else View.GONE
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        clearSearchButton.setOnClickListener { searchField.text.clear() }
    }

    /** Zeigt die zuletzt geöffneten Apps oben im Drawer, wie bei modernen Launchern üblich. */
    private fun refreshRecentRow() {
        recentAppsRow.removeAllViews()
        val recentPackages = LauncherPrefs.getRecentApps(this).take(6)
        val recentInfos = recentPackages.mapNotNull { pkg -> allApps.find { it.packageName == pkg } }
        val inflater = LayoutInflater.from(this)
        for (app in recentInfos) {
            val itemView = inflater.inflate(R.layout.dock_icon_item, recentAppsRow, false)
            val icon = itemView.findViewById<ImageView>(R.id.dockIcon)
            icon.setImageDrawable(app.icon)
            itemView.applyPressBounce()
            itemView.setOnClickListener { launchApp(app) }
            recentAppsRow.addView(itemView)
        }
        val visible = recentInfos.isNotEmpty() && searchField.text.isNullOrBlank()
        recentLabel.visibility = if (visible) View.VISIBLE else View.GONE
        recentAppsRow.visibility = if (visible) View.VISIBLE else View.GONE
    }

    // ---------- Dock ----------

    private fun rebuildDockViews() {
        dock.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for ((index, app) in dockApps.withIndex()) {
            val itemView = inflater.inflate(R.layout.dock_icon_item, dock, false)
            val icon = itemView.findViewById<ImageView>(R.id.dockIcon)
            icon.setImageDrawable(app.icon)
            itemView.applyPressBounce()
            itemView.setOnClickListener { launchApp(app) }
            itemView.setOnLongClickListener {
                startDrag(DragPayload.FromDock(app, index), itemView)
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
                    val payload = event.localState as? DragPayload ?: return@setOnDragListener false
                    val targetIndex = findNearestDockSlot(event.x)

                    when (payload) {
                        is DragPayload.FromDrawer -> {
                            if (dockApps.isEmpty()) dockApps.add(payload.app)
                            else dockApps[targetIndex] = payload.app
                        }
                        is DragPayload.FromDock -> {
                            // Zwei Dock-Plätze tauschen
                            if (payload.originIndex in dockApps.indices && targetIndex in dockApps.indices) {
                                val tmp = dockApps[targetIndex]
                                dockApps[targetIndex] = dockApps[payload.originIndex]
                                dockApps[payload.originIndex] = tmp
                            }
                        }
                        is DragPayload.FromHome -> {
                            // Von Homescreen ins Dock verschieben
                            homeIconContainer.removeView(payload.view)
                            persistPinnedIcons()
                            if (dockApps.isEmpty()) dockApps.add(payload.app)
                            else dockApps[targetIndex] = payload.app
                        }
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

    // ---------- Homescreen-Icons ----------

    private fun startDrag(payload: DragPayload, view: View): Boolean {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        appDrawerPanel.visibility = View.INVISIBLE
        drawerOpen = false
        searchField.text.clear()

        val app = when (payload) {
            is DragPayload.FromDrawer -> payload.app
            is DragPayload.FromDock -> payload.app
            is DragPayload.FromHome -> payload.app
        }
        val clipData = ClipData.newPlainText("app_package", app.packageName)
        view.startDragAndDrop(clipData, View.DragShadowBuilder(view), payload, 0)
        return true
    }

    /** Zeigt/versteckt die "Entfernen"-Zone anstelle der Uhr, solange gezogen wird. */
    private fun setupGlobalDragUi() {
        findViewById<View>(R.id.rootLayout).setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> {
                    clockCard.visibility = View.INVISIBLE
                    removeZone.visibility = View.VISIBLE
                    true
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    clockCard.visibility = View.VISIBLE
                    removeZone.visibility = View.GONE
                    true
                }
                else -> true
            }
        }
    }

    private fun setupRemoveZone() {
        removeZone.setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> true
                DragEvent.ACTION_DROP -> {
                    when (val payload = event.localState as? DragPayload) {
                        is DragPayload.FromHome -> {
                            homeIconContainer.removeView(payload.view)
                            persistPinnedIcons()
                        }
                        is DragPayload.FromDock -> {
                            if (payload.originIndex in dockApps.indices) {
                                dockApps.removeAt(payload.originIndex)
                                persistDock()
                                rebuildDockViews()
                            }
                        }
                        else -> { /* Drawer-Apps können nicht "entfernt" werden, nur nicht platziert */ }
                    }
                    true
                }
                else -> true
            }
        }
    }

    private fun setupHomeDragTarget() {
        homeIconContainer.setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> true
                DragEvent.ACTION_DROP -> {
                    val payload = event.localState as? DragPayload ?: return@setOnDragListener false
                    val (snapX, snapY) = snapToGrid(event.x, event.y)

                    when (payload) {
                        is DragPayload.FromDrawer -> addPinnedIcon(payload.app, snapX, snapY, persist = true)
                        is DragPayload.FromDock -> {
                            if (payload.originIndex in dockApps.indices) {
                                dockApps.removeAt(payload.originIndex)
                                persistDock()
                                rebuildDockViews()
                            }
                            addPinnedIcon(payload.app, snapX, snapY, persist = true)
                        }
                        is DragPayload.FromHome -> {
                            val lp = payload.view.layoutParams as FrameLayout.LayoutParams
                            lp.leftMargin = snapX
                            lp.topMargin = snapY
                            payload.view.layoutParams = lp
                            persistPinnedIcons()
                        }
                    }
                    true
                }
                else -> true
            }
        }
    }

    /** Rastet eine Ablageposition auf ein unsichtbares Raster ein, für ein aufgeräumtes Bild. */
    private fun snapToGrid(x: Float, y: Float): Pair<Int, Int> {
        val cell = gridCellPx
        val snappedX = ((x / cell).roundToInt() * cell).toInt().coerceAtLeast(0)
        val snappedY = ((y / cell).roundToInt() * cell).toInt().coerceAtLeast(0)
        return snappedX to snappedY
    }

    private fun addPinnedIcon(app: AppInfo, x: Int, y: Int, persist: Boolean) {
        val view = LayoutInflater.from(this).inflate(R.layout.item_app, homeIconContainer, false)
        val icon = view.findViewById<ImageView>(R.id.appIcon)
        val label = view.findViewById<TextView>(R.id.appLabel)
        icon.setImageDrawable(app.icon)
        label.text = app.label
        view.tag = app.packageName
        view.applyPressBounce()

        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        params.leftMargin = x
        params.topMargin = y
        view.layoutParams = params

        view.setOnClickListener { launchApp(app) }
        view.setOnLongClickListener { v -> startDrag(DragPayload.FromHome(app, v), v) }

        homeIconContainer.addView(view)
        if (persist) persistPinnedIcons()
    }

    /** Robuste Speicherung über den View-Tag (Package-Name) statt über den Anzeigenamen. */
    private fun persistPinnedIcons() {
        val icons = mutableListOf<LauncherPrefs.PinnedIcon>()
        for (i in 0 until homeIconContainer.childCount) {
            val child = homeIconContainer.getChildAt(i)
            val packageName = child.tag as? String ?: continue
            val lp = child.layoutParams as FrameLayout.LayoutParams
            icons.add(LauncherPrefs.PinnedIcon(packageName, lp.leftMargin.toFloat(), lp.topMargin.toFloat()))
        }
        LauncherPrefs.savePinnedIcons(this, icons)
    }

    private fun loadPinnedIcons() {
        val saved = LauncherPrefs.getPinnedIcons(this)
        for (pinned in saved) {
            val app = allApps.find { it.packageName == pinned.packageName } ?: continue
            addPinnedIcon(app, pinned.x.toInt(), pinned.y.toInt(), persist = false)
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
        refreshRecentRow()
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
