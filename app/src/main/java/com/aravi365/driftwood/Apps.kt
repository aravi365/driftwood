package com.aravi365.driftwood

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import java.io.File
import java.util.Locale
import org.json.JSONObject
import android.graphics.drawable.Drawable
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.provider.Settings

data class App(
    val pkg: String,
    val label: String,
    val launch: Intent,
    val banner: Drawable?,
    val icon: Drawable,
)

object AppRepo {
    /** TV apps first (they ship wide banners), then phone-style apps that only have an icon. */
    fun load(ctx: Context): List<App> {
        val pm = ctx.packageManager
        val out = LinkedHashMap<String, App>()
        val cache = ArtCache(ctx)
        // The grid already ends with its own Settings tile, so skip the system Settings app.
        val settingsPkg = pm.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)?.activityInfo?.packageName
        for (cat in arrayOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (ri in pm.queryIntentActivities(query, 0)) {
                val ai = ri.activityInfo
                if (ai.packageName == ctx.packageName || ai.packageName == settingsPkg || ai.packageName in JUNK || ai.packageName in out) continue
                val launch = Intent(Intent.ACTION_MAIN)
                    .addCategory(cat)
                    .setClassName(ai.packageName, ai.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                val (label, banner, icon) = cache.get(ri)
                out[ai.packageName] = App(
                    pkg = ai.packageName,
                    label = label,
                    launch = launch,
                    banner = banner,
                    icon = icon,
                )
            }
        }
        cache.save()
        return out.values.toList() + inputs(ctx)
    }

    /** Physical HDMI ports with something connected, as tiles that switch the TV straight to that input. */
    private fun inputs(ctx: Context): List<App> {
        val tim = ctx.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager ?: return emptyList()
        val icon = ctx.getDrawable(R.drawable.ic_hdmi) ?: return emptyList()
        return tim.tvInputList
            .filter { it.type == TvInputInfo.TYPE_HDMI && it.parentId == null }
            // Nothing plugged in, nothing to show. Standby still counts (a console that's asleep).
            .filter { tim.getInputState(it.id) != TvInputManager.INPUT_STATE_DISCONNECTED }
            .map { info ->
                val launch = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(TvContract.buildChannelUriForPassthroughInput(info.id), TvContract.Channels.CONTENT_ITEM_TYPE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                App(INPUT_PREFIX + info.id, info.loadLabel(ctx).toString(), launch, null, icon.copy())
            }
            .sortedBy { it.label }
    }

    const val INPUT_PREFIX = "input:"

    /** Factory/vendor screens that ship a launcher entry but are no use day to day. */
    private val JUNK = setOf("com.mstar.android.tv.disclaimercustomization")
}

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("driftwood", Context.MODE_PRIVATE)

    var order: List<String>
        get() = sp.getString("order", null)?.split(',')?.filter { it.isNotBlank() } ?: DEFAULT_ORDER
        set(v) = sp.edit().putString("order", v.joinToString(",")).apply()

    var hidden: Set<String>
        get() = sp.getStringSet("hidden", null)?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("hidden", v).apply()

    var city: String?
        get() = sp.getString("city", null)
        set(v) = sp.edit().putString("city", v).remove("weather").remove("weather_at").apply()

    /** Last weather line and when it was fetched, so home and the screensaver show it instantly. */
    var weather: String?
        get() = sp.getString("weather", null)
        set(v) = sp.edit().putString("weather", v).putLong("weather_at", System.currentTimeMillis()).apply()

    /** Names the user gave tiles, keyed by package (or input id). */
    var labels: Map<String, String>
        get() {
            val json = org.json.JSONObject(sp.getString("labels", null) ?: "{}")
            return json.keys().asSequence().associateWith { json.getString(it) }
        }
        set(v) = sp.edit().putString("labels", org.json.JSONObject(v).toString()).apply()

    var sun: Pair<Int, Int>?
        get() = if (sp.contains("sunrise")) sp.getInt("sunrise", 0) to sp.getInt("sunset", 0) else null
        set(v) { if (v != null) sp.edit().putInt("sunrise", v.first).putInt("sunset", v.second).apply() }

    /** "mine" (the order you arranged) or "used" (most used in the last 30 days first). */
    var sortMode: String
        get() = sp.getString("sort", "mine")!!
        set(v) = sp.edit().putString("sort", v).apply()

    val weatherAge: Long
        get() = System.currentTimeMillis() - sp.getLong("weather_at", 0)

    companion object {
        val DEFAULT_ORDER = listOf(
            "com.google.android.youtube.tv",
            "com.google.android.youtube.tvmusic",
            "in.startv.hotstar",
            "in.startv.hotstartv",
            "in.startv.hotstar.dplus",
            "com.ionitech.airscreen",
            "com.netflix.ninja",
            "com.amazon.amazonvideo.livingroom",
        )
    }
}

/**
 * Each app's name and banner (or icon, for apps without one), kept on disk per app version and
 * language. Loading them means opening every other app's package, which is most of a cold start;
 * a small PNG and a line of JSON are far cheaper.
 */
private class ArtCache(private val ctx: Context) {
    private val dir = File(ctx.cacheDir, "art").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val index = try { JSONObject(indexFile.readText()) } catch (e: Exception) { JSONObject() }
    private var dirty = false

    fun get(ri: ResolveInfo): Triple<String, Drawable?, Drawable> {
        val ai = ri.activityInfo
        val stamp = "${File(ai.applicationInfo.sourceDir).lastModified()}-${Locale.getDefault().toLanguageTag()}"
        val entry = index.optJSONObject(ai.packageName)
        if (entry != null && entry.optString("stamp") == stamp) {
            val f = File(dir, entry.getString("file"))
            BitmapFactory.decodeFile(f.path)?.let { BitmapDrawable(ctx.resources, it) }?.let { art ->
                val label = entry.getString("label")
                return if (entry.getBoolean("banner")) Triple(label, art, art) else Triple(label, null, art)
            }
        }

        val pm = ctx.packageManager
        entry?.optString("file")?.let { File(dir, it).delete() }
        val label = ri.loadLabel(pm).toString()
        val banner = ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm)
        val icon = if (banner == null) ri.loadIcon(pm) else banner
        val name = "${ai.packageName}_${stamp.hashCode().toUInt()}.png"
        val bmp = banner?.render(384, 216) ?: icon.render(192, 192, 0)
        try {
            File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            index.put(ai.packageName, JSONObject().put("stamp", stamp).put("label", label).put("file", name).put("banner", banner != null))
            dirty = true
        } catch (e: Exception) { }
        return Triple(label, banner, icon)
    }

    fun save() {
        if (dirty) try { indexFile.writeText(index.toString()) } catch (e: Exception) { }
    }
}
