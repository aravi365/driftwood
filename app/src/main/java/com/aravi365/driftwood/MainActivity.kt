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
    private lateinit var caption: TextView

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
    private var photos: List<Photo> = emptyList()
    private var photoIndex = -1
    private val kenBurns = arrayOfNulls<ObjectAnimator>(2)
    private val rotate = object : Runnable {
        override fun run() {
            nextPhoto()
            Weather.refresh(prefs) { showWeather(it) }  // cached; only hits the network every 30 min
            main.postDelayed(this, PHOTO_MS)
        }
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            i.data?.schemeSpecificPart?.let { art.remove(it) }
            shownKey = null
            reload()
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
        caption = findViewById(R.id.caption)

        val m = resources.displayMetrics
        shelf.layoutParams.height = (m.heightPixels * 0.56f).toInt()

        scroll.setOnScrollChangeListener { _, _, y, _, _ ->
            val f = 1f - (y / (shelf.layoutParams.height * 0.7f)).coerceIn(0f, 1f)
            shelfLayer.alpha = 0.4f + 0.6f * f
            chrome.alpha = f
            bar.alpha = 1f - f
            caption.visibility = if (f < 0.6f) View.INVISIBLE else View.VISIBLE
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

        reload()
        applyExtras(intent)
        showWeather(prefs.weather)
        photos = Backgrounds.cached(this)
    }

    override fun onResume() {
        super.onResume()
        Weather.refresh(prefs) { showWeather(it) }
        kenBurns.forEach { it?.resume() }
        tiles.forEach { it.resumeMotion() }
        main.removeCallbacks(rotate)
        if (photos.isNotEmpty()) main.post(rotate)
        Thread {
            if (Backgrounds.refresh(this)) runOnUiThread {
                val first = photos.isEmpty()
                photos = Backgrounds.cached(this)
                if (first && photos.isNotEmpty()) { main.removeCallbacks(rotate); main.post(rotate) }
            }
        }.start()
    }

    override fun onPause() {
        // Nothing animates behind a playing video.
        main.removeCallbacks(rotate)
        kenBurns.forEach { it?.pause() }
        tiles.forEach { it.pauseMotion() }
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
        Thread {
            if (Backgrounds.refresh(this)) runOnUiThread {
                photos = Backgrounds.cached(this)
                photoIndex = -1
                main.removeCallbacks(rotate)
                main.post(rotate)
            }
        }.start()
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
        super.onDestroy()
    }

    /** Home pressed while already home: glide back to the first app. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyExtras(intent)
        goHome()
    }

    @Deprecated("Back on a launcher just returns to the top row")
    override fun onBackPressed() = goHome()

    private fun goHome() {
        scroll.smoothScrollTo(0, 0)
        tiles.firstOrNull()?.requestFocus()
    }

    // ---- building the grid ----

    private fun reload() {
        val installed = AppRepo.load(this)
        val byPkg = installed.associateBy { it.pkg }
        val hidden = prefs.hidden
        val order = prefs.order
        visible = order.mapNotNull { byPkg[it] }.filter { it.pkg !in hidden } +
            installed.filter { it.pkg !in order && it.pkg !in hidden }.sortedBy { it.label.lowercase() }
        buildGrid()
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
        tiles = appTiles + settings

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
        if (photos.isNotEmpty()) return

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

    private fun nextPhoto() {
        if (photos.isEmpty()) return
        photoIndex = (photoIndex + 1) % photos.size
        val photo = photos[photoIndex]
        val maxW = resources.displayMetrics.widthPixels
        Thread {
            val bmp = Backgrounds.decode(photo, maxW) ?: return@Thread
            val tint = bmp.averageColor().withValue(0.22f, 0.8f)
            runOnUiThread { showPhoto(bmp, photo.caption, tint) }
        }.start()
    }

    /** Cross-fade in, then a slow Ken Burns zoom-and-drift for the photo's whole turn. */
    private fun showPhoto(bmp: Bitmap, text: String, tint: Int) {
        val i = 1 - front
        val next = backdrops[i]
        kenBurns[i]?.cancel()
        next.setImageBitmap(bmp)
        next.scaleX = 1.02f; next.scaleY = 1.02f; next.translationX = 0f
        val dx = (if (photoIndex % 2 == 0) 1 else -1) * 40 * dp
        kenBurns[i] = ObjectAnimator.ofPropertyValuesHolder(
            next,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1.02f, 1.14f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.02f, 1.14f),
            PropertyValuesHolder.ofFloat(View.TRANSLATION_X, 0f, dx),
        ).apply { duration = PHOTO_MS + 4000; interpolator = LinearInterpolator(); start() }
        next.animate().alpha(1f).setDuration(1600).start()
        backdrops[front].animate().alpha(0f).setDuration(1600).start()
        front = i

        caption.animate().alpha(0f).setDuration(400).withEndAction {
            caption.text = text
            caption.animate().alpha(1f).setDuration(800).start()
        }.start()

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
        } catch (e: Exception) {
            Toast.makeText(this, "Not available on this TV", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAppMenu(app: App) {
        val idx = visible.indexOf(app)
        val isInput = app.pkg.startsWith(AppRepo.INPUT_PREFIX)
        val options = arrayOf("Move to front", "Move left", "Move right", "Hide") + if (isInput) emptyArray() else arrayOf("App info")
        AlertDialog.Builder(this, R.style.DriftwoodDialog)
            .setTitle(app.label)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> move(idx, 0)
                    1 -> move(idx, idx - 1)
                    2 -> move(idx, idx + 1)
                    3 -> {
                        prefs.hidden = prefs.hidden + app.pkg
                        focusedPkg = null
                        reload()
                    }
                    4 -> open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}")))
                }
            }
            .show()
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
        val options = arrayOf("Weather location", "Wallpapers", "Show a hidden app", "Screensaver")
        AlertDialog.Builder(this, R.style.DriftwoodDialog)
            .setTitle("Driftwood")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> askCity()
                    1 -> askWallpapers()
                    2 -> showHiddenMenu()
                    3 -> open(Intent(Settings.ACTION_DREAM_SETTINGS))
                }
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
    }
}
