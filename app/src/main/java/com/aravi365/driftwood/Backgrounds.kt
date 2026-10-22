package com.aravi365.driftwood

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

class Photo(val id: String, val file: File, val caption: String)

/**
 * Wallpapers that never repeat. A small queue of unseen photos is kept on disk; each photo is
 * taken from the queue once, remembered as seen, and its file deleted after it's been replaced.
 *
 * Sources: Bing's photo of the day from the last ~15 days across a dozen countries (each picks
 * its own), then Unsplash via Picsum once those are used up, so there's always something new.
 */
object Backgrounds {
    private const val TARGET = 8
    private const val LOW = 3
    private const val MAX_SEEN = 5000
    private const val BING = "https://www.bing.com"
    private val MARKETS = listOf(
        "en-IN", "en-US", "en-GB", "en-CA", "en-AU", "de-DE", "fr-FR", "ja-JP", "zh-CN", "es-ES", "it-IT", "pt-BR",
    )

    private class Candidate(val id: String, val url: String, val caption: String)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("driftwood", Context.MODE_PRIVATE)
    private fun dir(ctx: Context) = File(ctx.cacheDir, "bg").apply { mkdirs() }

    fun source(ctx: Context) = prefs(ctx).getString("photo_source", "bing")!!

    /** "bing" or "unsplash". Drops the queue so the new source shows up right away. */
    @Synchronized
    fun setSource(ctx: Context, source: String) {
        prefs(ctx).edit().putString("photo_source", source).remove("queue").apply()
        dir(ctx).listFiles()?.forEach { it.delete() }
    }

    /** Next unseen photo, or null if the queue is empty (offline, or still downloading). */
    @Synchronized
    fun next(ctx: Context): Photo? {
        val queue = queue(ctx)
        while (queue.isNotEmpty()) {
            val p = queue.removeAt(0)
            if (p.file.length() == 0L) continue
            saveQueue(ctx, queue)
            markSeen(ctx, p.id)
            return p
        }
        saveQueue(ctx, queue)
        return null
    }

    fun needsMore(ctx: Context) = queue(ctx).size < LOW

    /** Blocking; call off the main thread. Tops the queue up with photos never shown before. */
    fun refresh(ctx: Context) {
        cleanUp(ctx)
        if (!needsMore(ctx)) return
        val seen = seen(ctx)
        val queued = queue(ctx).map { it.id }.toMutableSet()
        val fresh = ArrayList<Candidate>()
        if (source(ctx) == "bing") fresh += bing().filter { it.id !in seen && it.id !in queued }.shuffled()
        if (fresh.size < TARGET) fresh += unsplash(seen + queued)

        for (c in fresh) {
            if (queue(ctx).size >= TARGET) break
            if (c.id in queued) continue
            val f = File(dir(ctx), "bg_" + Integer.toHexString(c.id.hashCode()) + ".jpg")
            try { download(c.url, f) } catch (e: Exception) { f.delete(); continue }
            synchronized(this) {
                val q = queue(ctx)
                q += Photo(c.id, f, c.caption)
                saveQueue(ctx, q)
            }
            queued += c.id
        }
    }

    /** Decode near screen size so two photos in memory stay small. */
    fun decode(p: Photo, maxW: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(p.file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxW) sample *= 2
        return BitmapFactory.decodeFile(p.file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Files no longer queued (already shown, or left by older versions) go. Photos on screen are in memory. */
    @Synchronized
    private fun cleanUp(ctx: Context) {
        val keep = queue(ctx).map { it.file.name }.toSet()
        dir(ctx).listFiles()?.filter { it.name !in keep && !it.name.endsWith(".part") }?.forEach { it.delete() }
        prefs(ctx).edit().remove("photos").remove("photos_at").apply()
    }

    // ---- sources ----

    /** idx 0 and 7 together reach back about 15 days; same photo in two markets counts once. */
    private fun bing(): List<Candidate> {
        val out = LinkedHashMap<String, Candidate>()
        for (mkt in MARKETS) for (idx in listOf(0, 7)) {
            val images = try {
                JSONObject(read("$BING/HPImageArchive.aspx?format=js&idx=$idx&n=8&mkt=$mkt")).getJSONArray("images")
            } catch (e: Exception) { continue }
            for (i in 0 until images.length()) {
                val img = images.getJSONObject(i)
                val base = img.getString("urlbase")  // "/th?id=OHR.WolvesYellowstone_EN-IN1234567890"
                val id = "bing:" + base.substringAfter("id=").substringBefore("_")
                out.getOrPut(id) { Candidate(id, "$BING${base}_1920x1080.jpg", img.getString("copyright")) }
            }
        }
        return out.values.toList()
    }

    /** A random page of Picsum's Unsplash catalogue, with the photographer's name. */
    private fun unsplash(skip: Set<String>): List<Candidate> {
        repeat(4) {
            val list = try {
                JSONArray(read("https://picsum.photos/v2/list?limit=40&page=${Random.nextInt(1, 26)}"))
            } catch (e: Exception) { return emptyList() }
            val fresh = (0 until list.length()).map { list.getJSONObject(it) }
                .filter { it.getInt("width") > it.getInt("height") }  // landscape only
                .map { Candidate("picsum:" + it.getString("id"), "https://picsum.photos/id/${it.getString("id")}/1920/1080",
                    "Photo by ${it.getString("author")} on Unsplash") }
                .filter { it.id !in skip }
                .shuffled()
            if (fresh.isNotEmpty()) return fresh
        }
        return emptyList()
    }

    // ---- storage ----

    private fun queue(ctx: Context): MutableList<Photo> {
        val arr = try { JSONArray(prefs(ctx).getString("queue", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .map { Photo(it.getString("id"), File(dir(ctx), it.getString("file")), it.getString("caption")) }
            .toMutableList()
    }

    private fun saveQueue(ctx: Context, q: List<Photo>) {
        val arr = JSONArray()
        q.forEach { arr.put(JSONObject().put("id", it.id).put("file", it.file.name).put("caption", it.caption)) }
        prefs(ctx).edit().putString("queue", arr.toString()).apply()
    }

    private fun seen(ctx: Context): Set<String> =
        prefs(ctx).getString("seen", null)?.split('\n')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    private fun markSeen(ctx: Context, id: String) {
        val list = (prefs(ctx).getString("seen", "") ?: "").split('\n').filter { it.isNotEmpty() }.toMutableList()
        list += id
        prefs(ctx).edit().putString("seen", list.takeLast(MAX_SEEN).joinToString("\n")).apply()
    }

    // ---- network ----

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
