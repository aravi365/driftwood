package com.aravi365.driftwood

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.provider.Settings

class App(
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
                out[ai.packageName] = App(
                    pkg = ai.packageName,
                    label = ri.loadLabel(pm).toString(),
                    launch = launch,
                    banner = ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm),
                    icon = ri.loadIcon(pm),
                )
            }
        }
        return out.values.toList() + inputs(ctx)
    }

    /** Physical HDMI ports as tiles that switch the TV straight to that input. */
    private fun inputs(ctx: Context): List<App> {
        val tim = ctx.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager ?: return emptyList()
        val icon = ctx.getDrawable(R.drawable.ic_hdmi) ?: return emptyList()
        return tim.tvInputList
            .filter { it.type == TvInputInfo.TYPE_HDMI && it.parentId == null }
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
