package com.jsolaresc.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.text.format.Formatter
import android.widget.Switch
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
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
    private val store by lazy { TileCache(this) }
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

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
        findViewById<View>(R.id.stereo_settings).setOnClickListener { openStereoSettings() }

        setupTheme()
        setupBottomCard()
        setupVolumes()
        setupOfflineMap()
    }

    /** Ajustes del fabricante (desde ahí se entra a los de fábrica con el código). */
    private fun openStereoSettings() {
        try {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .setComponent(ComponentName("com.ts.MainUI", "com.ts.set.SetMainActivity"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Toast.makeText(this, R.string.stereo_settings_missing, Toast.LENGTH_SHORT).show()
        }
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
        // Íconos a los costados: también bajan y suben de a un paso (10 en el volumen propio de 0 a 100).
        val step = if (max > 30) 10 else 1
        fun iconButton(icon: Int, desc: Int, delta: Int): android.widget.ImageButton {
            val b = android.widget.ImageButton(this)
            b.setImageResource(icon)
            b.contentDescription = getString(desc)
            b.setBackgroundResource(R.drawable.bg_round_button)
            b.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            val pad = (7 * d).toInt()
            b.setPadding(pad, pad, pad, pad)
            b.setOnClickListener {
                val v = (bar.progress + delta).coerceIn(0, max)
                bar.progress = v
                onChange(v, true)
            }
            return b
        }
        val iconSize = (28 * d).toInt()
        row.addView(iconButton(R.drawable.ic_vol_low, R.string.vol_down, -step), LinearLayout.LayoutParams(iconSize, iconSize))
        bar.max = max
        bar.progress = value
        bar.progressTintList = getColorStateList(R.color.accent)
        bar.progressBackgroundTintList = getColorStateList(R.color.track)
        bar.thumbTintList = getColorStateList(R.color.text)
        row.addView(bar, LinearLayout.LayoutParams(0, (30 * d).toInt(), 1f))
        row.addView(iconButton(R.drawable.ic_vol_high, R.string.vol_up, step), LinearLayout.LayoutParams(iconSize, iconSize))

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
        private const val ZOOM_MIN = OfflineAreas.ZOOM_MIN
        private const val ZOOM_MAX = OfflineAreas.ZOOM_MAX
        private const val AMBA_ID = "amba"
        private const val AROUND_ID = "around"

        /** CABA y Zonas Norte, Oeste y Sur (igual que tools/offline_map.py, el mapa para USB). */
        private val AMBA = listOf(
            BoundingBox(-34.52, -58.33, -34.71, -58.54),
            BoundingBox(-34.36, -58.44, -34.54, -58.95),
            BoundingBox(-34.54, -58.50, -34.80, -58.95),
            BoundingBox(-34.64, -57.88, -35.00, -58.50)
        )

        // Peso promedio de una tesela, medido con el mapa del AMBA (~12 KB las dos juntas).
        private const val KB_DARK = 4
        private const val KB_LIGHT = 8
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
            if (activeButton != null) cancelDownload() else aroundBoxes()?.let { startDownload(it, offlineButton, aroundArea(it)) }
        }
        ambaButton.setOnClickListener {
            if (activeButton != null) cancelDownload() else startDownload(AMBA, ambaButton, ambaArea())
        }

        val saveVisited = findViewById<Switch>(R.id.save_visited)
        saveVisited.isChecked = Prefs.saveVisited(this)
        saveVisited.setOnCheckedChangeListener { _, on -> Prefs.setSaveVisited(this, on) }
        findViewById<View>(R.id.save_visited_row).setOnClickListener { saveVisited.toggle() }

        findViewById<View>(R.id.offline_manage).setOnClickListener { showManage() }
        findViewById<View>(R.id.offline_import).setOnClickListener { importFromUsb() }
    }

    private fun ambaArea() = OfflineAreas.Area(AMBA_ID, getString(R.string.area_amba), AMBA, 0)

    private fun aroundArea(boxes: List<BoundingBox>) =
        OfflineAreas.Area(AROUND_ID, getString(R.string.area_around, Prefs.offlineRadius(this)), boxes, 0)

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
            total += (OfflineAreas.tileX(box.lonEast, z) - OfflineAreas.tileX(box.lonWest, z) + 1) *
                (OfflineAreas.tileY(box.latSouth, z) - OfflineAreas.tileY(box.latNorth, z) + 1)
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
        val fs = StatFs(filesDir.path)
        offlineStatus.text = getString(
            R.string.offline_saved, cacheSizeMb(),
            Formatter.formatShortFileSize(this, fs.availableBytes),
            Formatter.formatShortFileSize(this, fs.totalBytes)
        )
    }

    private fun startDownload(boxes: List<BoundingBox>, button: TextView, area: OfflineAreas.Area) {
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
                    OfflineAreas.save(
                        this@SettingsActivity,
                        OfflineAreas.Area(area.id, area.name, area.boxes, System.currentTimeMillis())
                    )
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

    // ---------- Importar desde USB ----------

    private val importName = "mapa-amba.sqlite"
    private val reqStorage = 41
    private val reqPick = 42
    private var importing = false

    private fun importFromUsb() {
        if (importing || activeButton != null) return
        findOnUsb()?.let { importFile(it, temporary = false); return }
        if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), reqStorage)
            return
        }
        pickFile()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != reqStorage) return
        val found = findOnUsb()
        if (found != null) importFile(found, temporary = false) else pickFile()
    }

    /** Busca el archivo en la raíz (o una carpeta adentro) de cada memoria conectada. */
    private fun findOnUsb(): File? {
        val roots = LinkedHashSet<File>()
        try {
            val sm = getSystemService(Context.STORAGE_SERVICE) as android.os.storage.StorageManager
            for (v in sm.storageVolumes) {
                try {
                    (v.javaClass.getMethod("getPath").invoke(v) as? String)?.let { roots.add(File(it)) }
                } catch (e: Exception) {
                }
            }
        } catch (e: Exception) {
        }
        for (base in listOf("/storage", "/mnt", "/mnt/media_rw")) {
            File(base).listFiles()?.forEach { roots.add(it) }
        }
        for (root in roots) {
            val direct = File(root, importName)
            if (direct.canRead()) return direct
            root.listFiles()?.forEach { sub ->
                val f = File(sub, importName)
                if (f.canRead()) return f
            }
        }
        return null
    }

    private fun pickFile() {
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
                reqPick
            )
        } catch (e: Exception) {
            offlineStatus.setText(R.string.import_not_found)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != reqPick) return
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            offlineStatus.setText(R.string.import_not_found)
            return
        }
        // Elegido con el selector: hay que copiarlo primero (no hay ruta directa).
        importing = true
        val temp = File(filesDir, "import.sqlite")
        worker.execute {
            val ok = try {
                contentResolver.openInputStream(uri)!!.use { input ->
                    temp.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16)
                        var total = 0L
                        var shown = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            total += n
                            val mb = (total / (1024 * 1024)).toInt()
                            if (mb / 10 != shown) {
                                shown = mb / 10
                                main.post { offlineStatus.text = getString(R.string.import_copying, mb) }
                            }
                        }
                    }
                }
                true
            } catch (e: Exception) {
                false
            }
            main.post {
                importing = false
                if (ok) importFile(temp, temporary = true) else {
                    temp.delete()
                    offlineStatus.setText(R.string.import_failed)
                }
            }
        }
    }

    private fun importFile(file: File, temporary: Boolean) {
        importing = true
        offlineStatus.setText(R.string.import_merging)
        worker.execute {
            val count = try {
                store.importArchive(file.path)
            } catch (e: Exception) {
                -1
            }
            if (temporary) file.delete()
            main.post {
                importing = false
                if (count > 0) {
                    OfflineAreas.save(this, OfflineAreas.Area(AMBA_ID, getString(R.string.area_amba), AMBA, System.currentTimeMillis()))
                    showSaved()
                    Toast.makeText(this, getString(R.string.import_done, count), Toast.LENGTH_LONG).show()
                } else {
                    offlineStatus.setText(R.string.import_failed)
                }
            }
        }
    }

    // ---------- Administrar mapas guardados ----------

    private var manageDialog: AlertDialog? = null

    private fun showManage() {
        val d = resources.displayMetrics.density
        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        list.setPadding((18 * d).toInt(), (6 * d).toInt(), (18 * d).toInt(), 0)
        val loading = TextView(this)
        loading.setText(R.string.manage_loading)
        loading.setTextColor(getColor(R.color.muted))
        list.addView(loading)

        manageDialog = AlertDialog.Builder(this, Theme.dialogTheme(this))
            .setTitle(R.string.manage_title)
            .setView(android.widget.ScrollView(this).apply { addView(list) })
            .setPositiveButton(R.string.manage_close, null)
            .show()

        val areas = OfflineAreas.list(this)
        worker.execute {
            val usage = try {
                store.usage(areas)
            } catch (e: Exception) {
                emptyMap()
            }
            main.post { fillManage(list, areas, usage) }
        }
    }

    private fun mb(bytes: Long?) = ((bytes ?: 0L) / (1024 * 1024)).toInt()

    private fun date(ms: Long) = SimpleDateFormat("d/M", Locale.getDefault()).format(Date(ms))

    private fun fillManage(list: LinearLayout, areas: List<OfflineAreas.Area>, usage: Map<String, Long>) {
        list.removeAllViews()
        for (area in areas) {
            val others = areas.filter { it.id != area.id }
            manageRow(
                list, area.name, getString(R.string.manage_area_detail, date(area.date), mb(usage[area.id])),
                onUpdate = { updateArea(area, others) },
                onDelete = {
                    confirmDelete(area.name) {
                        OfflineAreas.remove(this, area.id)
                        deleteTiles(mb(usage[area.id])) { key -> area.contains(key) && others.none { it.contains(key) } }
                    }
                }
            )
        }
        val visited = usage[""] ?: 0L
        if (visited > 0) {
            val name = getString(R.string.manage_visited)
            manageRow(list, name, getString(R.string.manage_visited_detail, mb(visited)), onUpdate = null, onDelete = {
                confirmDelete(name) { deleteTiles(mb(visited)) { key -> areas.none { it.contains(key) } } }
            })
        }
        val camerasAt = try {
            JSONObject(File(filesDir, "cameras.json").readText()).getLong("at")
        } catch (e: Exception) {
            0L
        }
        manageRow(
            list, getString(R.string.manage_radars),
            if (camerasAt > 0) getString(R.string.manage_radars_detail, date(camerasAt)) else getString(R.string.manage_radars_never),
            onUpdate = { updateRoadData() }, onDelete = null
        )
    }

    private fun manageRow(list: LinearLayout, title: String, detail: String, onUpdate: (() -> Unit)?, onDelete: (() -> Unit)?) {
        val d = resources.displayMetrics.density
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())

        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        val name = TextView(this)
        name.text = title
        name.setTextColor(getColor(R.color.text))
        name.textSize = 11f
        val sub = TextView(this)
        sub.text = detail
        sub.setTextColor(getColor(R.color.muted))
        sub.textSize = 9f
        texts.addView(name)
        texts.addView(sub)
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        fun button(label: Int, action: () -> Unit) {
            val b = TextView(this)
            b.setText(label)
            b.setTextColor(getColor(R.color.text))
            b.textSize = 10f
            b.gravity = Gravity.CENTER
            b.setBackgroundResource(R.drawable.bg_option)
            b.setPadding((12 * d).toInt(), 0, (12 * d).toInt(), 0)
            b.setOnClickListener { action() }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (32 * d).toInt())
            lp.marginStart = (6 * d).toInt()
            row.addView(b, lp)
        }
        onUpdate?.let { button(R.string.manage_update, it) }
        onDelete?.let { button(R.string.manage_delete, it) }
        list.addView(row)
    }

    private fun onWifi(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    private fun confirmDelete(name: String, action: () -> Unit) {
        AlertDialog.Builder(this, Theme.dialogTheme(this))
            .setMessage(getString(R.string.manage_confirm_delete, name))
            .setPositiveButton(R.string.manage_delete) { _, _ -> action() }
            .setNegativeButton(R.string.offline_cancel, null)
            .show()
    }

    private fun deleteTiles(freedMb: Int, match: (Long) -> Boolean) {
        manageDialog?.dismiss()
        offlineStatus.setText(R.string.manage_deleting)
        worker.execute {
            try {
                store.deleteWhere(match)
            } catch (e: Exception) {
            }
            main.post {
                showSaved()
                Toast.makeText(this, getString(R.string.manage_deleted, freedMb), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Vuelve a bajar la zona: borra lo viejo (sin tocar lo que comparte con otra zona) y descarga. */
    private fun updateArea(area: OfflineAreas.Area, others: List<OfflineAreas.Area>) {
        if (!onWifi()) {
            Toast.makeText(this, R.string.manage_need_wifi, Toast.LENGTH_SHORT).show()
            return
        }
        if (activeButton != null) return
        manageDialog?.dismiss()
        offlineStatus.setText(R.string.manage_deleting)
        worker.execute {
            try {
                store.deleteWhere { key -> area.contains(key) && others.none { it.contains(key) } }
            } catch (e: Exception) {
            }
            main.post {
                startDownload(area.boxes, if (area.id == AMBA_ID) ambaButton else offlineButton, area)
            }
        }
    }

    /** Radares y máximas/nombres de calles: se descartan y el mapa los vuelve a pedir con señal. */
    private fun updateRoadData() {
        File(filesDir, "cameras.json").delete()
        getSharedPreferences("maxspeed", Context.MODE_PRIVATE).edit().clear().apply()
        manageDialog?.dismiss()
        Toast.makeText(this, R.string.manage_radars_updated, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        downloads.forEach { it.cancelAllJobs() }
        writer?.onDetach()
        soundPool?.release()
        manageDialog?.dismiss()
        worker.shutdown()
        super.onDestroy()
    }
}
