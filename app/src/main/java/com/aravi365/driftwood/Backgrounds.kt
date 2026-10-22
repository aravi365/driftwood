package com.aravi365.driftwood

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class Photo(val file: File, val caption: String)

/**
 * Free rotating wallpapers: Bing's 8 daily photos (no key), falling back to Picsum (Unsplash).
 * Files live in the cache dir and are refreshed at most every 6 hours.
 */
object Backgrounds {
    private const val STALE_MS = 6 * 60 * 60 * 1000L
    private const val BING = "https://www.bing.com"

    fun cached(ctx: Context): List<Photo> {
        val list = ctx.getSharedPreferences("driftwood", Context.MODE_PRIVATE).getString("photos", null) ?: return emptyList()
        val arr = JSONArray(list)
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .map { Photo(File(dir(ctx), it.getString("file")), it.getString("caption")) }
            .filter { it.file.length() > 0 }
    }

    /** Blocking; call off the main thread. Returns true when the set changed. */
    fun refresh(ctx: Context): Boolean {
        val sp = ctx.getSharedPreferences("driftwood", Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - sp.getLong("photos_at", 0) < STALE_MS && cached(ctx).isNotEmpty()) return false
        val useBing = sp.getString("photo_source", "bing") == "bing"
        val wanted = (if (useBing) try { bing() } catch (e: Exception) { null } else null) ?: picsum()
        val out = JSONArray()
        for ((url, caption) in wanted) {
            val name = "bg_" + Integer.toHexString(url.hashCode()) + ".jpg"
            val f = File(dir(ctx), name)
            if (f.length() == 0L) try { download(url, f) } catch (e: Exception) { f.delete(); continue }
            out.put(JSONObject().put("file", name).put("caption", caption))
        }
        if (out.length() == 0) return false
        val keep = (0 until out.length()).map { out.getJSONObject(it).getString("file") }.toSet()
        dir(ctx).listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
        sp.edit().putString("photos", out.toString()).putLong("photos_at", System.currentTimeMillis()).apply()
        return true
    }

    /** "bing" or "unsplash"; forces a fresh download on the next refresh. */
    fun setSource(ctx: Context, source: String) {
        ctx.getSharedPreferences("driftwood", Context.MODE_PRIVATE).edit()
            .putString("photo_source", source).putLong("photos_at", 0).apply()
    }

    fun source(ctx: Context) =
        ctx.getSharedPreferences("driftwood", Context.MODE_PRIVATE).getString("photo_source", "bing")!!

    /** Decode near screen size so two photos in memory stay small. */
    fun decode(p: Photo, maxW: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(p.file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxW) sample *= 2
        return BitmapFactory.decodeFile(p.file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun bing(): List<Pair<String, String>> {
        val json = JSONObject(read("$BING/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=en-IN"))
        val images = json.getJSONArray("images")
        return (0 until images.length()).map { images.getJSONObject(it) }.map {
            "$BING${it.getString("urlbase")}_1920x1080.jpg" to it.getString("copyright")
        }
    }

    private fun picsum(): List<Pair<String, String>> {
        val day = System.currentTimeMillis() / 86_400_000L
        return (0 until 8).map { "https://picsum.photos/seed/driftwood$day$it/1920/1080" to "Photo from Unsplash via Picsum" }
    }

    private fun dir(ctx: Context) = File(ctx.cacheDir, "bg").apply { mkdirs() }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            instanceFollowRedirects = true
        }

    private fun read(url: String): String {
        val c = open(url)
        return try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
    }

    private fun download(url: String, to: File) {
        val c = open(url)
        try {
            val tmp = File(to.path + ".part")
            c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.renameTo(to)
        } finally { c.disconnect() }
    }
}
