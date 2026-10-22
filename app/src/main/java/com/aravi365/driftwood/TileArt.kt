package com.aravi365.driftwood

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.MediaStore
import java.io.File

/**
 * Custom tile pictures. They live in the app's external files folder, so they can be picked
 * on the TV from any photo in Downloads/Pictures, or pushed straight in with adb:
 *   adb push banner.png /sdcard/Android/data/com.aravi365.driftwood/files/tiles/<package>.png
 */
object TileArt {
    class Image(val uri: Uri, val name: String)

    private fun dir(ctx: Context) = File(ctx.getExternalFilesDir(null), "tiles").apply { mkdirs() }

    private fun file(ctx: Context, key: String) =
        File(dir(ctx), safeName(key) + ".png")

    fun load(ctx: Context, key: String): Drawable? {
        val f = file(ctx, key)
        if (!f.exists()) return null
        return BitmapFactory.decodeFile(f.path)?.let { BitmapDrawable(ctx.resources, it) }
    }

    fun clear(ctx: Context, key: String) = file(ctx, key).delete()

    /** Keys that have a custom picture, from one listing of the folder (shared storage is slow). */
    fun keys(ctx: Context): Set<String> =
        dir(ctx).list()?.filter { it.endsWith(".png") }?.map { it.removeSuffix(".png") }?.toSet() ?: emptySet()

    fun safeName(key: String) = key.replace(Regex("[^A-Za-z0-9._-]"), "_")

    /** Recent photos on the TV, newest first (LocalSend and USB copies land here). */
    fun recentImages(ctx: Context): List<Image> {
        val cols = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME)
        val out = ArrayList<Image>()
        ctx.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cols, null, null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { c ->
            while (c.moveToNext() && out.size < 40) {
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                out += Image(uri, c.getString(1) ?: "Photo")
            }
        }
        return out
    }

    /** Crop the picture to the tile's 16:9 shape and keep a small copy. */
    fun save(ctx: Context, key: String, from: Uri): Boolean {
        val src = ctx.contentResolver.openInputStream(from)?.use { BitmapFactory.decodeStream(it) } ?: return false
        val w = 640
        val h = 360
        val scale = maxOf(w.toFloat() / src.width, h.toFloat() / src.height)
        val cw = (w / scale).toInt()
        val ch = (h / scale).toInt()
        val left = (src.width - cw) / 2
        val top = (src.height - ch) / 2
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, Rect(left, top, left + cw, top + ch), Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        file(ctx, key).outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return true
    }
}
