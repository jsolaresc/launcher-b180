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
import java.io.File

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
    private lateinit var offlineStatus: TextView
    private lateinit var offlineProgress: ProgressBar

    private fun setupOfflineMap() {
        offlineButton = findViewById(R.id.offline_button)
        offlineStatus = findViewById(R.id.offline_status)
        offlineProgress = findViewById(R.id.offline_progress)
        showSaved()
        offlineButton.setOnClickListener {
            if (downloads.isNotEmpty()) cancelDownload() else startDownload()
        }
    }

    private fun cacheSizeMb(): Int {
        val dir = Configuration.getInstance().osmdroidTileCache
        val bytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return (bytes / (1024 * 1024)).toInt()
    }

    private fun showSaved() {
        offlineStatus.text = getString(R.string.offline_saved, cacheSizeMb())
    }

    @SuppressLint("MissingPermission")
    private fun startDownload() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val loc = try {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {
            null
        }
        if (loc == null) {
            offlineStatus.text = getString(R.string.offline_no_location)
            return
        }
        // ~20 km alrededor; zoom 10 a 16 (de ciudad a calle).
        val d = 0.18
        val box = BoundingBox(loc.latitude + d, loc.longitude + d, loc.latitude - d, loc.longitude - d)
        val sources = when (Prefs.theme(this)) {
            Prefs.THEME_LIGHT -> listOf(NavMap.LIGHT_TILES)
            Prefs.THEME_DARK -> listOf(NavMap.DARK_TILES)
            else -> listOf(NavMap.DARK_TILES, NavMap.LIGHT_TILES)
        }
        val w = SqlTileWriter().also { writer = it }
        var total = 0
        var done = 0
        var finished = 0
        var errors = 0
        offlineProgress.visibility = View.VISIBLE
        offlineProgress.progress = 0
        offlineButton.setText(R.string.offline_cancel)

        for (source in sources) {
            val manager = CacheManager(source, w, 10, 16)
            downloads.add(manager)
            val count = manager.possibleTilesInArea(box, 10, 16)
            total += count
            manager.downloadAreaAsyncNoUI(this, box, 10, 16, object : CacheManager.CacheManagerCallback {
                private var mine = 0

                override fun onTaskComplete() = finishOne(false)

                override fun onTaskFailed(errors1: Int) {
                    errors += errors1
                    finishOne(true)
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

                private fun finishOne(failed: Boolean) {
                    finished++
                    if (finished < sources.size) return
                    downloads.clear()
                    offlineProgress.visibility = View.GONE
                    offlineButton.setText(R.string.offline_download)
                    offlineStatus.text = if (errors > 0 || failed) getString(R.string.offline_failed, errors)
                    else getString(R.string.offline_done)
                }
            })
        }
    }

    private fun cancelDownload() {
        downloads.forEach { it.cancelAllJobs() }
        downloads.clear()
        offlineProgress.visibility = View.GONE
        offlineButton.setText(R.string.offline_download)
        showSaved()
    }

    override fun onDestroy() {
        downloads.forEach { it.cancelAllJobs() }
        writer?.onDetach()
        soundPool?.release()
        super.onDestroy()
    }
}
