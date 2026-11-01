package com.jsolaresc.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.location.LocationManager
import android.media.AudioManager
import android.media.SoundPool
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.util.BoundingBox

class SettingsActivity : Activity() {

    private lateinit var audio: AudioManager
    private var soundPool: SoundPool? = null
    private var alertSound = 0
    private val downloads = mutableListOf<CacheManager>()
    private var writer: SqlTileWriter? = null

    override fun attachBaseContext(base: Context) = super.attachBaseContext(Theme.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NavMap.configure(this)
        setContentView(R.layout.activity_settings)
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        findViewById<View>(R.id.settings_back).setOnClickListener { finish() }
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            ""
        }
        findViewById<TextView>(R.id.settings_version).text = getString(R.string.version, version)

        setupTheme()
        setupBottomCard()
        setupVolumes()
        setupOfflineMap()
    }

    // ---------- Tema ----------

    private fun setupTheme() {
        val options = mapOf(
            R.id.theme_light to Prefs.THEME_LIGHT,
            R.id.theme_dark to Prefs.THEME_DARK,
            R.id.theme_auto to Prefs.THEME_AUTO
        )
        val current = Prefs.theme(this)
        for ((id, value) in options) {
            val view = findViewById<View>(id)
            view.isSelected = value == current
            view.setOnClickListener {
                if (value == Prefs.theme(this)) return@setOnClickListener
                Prefs.setTheme(this, value)
                recreate()
            }
        }
    }

    // ---------- Tarjeta inferior ----------

    private fun setupBottomCard() {
        val options = mapOf(
            R.id.card_places to Prefs.CARD_PLACES,
            R.id.card_clock to Prefs.CARD_CLOCK,
            R.id.card_compass to Prefs.CARD_COMPASS,
            R.id.card_speedo to Prefs.CARD_SPEEDO
        )
        fun render() {
            val current = Prefs.bottomCard(this)
            for ((id, value) in options) findViewById<View>(id).isSelected = value == current
        }
        for ((id, value) in options) {
            findViewById<View>(id).setOnClickListener {
                Prefs.setBottomCard(this, value)
                render()
            }
        }
        render()
    }

    // ---------- Volumen ----------

    private fun setupVolumes() {
        val container = findViewById<LinearLayout>(R.id.volumes)
        addStreamRow(container, R.string.vol_music, AudioManager.STREAM_MUSIC)

        // Fotomultas: volumen propio del launcher, con una muestra al soltar.
        soundPool = SoundPool.Builder().setMaxStreams(1).build().also {
            alertSound = it.load(this, R.raw.alerta, 1)
        }
        addRow(container, R.string.vol_alerts, 100, Prefs.alertVolume(this)) { value, done ->
            Prefs.setAlertVolume(this, value)
            if (done) soundPool?.play(alertSound, value / 100f, value / 100f, 1, 0, 1f)
        }

        addStreamRow(container, R.string.vol_notifications, AudioManager.STREAM_NOTIFICATION)
        addStreamRow(container, R.string.vol_system, AudioManager.STREAM_SYSTEM)
    }

    private fun addStreamRow(container: LinearLayout, label: Int, stream: Int) {
        val max = audio.getStreamMaxVolume(stream)
        addRow(container, label, max, audio.getStreamVolume(stream)) { value, _ ->
            try {
                audio.setStreamVolume(stream, value, 0)
            } catch (e: Exception) {
            }
        }
    }

    private fun addRow(container: LinearLayout, label: Int, max: Int, value: Int, onChange: (Int, Boolean) -> Unit) {
        val d = resources.displayMetrics.density
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL

        val name = TextView(this)
        name.setText(label)
        name.setTextColor(getColor(R.color.text))
        name.textSize = 10f
        row.addView(name, LinearLayout.LayoutParams((80 * d).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT))

        val bar = SeekBar(this)
        bar.max = max
        bar.progress = value
        bar.progressTintList = getColorStateList(R.color.accent)
        bar.progressBackgroundTintList = getColorStateList(R.color.track)
        bar.thumbTintList = getColorStateList(R.color.text)
        row.addView(bar, LinearLayout.LayoutParams(0, (30 * d).toInt(), 1f))

        val number = TextView(this)
        number.text = value.toString()
        number.setTextColor(getColor(R.color.muted))
        number.textSize = 9f
        number.gravity = Gravity.END
        row.addView(number, LinearLayout.LayoutParams((24 * d).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT))

        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                number.text = progress.toString()
                if (fromUser) onChange(progress, false)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) = onChange(seekBar.progress, true)
        })
        container.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (30 * d).toInt()))
    }

    // ---------- Mapa sin conexión ----------

    private lateinit var offlineButton: TextView
    private lateinit var ambaButton: TextView
    private lateinit var offlineStatus: TextView
    private lateinit var offlineProgress: ProgressBar
    private lateinit var radiusValue: TextView
    private lateinit var radiusEstimate: TextView
    /** Qué botón inició la descarga en curso (para mostrar "Cancelar" en ese). */
    private var activeButton: TextView? = null

    companion object {
        private const val ZOOM_MIN = 10
        private const val ZOOM_MAX = 16

        /** CABA, Zona Sur hasta La Plata y Zona Oeste hasta Moreno (rectángulos que se pisan poco). */
        private val AMBA = listOf(
            BoundingBox(-34.52, -58.33, -34.71, -58.54),
            BoundingBox(-34.64, -57.90, -34.96, -58.42),
            BoundingBox(-34.56, -58.50, -34.72, -58.82)
        )

        // Peso promedio de una tesela urbana, medido a ojo sobre CARTO: la oscura pesa menos.
        private const val KB_DARK = 9
        private const val KB_LIGHT = 20
    }

    private fun setupOfflineMap() {
        offlineButton = findViewById(R.id.offline_button)
        ambaButton = findViewById(R.id.amba_button)
        offlineStatus = findViewById(R.id.offline_status)
        offlineProgress = findViewById(R.id.offline_progress)
        radiusValue = findViewById(R.id.radius_value)
        radiusEstimate = findViewById(R.id.radius_estimate)

        findViewById<View>(R.id.radius_less).setOnClickListener { changeRadius(-5) }
        findViewById<View>(R.id.radius_more).setOnClickListener { changeRadius(5) }
        renderRadius()
        findViewById<TextView>(R.id.amba_estimate).text = getString(R.string.offline_estimate, estimateMb(AMBA))
        showSaved()

        offlineButton.setOnClickListener {
            if (activeButton != null) cancelDownload() else aroundBoxes()?.let { startDownload(it, offlineButton) }
        }
        ambaButton.setOnClickListener {
            if (activeButton != null) cancelDownload() else startDownload(AMBA, ambaButton)
        }
    }

    private fun changeRadius(delta: Int) {
        Prefs.setOfflineRadius(this, (Prefs.offlineRadius(this) + delta).coerceIn(5, 50))
        renderRadius()
    }

    private fun renderRadius() {
        radiusValue.text = getString(R.string.offline_radius, Prefs.offlineRadius(this))
        val boxes = aroundBoxes(quiet = true)
        radiusEstimate.text = if (boxes != null) getString(R.string.offline_estimate, estimateMb(boxes)) else ""
    }

    private fun sources() = when (Prefs.theme(this)) {
        Prefs.THEME_LIGHT -> listOf(NavMap.LIGHT_TILES)
        Prefs.THEME_DARK -> listOf(NavMap.DARK_TILES)
        else -> listOf(NavMap.DARK_TILES, NavMap.LIGHT_TILES)
    }

    /** Teselas de las zonas, sumando los temas que se van a bajar. */
    private fun estimateMb(boxes: List<BoundingBox>): Int {
        val tiles = boxes.sumOf { tileCount(it) }
        val kb = sources().sumOf { if (it === NavMap.DARK_TILES) KB_DARK else KB_LIGHT }
        return (tiles.toLong() * kb / 1024).toInt()
    }

    private fun tileCount(box: BoundingBox): Int {
        var total = 0
        for (z in ZOOM_MIN..ZOOM_MAX) {
            val n = 1 shl z
            fun x(lon: Double) = ((lon + 180) / 360 * n).toInt()
            fun y(lat: Double): Int {
                val r = Math.toRadians(lat)
                return ((1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * n).toInt()
            }
            total += (x(box.lonEast) - x(box.lonWest) + 1) * (y(box.latSouth) - y(box.latNorth) + 1)
        }
        return total
    }

    @SuppressLint("MissingPermission")
    private fun aroundBoxes(quiet: Boolean = false): List<BoundingBox>? {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val loc = try {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {
            null
        }
        if (loc == null) {
            if (!quiet) offlineStatus.text = getString(R.string.offline_no_location)
            return null
        }
        val km = Prefs.offlineRadius(this)
        val dLat = km / 111.0
        val dLon = km / (111.0 * Math.cos(Math.toRadians(loc.latitude)))
        return listOf(BoundingBox(loc.latitude + dLat, loc.longitude + dLon, loc.latitude - dLat, loc.longitude - dLon))
    }

    private fun cacheSizeMb(): Int {
        val dir = Configuration.getInstance().osmdroidTileCache
        val bytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return (bytes / (1024 * 1024)).toInt()
    }

    private fun showSaved() {
        offlineStatus.text = getString(R.string.offline_saved, cacheSizeMb())
    }

    private fun startDownload(boxes: List<BoundingBox>, button: TextView) {
        val w = writer ?: SqlTileWriter().also { writer = it }
        val jobs = sources().flatMap { source -> boxes.map { source to it } }
        var total = 0
        var done = 0
        var finished = 0
        var errors = 0
        activeButton = button
        button.setText(R.string.offline_cancel)
        offlineProgress.visibility = View.VISIBLE
        offlineProgress.progress = 0

        for ((source, box) in jobs) {
            val manager = CacheManager(source, w, ZOOM_MIN, ZOOM_MAX)
            downloads.add(manager)
            total += manager.possibleTilesInArea(box, ZOOM_MIN, ZOOM_MAX)
            manager.downloadAreaAsyncNoUI(this, box, ZOOM_MIN, ZOOM_MAX, object : CacheManager.CacheManagerCallback {
                private var mine = 0

                override fun onTaskComplete() = finishOne()

                override fun onTaskFailed(errors1: Int) {
                    errors += errors1
                    finishOne()
                }

                override fun updateProgress(progress: Int, currentZoomLevel: Int, zoomMin: Int, zoomMax: Int) {
                    done += progress - mine
                    mine = progress
                    offlineProgress.max = total
                    offlineProgress.progress = done
                    offlineStatus.text = getString(R.string.offline_progress, done, total)
                }

                override fun downloadStarted() {}
                override fun setPossibleTilesInArea(total1: Int) {}

                private fun finishOne() {
                    finished++
                    if (finished < jobs.size) return
                    endDownload()
                    offlineStatus.text = if (errors > 0) getString(R.string.offline_failed, errors)
                    else getString(R.string.offline_done)
                }
            })
        }
    }

    private fun endDownload() {
        downloads.clear()
        offlineProgress.visibility = View.GONE
        offlineButton.setText(R.string.offline_download)
        ambaButton.setText(R.string.offline_download_amba)
        activeButton = null
    }

    private fun cancelDownload() {
        downloads.forEach { it.cancelAllJobs() }
        endDownload()
        showSaved()
    }

    override fun onDestroy() {
        downloads.forEach { it.cancelAllJobs() }
        writer?.onDetach()
        soundPool?.release()
        super.onDestroy()
    }
}
