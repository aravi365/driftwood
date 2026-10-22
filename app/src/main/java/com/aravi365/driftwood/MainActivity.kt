package com.aravi365.driftwood

import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityOptions
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.media.tv.TvInputManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.provider.Settings
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private class Art(val backdrop: Bitmap, val tint: Int)

    private lateinit var prefs: Prefs
    private lateinit var backdrops: Array<ImageView>
    private lateinit var ambient: View
    private lateinit var shelfLayer: View
    private lateinit var chrome: View
    private lateinit var scroll: FocusScrollView
    private lateinit var shelf: FrameLayout
    private lateinit var title: TextView
    private lateinit var grid: LinearLayout
    private lateinit var weather: TextView
    private lateinit var bar: View
    private lateinit var hint: View
    private val hideHint = Runnable { hint.animate().alpha(0f).setDuration(600).start() }
    private lateinit var barWeather: TextView
    private lateinit var quick: FrameLayout
    private lateinit var quickPanel: QuickPanel

    private val dp by lazy { resources.displayMetrics.density }
    private val art = HashMap<String, Art>()
    private var visible: List<App> = emptyList()
    private var tiles: List<Tile> = emptyList()
    private var front = 0
    private var shownKey: String? = null
    private var focusedPkg: String? = null
    private var ambientColor = Color.BLACK
    private var ambientAnim: ValueAnimator? = null
    private val main = Handler(Looper.getMainLooper())
    /** The photo on screen now, its file kept until the next one replaces it. */
    private var current: Photo? = null
    private var shownAt = 0L
    private var shownCount = 0
    private lateinit var credit: TextView
    private val kenBurns = arrayOfNulls<ObjectAnimator>(2)
    private val rotate = object : Runnable {
        override fun run() {
            applyEvening()
            nextPhoto()
            Weather.refresh(prefs) { showWeather(it) }  // cached; only hits the network every 30 min
            main.postDelayed(this, PHOTO_MS)
        }
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            i.data?.schemeSpecificPart?.let { art.remove(it) }
            shownKey = null
            reloadSoon()
        }
    }

    private val reloadTask = Runnable { reloadIfChanged() }

    /** Installs and input changes arrive in bursts (and once on start-up); rebuild once, after they settle. */
    private fun reloadSoon() {
        main.removeCallbacks(reloadTask)
        main.postDelayed(reloadTask, 400)
    }

    /** Skip the rebuild (and the focus jump it causes) when nothing visible changed. */
    private fun reloadIfChanged() {
        val before = visible.map { it.pkg to it.label }
        reload(rebuild = false)
        if (visible.map { it.pkg to it.label } != before) buildGrid()
    }

    /** Plugging a device in or out of an HDMI port adds or removes its tile. */
    private val inputWatcher = object : TvInputManager.TvInputCallback() {
        override fun onInputStateChanged(inputId: String, state: Int) = reloadSoon()
        override fun onInputAdded(inputId: String) = reloadSoon()
        override fun onInputRemoved(inputId: String) = reloadSoon()
    }

    /** A small icon by the weather when the connection drops; weather and photos catch up when it's back. */
    private val network = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: android.net.Network) = main.post { setOnline(true) }.let { }
        override fun onLost(n: android.net.Network) = main.post { setOnline(false) }.let { }
    }

    private fun setOnline(online: Boolean) {
        val v = if (online) View.GONE else View.VISIBLE
        findViewById<View>(R.id.offline).visibility = v
        findViewById<View>(R.id.bar_offline).visibility = v
        if (online && resumed) {
            Weather.refresh(prefs) { showWeather(it) }
            topUpPhotos()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        backdrops = arrayOf(findViewById(R.id.backdrop_a), findViewById(R.id.backdrop_b))
        ambient = findViewById(R.id.ambient)
        shelfLayer = findViewById(R.id.shelf_layer)
        chrome = findViewById(R.id.chrome)
        scroll = findViewById(R.id.scroll)
        shelf = findViewById(R.id.shelf)
        title = findViewById(R.id.title)
        grid = findViewById(R.id.grid)
        weather = findViewById(R.id.weather)
        bar = findViewById(R.id.bar)
        hint = findViewById(R.id.hint)
        barWeather = findViewById(R.id.bar_weather)
        quick = findViewById(R.id.quick)
        quickPanel = QuickPanel(this)
        quick.addView(quickPanel, FrameLayout.LayoutParams(-1, -2))
        quickPanel.setItems(quickItems())

        val m = resources.displayMetrics
        shelf.layoutParams.height = (m.heightPixels * 0.56f).toInt()

        scroll.setOnScrollChangeListener { _, _, y, _, _ ->
            val f = 1f - (y / (shelf.layoutParams.height * 0.7f)).coerceIn(0f, 1f)
            shelfLayer.alpha = 0.4f + 0.6f * f
            chrome.alpha = f
            bar.alpha = 1f - f
            bar.translationY = -12 * dp * f
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(packageReceiver, filter, RECEIVER_EXPORTED)
        else registerReceiver(packageReceiver, filter)

        (getSystemService(TV_INPUT_SERVICE) as? TvInputManager)?.registerCallback(inputWatcher, main)
        getSystemService(android.net.ConnectivityManager::class.java).let { cm ->
            if (cm.activeNetwork == null) setOnline(false)
            cm.registerDefaultNetworkCallback(network)
        }

        reload()
        applyExtras(intent)
        showWeather(prefs.weather)
        credit = findViewById(R.id.credit)
    }

    /**
     * Some TVs bring the home screen back flagged as see-through (after an update, or when it's
     * restored behind another screen). The app you just left then stays drawn on top and Back and
     * Home seem to do nothing. Insist on opaque every time we come to the front.
     */
    private fun stayOpaque() {
        if (Build.VERSION.SDK_INT >= 30) setTranslucent(false)
    }

    override fun onStart() {
        super.onStart()
        stayOpaque()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        stayOpaque()
        Updater.check(this) { it?.let(::offerUpdate) }
        Weather.refresh(prefs) { showWeather(it) }
        kenBurns.forEach { it?.resume() }
        // Pick up where the current photo left off rather than skipping to a new one.
        main.removeCallbacks(rotate)
        val left = if (current == null) 0L else (PHOTO_MS - (System.currentTimeMillis() - shownAt)).coerceAtLeast(0)
        main.postDelayed(rotate, left)
        topUpPhotos()
    }

    /** Fetch more unseen photos in the background when the queue runs low. */
    private fun topUpPhotos(thenShow: Boolean = false) {
        if (!thenShow && !Backgrounds.needsMore(this)) return
        Thread {
            Backgrounds.refresh(this)
            if (thenShow || current == null) runOnUiThread { main.removeCallbacks(rotate); main.post(rotate) }
        }.start()
    }

    override fun onPause() {
        resumed = false
        // Nothing animates behind a playing video.
        main.removeCallbacks(rotate)
        kenBurns.forEach { it?.pause() }
        super.onPause()
    }

    /** `adb shell am start -n com.aravi365.driftwood/.MainActivity --es city Kochi` (empty = auto). */
    private fun applyExtras(intent: Intent?) {
        intent?.getStringExtra("city")?.let { setCity(it) }
        intent?.getStringExtra("wallpapers")?.let { setWallpapers(it) }
    }

    /** Switch wallpaper source and fetch the new set right away. */
    private fun setWallpapers(source: String) {
        Backgrounds.setSource(this, source)
        topUpPhotos(thenShow = true)
    }

    private fun showWeather(line: String?) {
        weather.text = line.orEmpty()
        barWeather.text = line?.substringBefore(" · ").orEmpty()
        barWeather.visibility = if (line.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun setCity(city: String) {
        prefs.city = city.trim().ifEmpty { null }
        Weather.refresh(prefs, force = true) { showWeather(it) }
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        (getSystemService(TV_INPUT_SERVICE) as? TvInputManager)?.unregisterCallback(inputWatcher)
        getSystemService(android.net.ConnectivityManager::class.java).unregisterNetworkCallback(network)
        super.onDestroy()
    }

    private var resumed = false

    /**
     * Home pressed from inside an app: come back to that app's tile.
     * Home pressed while already home: glide back to the first app.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyExtras(intent)
        hideQuick()
        if (resumed) goHome() else focusLast()
    }

    private fun focusLast() {
        val t = tiles.firstOrNull { focusedPkg != null && (it.tag as? App)?.pkg == focusedPkg } ?: return goHome()
        t.post { t.requestFocus(); onTileFocused(t) }
    }

    // ---- quick settings ----

    private fun quickItems() = listOf(
        QuickPanel.Item("Wi-Fi", R.drawable.ic_wifi) { open(Intent(Settings.ACTION_WIFI_SETTINGS)) },
        QuickPanel.Item("Bluetooth", R.drawable.ic_bluetooth) {
            openFirst(
                Intent().setClassName(TV_SETTINGS, "$TV_SETTINGS.accessories.AddAccessoryActivity"),
                Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
            )
        },
        QuickPanel.Item("Picture", R.drawable.ic_picture) {
            openFirst(
                Intent().setClassName(TV_SETTINGS, "$TV_SETTINGS.picture.ui.PictureModeActivity"),
                Intent(Settings.ACTION_DISPLAY_SETTINGS),
            )
        },
        QuickPanel.Item("Sound", R.drawable.ic_sound) {
            openFirst(
                Intent().setClassName(TV_SETTINGS, "$TV_SETTINGS.sound.ui.SoundModeActivity"),
                Intent(Settings.ACTION_SOUND_SETTINGS),
            )
        },
        QuickPanel.Item("Inputs", R.drawable.ic_hdmi) { showInputs() },
        QuickPanel.Item("Screensaver", R.drawable.ic_moon) {
            openFirst(Intent().setClassName("com.android.systemui", "com.android.systemui.Somnambulator"))
        },
        QuickPanel.Item("Settings", R.drawable.ic_settings) { open(Intent(Settings.ACTION_SETTINGS)) },
        QuickPanel.Item("Turn off", R.drawable.ic_power) { askTurnOff() },
    )

    private fun askTurnOff() {
        if (!PowerService.enabled(this)) {
            AlertDialog.Builder(this, R.style.DriftwoodDialog)
                .setTitle("One-time setup")
                .setMessage("Android only lets system apps turn the TV off. To use this button, turn on " +
                    "\"${getString(R.string.power_service_label)}\" in Settings > Accessibility. " +
                    "It only opens the TV's power menu, and reads nothing on screen.")
                .setPositiveButton("Open Accessibility") { _, _ ->
                    openFirst(
                        Intent().setClassName(TV_SETTINGS, "$TV_SETTINGS.oemlink.AccessibilitySettingsActivity"),
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                    )
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        // The TV's own power menu asks to confirm, so no second dialog here.
        hideQuick()
        if (!PowerService.showPowerMenu()) Toast.makeText(this, "Couldn't open the power menu", Toast.LENGTH_SHORT).show()
    }

    private fun showQuick() {
        if (quick.visibility == View.VISIBLE) return
        quick.visibility = View.VISIBLE
        quick.alpha = 0f
        quick.translationY = -24 * dp
        quick.animate().alpha(1f).translationY(0f).setDuration(220).start()
        chrome.animate().alpha(0f).setDuration(160).start()
        quickPanel.focusFirst()
    }

    private fun hideQuick() {
        if (quick.visibility != View.VISIBLE) return
        quick.animate().alpha(0f).translationY(-24 * dp).setDuration(180).withEndAction { quick.visibility = View.GONE }.start()
        chrome.animate().alpha(if (scroll.scrollY == 0) 1f else 0f).setDuration(220).start()
        (tiles.firstOrNull { focusedPkg != null && (it.tag as? App)?.pkg == focusedPkg } ?: tiles.firstOrNull())?.requestFocus()
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            val open = quick.visibility == View.VISIBLE
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_MENU -> { if (open) hideQuick() else showQuick(); return true }
                android.view.KeyEvent.KEYCODE_BACK, android.view.KeyEvent.KEYCODE_DPAD_DOWN -> if (open) { hideQuick(); return true }
                android.view.KeyEvent.KEYCODE_DPAD_UP ->
                    if (!open && (currentFocus as? Tile)?.row == 0) { showQuick(); return true }
                    else if (open) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Every connected HDMI input, hidden tiles included. */
    private fun showInputs() {
        val inputs = AppRepo.load(this).filter { it.pkg.startsWith(AppRepo.INPUT_PREFIX) }
        if (inputs.isEmpty()) {
            Toast.makeText(this, "No inputs found", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = prefs.labels
        AlertDialog.Builder(this, R.style.DriftwoodDialog)
            .setTitle("Switch input")
            .setItems(inputs.map { labels[it.pkg] ?: it.label }.toTypedArray()) { _, which ->
                hideQuick()
                open(inputs[which].launch)
            }
            .show()
    }

    /** Vendor settings screens differ between TVs, so try the specific one and fall back. */
    private fun openFirst(vararg intents: Intent) {
        for (i in intents) {
            // Google TV answers some settings actions with an empty "no app installed" stub.
            val target = i.resolveActivity(packageManager) ?: continue
            if (target.packageName == STUBS) continue
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                hideQuick()
                return
            } catch (e: Exception) { }
        }
        open(Intent(Settings.ACTION_SETTINGS))
    }

    @Deprecated("Back on a launcher just returns to the top row")
    override fun onBackPressed() = goHome()

    private fun goHome() {
        scroll.smoothScrollTo(0, 0)
        tiles.firstOrNull()?.requestFocus()
    }

    // ---- building the grid ----

    private fun reload(rebuild: Boolean = true) {
        val installed = AppRepo.load(this)
        val labels = prefs.labels
        val custom = TileArt.keys(this)
        val byPkg = installed.associateBy { it.pkg }.mapValues { (pkg, app) ->
            app.copy(
                label = labels[pkg] ?: app.label,
                banner = (if (TileArt.safeName(pkg) in custom) TileArt.load(this, pkg) else null) ?: app.banner,
            )
        }
        val hidden = prefs.hidden
        val order = prefs.order
        val mine = order.mapNotNull { byPkg[it] }.filter { it.pkg !in hidden } +
            byPkg.values.filter { it.pkg !in order && it.pkg !in hidden }.sortedBy { it.label.lowercase() }
        visible = if (prefs.sortMode == "used") byUsage(mine) else mine
        if (rebuild) buildGrid()
    }

    /** Most used apps first; unused apps and HDMI inputs keep your order after them. Stable sort. */
    private fun byUsage(list: List<App>): List<App> {
        val time = Usage.foregroundTime(this)
        if (time.isEmpty()) return list
        return list.sortedByDescending { if (it.pkg.startsWith(AppRepo.INPUT_PREFIX)) -1L else time[it.pkg] ?: 0L }
    }

    private fun buildGrid() {
        grid.removeAllViews()
        val edge = resources.getDimensionPixelSize(R.dimen.edge)
        val ring = (Tile.RING_GAP * dp).toInt()
        val gap = (28 * dp).toInt() - 2 * ring
        // The glass frame sits outside each card, so pull the grid out by that much
        // to keep card edges flush with the title, clock and bar text.
        grid.setPadding(edge - ring, grid.paddingTop, edge - ring, grid.paddingBottom)
        val w = (resources.displayMetrics.widthPixels - 2 * (edge - ring) - (COLUMNS - 1) * gap) / COLUMNS - 2 * ring
        val h = w * 9 / 16

        val appTiles = visible.map { app ->
            Tile(this, app.label, app.banner, app.icon, w, h).also { t ->
                t.tag = app
                t.setOnClickListener { launch(t, app) }
                t.setOnLongClickListener { showAppMenu(app); true }
            }
        }
        val settings = Tile(this, "Settings", null, getDrawable(R.drawable.ic_settings), w, h, 0xFF48484A.toInt()).also { t ->
            t.setOnClickListener { open(Intent(Settings.ACTION_SETTINGS)) }
            t.setOnLongClickListener { showDriftwoodMenu(); true }
        }
        // Voice search, for anyone who hasn't found the mic button on the remote.
        val search = Intent(Intent.ACTION_ASSIST).takeIf { it.resolveActivity(packageManager) != null }
            ?: Intent("android.search.action.GLOBAL_SEARCH").takeIf { it.resolveActivity(packageManager) != null }
        val searchTile = search?.let { intent ->
            Tile(this, "Search", null, getDrawable(R.drawable.ic_mic), w, h, 0xFF3A6FD8.toInt()).also { t ->
                t.setOnClickListener { open(intent) }
            }
        }
        tiles = appTiles + listOfNotNull(searchTile) + settings

        tiles.chunked(COLUMNS).forEachIndexed { r, rowTiles ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
                clipToPadding = false
            }
            rowTiles.forEachIndexed { c, t ->
                t.row = r
                t.onFocus = ::onTileFocused
                row.addView(t, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { if (c < COLUMNS - 1) marginEnd = gap })
            }
            grid.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = (22 * dp).toInt() })
        }

        val target = tiles.firstOrNull { focusedPkg != null && (it.tag as? App)?.pkg == focusedPkg } ?: tiles.first()
        target.post { target.requestFocus() }
    }

    // ---- focus → top shelf ----

    private fun onTileFocused(t: Tile) {
        val app = t.tag as? App
        focusedPkg = app?.pkg

        val rowView = t.parent as View
        val maxScroll = ((scroll.getChildAt(0)?.height ?: 0) - scroll.height).coerceAtLeast(0)
        val target = if (t.row == 0) 0 else
            (grid.top + rowView.top - 132 * dp).toInt().coerceIn(0, maxScroll)
        scroll.smoothScrollTo(0, target)

        // Rows that would end up under the sticky bar step aside instead of colliding with it.
        val barBottom = 100 * dp
        for (i in 0 until grid.childCount) {
            val row = grid.getChildAt(i)
            val hidden = t.row > 0 && grid.top + row.top - target < barBottom
            row.animate().alpha(if (hidden) 0f else 1f).setDuration(220).start()
        }

        val key = app?.pkg ?: "settings"
        if (key == shownKey) return
        shownKey = key
        val banner = app?.banner
        val icon = app?.icon ?: getDrawable(R.drawable.ic_settings)!!
        val a = art.getOrPut(key) { makeArt(banner ?: icon, banner == null) }

        // Briefly remind how to rearrange, then get out of the way.
        hint.animate().alpha(1f).setDuration(250).start()
        main.removeCallbacks(hideHint)
        main.postDelayed(hideHint, 2500)

        title.text = t.label
        title.alpha = 0f
        title.translationX = 16 * dp
        title.animate().alpha(1f).translationX(0f).setDuration(320).start()
        if (current != null) return

        // No wallpapers yet (offline): cross-fade the blurred app art instead.
        val next = backdrops[1 - front]
        val cur = backdrops[front]
        next.setImageBitmap(a.backdrop)
        next.animate().alpha(1f).setDuration(500).start()
        cur.animate().alpha(0f).setDuration(500).start()
        front = 1 - front

        // Ambient tint behind everything (visible once you scroll down).
        ambientAnim?.cancel()
        ambientAnim = ValueAnimator.ofArgb(ambientColor, a.tint).apply {
            duration = 500
            addUpdateListener { ambientColor = it.animatedValue as Int; ambient.setBackgroundColor(ambientColor) }
            start()
        }
    }

    // ---- rotating wallpapers ----

    private var evening: Boolean? = null

    /** Dim and warm the wallpaper between sunset and sunrise; checked with every photo change. */
    private fun applyEvening() {
        val now = Weather.isEvening(prefs)
        if (now == evening) return
        evening = now
        val filter = if (now) Weather.eveningFilter() else null
        backdrops.forEach { it.colorFilter = filter }
    }

    /** Each photo is shown once, ever. When the queue is empty the current one simply stays. */
    private fun nextPhoto() {
        val maxW = resources.displayMetrics.widthPixels
        Thread {
            val photo = Backgrounds.next(this) ?: return@Thread topUpPhotos()
            val bmp = Backgrounds.decode(photo, maxW) ?: return@Thread
            val average = bmp.averageColor()
            val tint = average.withValue(0.22f, 0.8f)
            runOnUiThread {
                current?.file?.delete()
                current = photo
                shownAt = System.currentTimeMillis()
                showPhoto(bmp, photo.caption, tint)
                credit.setTextColor(average.blendedText(0x8C))
            }
            topUpPhotos()
        }.start()
    }

    /** Cross-fade in, then a slow Ken Burns zoom-and-drift for the photo's whole turn. */
    private fun showPhoto(bmp: Bitmap, text: String, tint: Int) {
        val i = 1 - front
        val next = backdrops[i]
        kenBurns[i]?.cancel()
        next.setImageBitmap(bmp)
        next.scaleX = 1.02f; next.scaleY = 1.02f; next.translationX = 0f
        val dx = (if (shownCount++ % 2 == 0) 1 else -1) * 40 * dp
        kenBurns[i] = ObjectAnimator.ofPropertyValuesHolder(
            next,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1.02f, 1.14f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.02f, 1.14f),
            PropertyValuesHolder.ofFloat(View.TRANSLATION_X, 0f, dx),
        ).apply { duration = PHOTO_MS + 4000; interpolator = LinearInterpolator(); start() }
        next.animate().alpha(1f).setDuration(1600).start()
        backdrops[front].animate().alpha(0f).setDuration(1600).start()
        front = i

        credit.text = getString(R.string.photo_credit, text)

        ambientAnim?.cancel()
        ambientAnim = ValueAnimator.ofArgb(ambientColor, tint).apply {
            duration = 1600
            addUpdateListener { ambientColor = it.animatedValue as Int; ambient.setBackgroundColor(ambientColor) }
            start()
        }
    }

    /** Cheap, smooth blur: shrink the art to a few pixels and let bilinear filtering spread it. */
    private fun makeArt(d: Drawable, isIcon: Boolean): Art {
        val src = d.render(160, 90)
        val tint = (if (isIcon) d.render(48, 48, 0) else src).averageColor()
        val tiny = Bitmap.createScaledBitmap(src, 20, 12, true)
        val blurred = Bitmap.createBitmap(192, 108, Bitmap.Config.ARGB_8888)
        val vivid = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.8f) })
        }
        Canvas(blurred).drawBitmap(tiny, null, Rect(0, 0, 192, 108), vivid)
        return Art(blurred, tint.withValue(0.24f, 0.85f))
    }

    // ---- actions ----

    private fun launch(t: Tile, app: App) {
        val opts = ActivityOptions.makeScaleUpAnimation(t.card, 0, 0, t.card.width, t.card.height)
        try {
            startActivity(app.launch, opts.toBundle())
        } catch (e: Exception) {
            Toast.makeText(this, "Can't open ${app.label}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            hideQuick()
        } catch (e: Exception) {
            Toast.makeText(this, "Not available on this TV", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAppMenu(app: App) {
        val idx = visible.indexOf(app)
        val isInput = app.pkg.startsWith(AppRepo.INPUT_PREFIX)
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (prefs.sortMode != "used") actions += listOf(
            "Move to front" to { move(idx, 0) },
            "Move left" to { move(idx, idx - 1) },
            "Move right" to { move(idx, idx + 1) },
        )
        actions += listOf(
            "Rename" to { askRename(app) },
            "Change picture" to { pickPicture(app) },
        )
        if (TileArt.load(this, app.pkg) != null) actions += "Use original picture" to {
            TileArt.clear(this, app.pkg); art.remove(app.pkg); shownKey = null; reload()
        }
        actions += "Hide" to {
            prefs.hidden = prefs.hidden + app.pkg
            focusedPkg = null
            reload()
        }
        if (!isInput) actions += "App info" to {
            open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}")))
        }
        AlertDialog.Builder(this, R.style.DriftwoodDialog)
            .setTitle(app.label)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    private fun askRename(app: App) {
        val input = EditText(this).apply {
            setText(app.label)
            isSingleLine = true
            selectAll()
        }
        AlertDialog.Builder(this, R.style.DriftwoodDialog)
            .setTitle("Rename")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                prefs.labels = if (name.isEmpty()) prefs.labels - app.pkg else prefs.labels + (app.pkg to name)
                focusedPkg = app.pkg
                shownKey = null
                reload()
            }
            .setNeutralButton("Original name") { _, _ ->
                prefs.labels = prefs.labels - app.pkg
                shownKey = null
                reload()
            }
            .show()
    }

    private var pendingPicture: App? = null

    /** Pick any photo on the TV (send one over with LocalSend, or copy from USB). */
    private fun pickPicture(app: App) {
        val perm = if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_IMAGES
            else android.Manifest.permission.READ_EXTERNAL_STORAGE
        if (checkSelfPermission(perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingPicture = app
            requestPermissions(arrayOf(perm), REQ_PHOTOS)
            return
        }
        val images = TileArt.recentImages(this)
        if (images.isEmpty()) {
            Toast.makeText(this, "No photos on the TV yet. Send one with LocalSend or copy it from USB.", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this, R.style.DriftwoodDialog)
            .setTitle("Picture for ${app.label}")
            .setItems(images.map { it.name }.toTypedArray()) { _, which ->
                if (TileArt.save(this, app.pkg, images[which].uri)) {
                    art.remove(app.pkg); shownKey = null; focusedPkg = app.pkg; reload()
                } else Toast.makeText(this, "Couldn't read that picture", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        val app = pendingPicture ?: return
        pendingPicture = null
        if (requestCode == REQ_PHOTOS && results.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) pickPicture(app)
    }

    private fun move(from: Int, to: Int) {
        if (to !in visible.indices || from == to) return
        val list = visible.toMutableList()
        val app = list.removeAt(from)
        list.add(to, app)
        prefs.order = list.map { it.pkg }
        focusedPkg = app.pkg
        reload()
    }

    private fun showDriftwoodMenu() {
        val options = arrayOf("Weather location", "Wallpapers", "App order", "Show a hidden app", "Screensaver", "Check for updates")
        AlertDialog.Builder(this, R.style.DriftwoodDialog)
            .setTitle("Driftwood")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> askCity()
                    1 -> askWallpapers()
                    2 -> askOrder()
                    3 -> showHiddenMenu()
                    4 -> openFirst(
                        Intent(Settings.ACTION_DREAM_SETTINGS),
                        Intent().setClassName(TV_SETTINGS, "$TV_SETTINGS.device.display.daydream.DaydreamActivity"),
                    )
                    5 -> Updater.check(this, force = true) { release ->
                        if (release != null) offerUpdate(release)
                        else Toast.makeText(this, "Driftwood ${Updater.installedVersion(this)} is the latest", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun offerUpdate(release: Updater.Release) {
        if (isFinishing) return
        AlertDialog.Builder(this, R.style.DriftwoodDialog)
            .setTitle("Update available")
            .setMessage("Driftwood ${release.version} is ready (you have ${Updater.installedVersion(this)}).")
            .setPositiveButton("Install") { _, _ ->
                if (!packageManager.canRequestPackageInstalls()) {
                    AlertDialog.Builder(this, R.style.DriftwoodDialog)
                        .setTitle("Allow Driftwood to install updates")
                        .setMessage("Turn on Driftwood under Settings > Apps > Special app access > Install unknown apps, or from a computer:\n\n" +
                            "adb shell appops set $packageName REQUEST_INSTALL_PACKAGES allow")
                        .setPositiveButton("Open settings") { _, _ ->
                            openFirst(
                                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")),
                                Intent().setClassName(TV_SETTINGS, "$TV_SETTINGS.device.apps.specialaccess.SpecialAppAccessActivity"),
                            )
                        }
                        .setNegativeButton("Later", null)
                        .show()
                } else Updater.install(this, release)
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun askOrder() {
        val modes = arrayOf("mine", "used")
        AlertDialog.Builder(this, R.style.DriftwoodDialog)
            .setTitle("App order")
            .setSingleChoiceItems(arrayOf("My order", "Most used first"), modes.indexOf(prefs.sortMode).coerceAtLeast(0)) { d, which ->
                d.dismiss()
                if (modes[which] == "used" && !Usage.allowed(this)) {
                    AlertDialog.Builder(this, R.style.DriftwoodDialog)
                        .setTitle("One-time permission needed")
                        .setMessage("TVs hide the usage-access switch, so grant it once from a computer:\n\n" +
                            "adb shell appops set $packageName GET_USAGE_STATS allow\n\nThen choose Most used first again.")
                        .setPositiveButton("OK", null)
                        .show()
                    return@setSingleChoiceItems
                }
                prefs.sortMode = modes[which]
                focusedPkg = null
                reload()
            }
            .show()
    }

    private fun askWallpapers() {
        val sources = arrayOf("bing", "unsplash")
        val current = sources.indexOf(Backgrounds.source(this)).coerceAtLeast(0)
        AlertDialog.Builder(this, R.style.DriftwoodDialog)
            .setTitle("Wallpapers")
            .setSingleChoiceItems(arrayOf("Bing photo of the day", "Unsplash (via Picsum)"), current) { d, which ->
                setWallpapers(sources[which])
                d.dismiss()
            }
            .show()
    }

    private fun askCity() {
        val input = EditText(this).apply {
            hint = "City, e.g. Kochi"
            setText(prefs.city.orEmpty())
            isSingleLine = true
        }
        AlertDialog.Builder(this, R.style.DriftwoodDialog)
            .setTitle("Weather location")
            .setView(input)
            .setPositiveButton("Save") { _, _ -> setCity(input.text.toString()) }
            .setNeutralButton("Use my location") { _, _ -> setCity("") }
            .show()
    }

    private fun showHiddenMenu() {
        val hidden = prefs.hidden.toList()
        if (hidden.isEmpty()) {
            Toast.makeText(this, "No hidden apps", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = hidden.map { pkg ->
            try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }
            catch (e: Exception) { pkg }
        }
        AlertDialog.Builder(this, R.style.DriftwoodDialog)
            .setTitle("Show a hidden app")
            .setItems(labels.toTypedArray()) { _, which ->
                prefs.hidden = prefs.hidden - hidden[which]
                reload()
            }
            .show()
    }

    companion object {
        const val COLUMNS = 5
        const val PHOTO_MS = 120_000L
        const val REQ_PHOTOS = 7
        const val TV_SETTINGS = "com.android.tv.settings"
        const val STUBS = "com.google.android.tv.frameworkpackagestubs"
    }
}
