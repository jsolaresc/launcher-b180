package com.jsolaresc.launcher

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Actualización desde Ajustes: mira la última versión publicada en GitHub, la baja
 * y la instala con el instalador de Android (que pide confirmar). Sin navegador.
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/jsolaresc/launcher-b180/releases/latest"
    private const val ASSET = "launcher-b180.apk"
    const val ACTION_INSTALL = "com.jsolaresc.launcher.INSTALL_RESULT"

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var running = false
        private set

    fun run(ctx: Context, status: (String) -> Unit) {
        if (running) return
        running = true
        val app = ctx.applicationContext
        fun post(s: String) = main.post { status(s) }
        Thread {
            try {
                RealTime.installTlsFix()
                post(app.getString(R.string.update_checking))
                val json = JSONObject(get(LATEST).bufferedReader().use { it.readText() })
                val tag = json.optString("tag_name")
                val build = tag.removePrefix("build-").toIntOrNull()
                    ?: throw Exception("versión publicada desconocida ($tag)")
                val name = "0.1.$build"
                Diag.log("update", "publicada $tag, instalada ${BuildConfig.VERSION_CODE}")
                if (build <= BuildConfig.VERSION_CODE) {
                    post(app.getString(R.string.update_none, BuildConfig.VERSION_NAME))
                    return@Thread
                }
                val assets = json.getJSONArray("assets")
                var url: String? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name") == ASSET) url = a.optString("browser_download_url")
                }
                if (url == null) throw Exception("la versión $name no trae el APK")

                val file = File(app.cacheDir, "update.apk")
                get(url).use { input ->
                    file.outputStream().use { out ->
                        val total = lastLength
                        val buf = ByteArray(1 shl 16)
                        var done = 0L
                        var shown = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val pct = if (total > 0) (done * 100 / total).toInt() else 0
                            if (pct / 5 != shown) {
                                shown = pct / 5
                                post(app.getString(R.string.update_downloading, name, pct))
                            }
                        }
                    }
                }
                install(app, file)
                post(app.getString(R.string.update_installing, name))
            } catch (e: Exception) {
                Diag.log("update", "error: ${e.message}")
                post(app.getString(R.string.update_failed, e.message ?: e.javaClass.simpleName))
            } finally {
                running = false
            }
        }.start()
    }

    @Volatile
    private var lastLength = -1L

    /** GET siguiendo redirecciones (GitHub manda el APK desde otro dominio). */
    private fun get(address: String): java.io.InputStream {
        var url = URL(address)
        repeat(6) {
            val c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "launcher-b180")
            c.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream, */*")
            val code = c.responseCode
            if (code in 300..399) {
                url = URL(url, c.getHeaderField("Location"))
                c.disconnect()
                return@repeat
            }
            if (code != 200) throw Exception("el servidor respondió $code")
            lastLength = c.contentLengthLong
            return c.inputStream
        }
        throw Exception("demasiadas redirecciones")
    }

    private fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("launcher", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(ctx, InstallReceiver::class.java).setAction(ACTION_INSTALL)
            val pi = PendingIntent.getBroadcast(ctx, id, intent, PendingIntent.FLAG_UPDATE_CURRENT)
            session.commit(pi.intentSender)
        }
    }

    /** Respuesta del instalador: si pide confirmación, se muestra la pantalla de Android. */
    fun onInstallResult(ctx: Context, intent: Intent): String? {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return null
            ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return null
        }
        if (status == PackageInstaller.STATUS_SUCCESS) return null
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "código $status"
        Diag.log("update", "instalador: $msg")
        return ctx.getString(R.string.update_failed, msg)
    }
}

/** Recibe la respuesta del instalador de Android. */
class InstallReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val msg = Updater.onInstallResult(context, intent) ?: return
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
    }
}
