package com.jsolaresc.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Portadas para lo que suena por Bluetooth: este estéreo (Android 10) no recibe la
 * portada del celular, así que se busca por artista + canción en el buscador público
 * de iTunes (sin cuenta ni clave). Se guardan en el estéreo: la misma canción no se
 * vuelve a buscar y se ve aunque no haya señal.
 */
object Covers {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val memory = HashMap<String, Bitmap?>()
    private val pending = HashSet<String>()

    private fun key(artist: String?, title: String) = "${artist.orEmpty().trim().lowercase()}|${title.trim().lowercase()}"

    private fun file(ctx: Context, key: String) =
        File(File(ctx.filesDir, "covers").apply { mkdirs() }, Integer.toHexString(key.hashCode()) + ".jpg")

    /** Portada ya conocida (memoria o disco), o null. */
    fun cached(ctx: Context, artist: String?, title: String?): Bitmap? {
        if (title.isNullOrBlank()) return null
        val k = key(artist, title)
        if (memory.containsKey(k)) return memory[k]
        val f = file(ctx, k)
        if (f.exists()) {
            val bmp = BitmapFactory.decodeFile(f.path)
            memory[k] = bmp
            return bmp
        }
        return null
    }

    /** Busca en internet si no la tiene; avisa con onFound (en el hilo principal). */
    fun lookup(ctx: Context, artist: String?, title: String?, online: Boolean, onFound: () -> Unit) {
        if (title.isNullOrBlank() || !online) return
        val k = key(artist, title)
        if (memory.containsKey(k) || file(ctx, k).exists() || !pending.add(k)) return
        val app = ctx.applicationContext
        executor.execute {
            var bmp: Bitmap? = null
            try {
                val term = URLEncoder.encode(listOfNotNull(artist, title).joinToString(" "), "UTF-8")
                val conn = URL("https://itunes.apple.com/search?term=$term&entity=song&limit=1&country=AR")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 6000
                conn.readTimeout = 8000
                if (conn.responseCode == 200) {
                    val results = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optJSONArray("results")
                    val art = results?.optJSONObject(0)?.optString("artworkUrl100")
                    if (!art.isNullOrBlank()) {
                        val big = art.replace("100x100bb", "300x300bb")
                        val bytes = (URL(big).openConnection() as HttpURLConnection).run {
                            connectTimeout = 6000
                            readTimeout = 8000
                            inputStream.use { it.readBytes() }
                        }
                        bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null) file(app, k).writeBytes(bytes)
                    }
                }
            } catch (e: Exception) {
            }
            main.post {
                pending.remove(k)
                memory[k] = bmp
                if (bmp != null) onFound()
            }
        }
    }
}
