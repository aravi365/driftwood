package com.aravi365.driftwood

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks the GitHub releases for a newer Driftwood and installs it on the TV, so updating
 * never needs a computer. Looks at most twice a day.
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/aravi365/driftwood/releases/latest"
    private const val EVERY_MS = 12 * 60 * 60 * 1000L
    private val main = Handler(Looper.getMainLooper())

    class Release(val version: String, val apkUrl: String)

    fun check(ctx: Context, force: Boolean = false, found: (Release?) -> Unit) {
        val sp = ctx.getSharedPreferences("driftwood", Context.MODE_PRIVATE)
        if (!force && System.currentTimeMillis() - sp.getLong("update_checked", 0) < EVERY_MS) return
        sp.edit().putLong("update_checked", System.currentTimeMillis()).apply()
        Thread {
            val release = try { latest() } catch (e: Exception) { null }
            val newer = release?.takeIf { isNewer(it.version, installedVersion(ctx)) }
            main.post { found(newer) }
        }.start()
    }

    fun installedVersion(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"

    /** "1.10" > "1.9": compare dotted numbers part by part. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split('.').map { it.toIntOrNull() ?: 0 }
        val b = current.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun latest(): Release? {
        val c = URL(LATEST).openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "Driftwood")
        c.connectTimeout = 8000
        c.readTimeout = 8000
        val json = try { JSONObject(c.inputStream.bufferedReader().readText()) } finally { c.disconnect() }
        val assets = json.getJSONArray("assets")
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.getString("name").endsWith(".apk") } ?: return null
        return Release(json.getString("tag_name").removePrefix("v"), apk.getString("browser_download_url"))
    }

    /** Download, then hand it to the system installer, which asks once to confirm. */
    fun install(ctx: Context, release: Release) {
        Toast.makeText(ctx, "Downloading Driftwood ${release.version}…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val apk = File(ctx.cacheDir, "update.apk")
                val c = URL(release.apkUrl).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = true
                c.connectTimeout = 10_000
                c.readTimeout = 30_000
                try { c.inputStream.use { input -> apk.outputStream().use { input.copyTo(it) } } } finally { c.disconnect() }

                val installer = ctx.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                val id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    session.openWrite("driftwood.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) } }
                    val done = PendingIntent.getBroadcast(
                        ctx, id, Intent(ctx, InstallResult::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    )
                    session.commit(done.intentSender)
                }
            } catch (e: Exception) {
                main.post { Toast.makeText(ctx, "Update failed: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    /** The installer reports back here; the first time it needs the user to confirm. */
    class InstallResult : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                    ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                PackageInstaller.STATUS_SUCCESS -> Unit  // the new version restarts as home
                else -> Toast.makeText(
                    ctx, "Update failed: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}", Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
}
