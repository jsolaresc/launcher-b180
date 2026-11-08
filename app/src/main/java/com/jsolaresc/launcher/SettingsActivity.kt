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
        RealTime.installTlsFix()
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
        setupOsmandHome()
        setupDockCard()

        // Arranque en frío del GPS: borra los datos viejos (por ejemplo, de cuando la fecha estaba en 2007).
        findViewById<View>(R.id.gps_reset).setOnClickListener {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            for (cmd in listOf("delete_aiding_data", "force_time_injection", "force_xtra_injection")) {
                try {
                    lm.sendExtraCommand(LocationManager.GPS_PROVIDER, cmd, null)
                } catch (e: Exception) {
                }
            }
            Toast.makeText(this, R.string.gps_reset_done, Toast.LENGTH_LONG).show()
        }
        findViewById<View>(R.id.gmaps_notifications).setOnClickListener {
            openFirst(
                Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, GMAPS)
            )
        }
        findViewById<View>(R.id.gmaps_pip).setOnClickListener {
            openFirst(
                Intent("android.settings.PICTURE_IN_PICTURE_SETTINGS", android.net.Uri.parse("package:$GMAPS")),
                Intent("android.settings.PICTURE_IN_PICTURE_SETTINGS")
            )
        }

        setupTheme()
        setupBottomCard()
        setupVolumes()
        setupOfflineMap()
    }

    private val GMAPS = "com.google.android.apps.maps"

    /** Inicio con el mapa de OsmAnd: interruptor y los dos permisos que necesita. */
    private fun setupOsmandHome() {
        val home = findViewById<Switch>(R.id.osmand_home)
        home.isChecked = Prefs.osmandHome(this)
        home.setOnCheckedChangeListener { _, on ->
            Prefs.setOsmandHome(this, on)
            if (on && !android.provider.Settings.canDrawOverlays(this)) askOverlay()
        }
        findViewById<View>(R.id.osmand_home_row).setOnClickListener { home.toggle() }
        findViewById<View>(R.id.perm_overlay).setOnClickListener { askOverlay() }
        findViewById<View>(R.id.perm_access).setOnClickListener {
            openFirst(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        val voice = findViewById<Switch>(R.id.pause_voice)
        voice.isChecked = Prefs.pauseForVoice(this)
        voice.setOnCheckedChangeListener { _, on -> Prefs.setPauseForVoice(this, on) }
        findViewById<View>(R.id.pause_voice_row).setOnClickListener { voice.toggle() }
    }

    override fun onResume() {
        super.onResume()
        // Estado del permiso a la vista: sin él, el inicio usa el mapa del launcher.
        val ok = android.provider.Settings.canDrawOverlays(this)
        findViewById<TextView>(R.id.perm_overlay).text =
            getString(if (ok) R.string.perm_overlay_ok else R.string.perm_overlay_missing)
    }

    private fun askOverlay() {
        openFirst(
            Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")),
            Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        )
    }

    /** Abre la primera pantalla de ajustes que exista; si ninguna, la ficha de la app. */
    private fun openFirst(vararg intents: Intent) {
        for (i in intents) {
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {
            }
        }
        try {
            startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$GMAPS"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
        }
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

    // ---------- Barra inferior ----------

    private val reqDockApp = 43
    private lateinit var dockList: LinearLayout
    private var dockItems: MutableList<DockConfig.Item> = mutableListOf()
    /** Acceso al que se le cambia la app (null: agregar uno nuevo). */
    private var dockTarget: String? = null

    private fun setupDockCard() {
        dockList = findViewById(R.id.dock_list)
        dockItems = DockConfig.load(this)
        renderDockList()
        findViewById<View>(R.id.dock_add).setOnClickListener {
            dockTarget = null
            pickDockApp()
        }
        findViewById<View>(R.id.dock_reset).setOnClickListener {
            AlertDialog.Builder(this, Theme.dialogTheme(this))
                .setMessage(R.string.dock_reset_confirm)
                .setPositiveButton(R.string.dock_reset) { _, _ ->
                    DockConfig.reset(this)
                    dockItems = DockConfig.load(this)
                    renderDockList()
                }
                .setNegativeButton(R.string.offline_cancel, null)
                .show()
        }
    }

    private fun saveDock() {
        DockConfig.save(this, dockItems)
        renderDockList()
    }

    private fun pickDockApp() {
        @Suppress("DEPRECATION")
        startActivityForResult(
            Intent(this, AppsActivity::class.java)
                .putExtra(AppsActivity.EXTRA_PICK_KEY, AppsActivity.PICK_RETURN)
                .putExtra(AppsActivity.EXTRA_PICK_LABEL, getString(R.string.dock_pick)),
            reqDockApp
        )
    }

    private fun onDockAppPicked(resultCode: Int, data: Intent?) {
        val flat = data?.getStringExtra(AppsActivity.EXTRA_COMPONENT)
        val component = flat?.let { ComponentName.unflattenFromString(it) }
        if (resultCode != RESULT_OK || component == null) return
        val label = data.getStringExtra(AppsActivity.EXTRA_APP_LABEL) ?: component.packageName
        val target = dockTarget?.let { id -> dockItems.firstOrNull { it.id == id } }
        if (target != null) {
            if (target.component != null) {
                target.component = component.flattenToString()
                if (DockConfig.ICONS.containsKey(target.icon).not()) target.icon = DockConfig.guessIcon(component.packageName)
            } else {
                // Fijo (Android Auto, Mapa, Radio, Teléfono): se guarda en Dock como antes.
                Dock.save(this, target.id, component)
            }
            saveDock()
            return
        }
        val item = DockConfig.newApp(component, label)
        dockItems.removeAll { it.id == item.id }
        // Antes de Ajustes, si está; si no, al final.
        val at = dockItems.indexOfFirst { it.id == DockConfig.SETTINGS && it.visible }
        if (at >= 0) dockItems.add(at, item) else dockItems.add(item)
        saveDock()
        chooseDockIcon(item)
    }

    private fun renderDockList() {
        val d = resources.displayMetrics.density
        dockList.removeAllViews()
        dockItems.forEachIndexed { index, item ->
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, (4 * d).toInt(), 0, (4 * d).toInt())
            row.isClickable = true
            row.setOnClickListener { editDockItem(item) }

            val icon = android.widget.ImageView(this)
            icon.setImageDrawable(DockConfig.drawable(this, item))
            icon.alpha = if (item.visible) 1f else 0.4f
            row.addView(icon, LinearLayout.LayoutParams((20 * d).toInt(), (20 * d).toInt()))

            val texts = LinearLayout(this)
            texts.orientation = LinearLayout.VERTICAL
            texts.setPadding((10 * d).toInt(), 0, 0, 0)
            val name = TextView(this)
            name.text = item.label
            name.setTextColor(getColor(if (item.visible) R.color.text else R.color.muted))
            name.textSize = 11f
            texts.addView(name)
            dockDetail(item)?.let {
                val sub = TextView(this)
                sub.text = it
                sub.setTextColor(getColor(R.color.muted))
                sub.textSize = 8f
                sub.maxLines = 1
                texts.addView(sub)
            }
            row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            fun arrow(res: Int, delta: Int) {
                val b = android.widget.ImageButton(this)
                b.setImageResource(res)
                b.setBackgroundResource(R.drawable.bg_option)
                b.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                b.setPadding((7 * d).toInt(), (7 * d).toInt(), (7 * d).toInt(), (7 * d).toInt())
                val target = index + delta
                b.isEnabled = target in dockItems.indices
                b.alpha = if (b.isEnabled) 1f else 0.3f
                b.setOnClickListener {
                    java.util.Collections.swap(dockItems, index, target)
                    saveDock()
                }
                val lp = LinearLayout.LayoutParams((32 * d).toInt(), (30 * d).toInt())
                lp.marginStart = (5 * d).toInt()
                row.addView(b, lp)
            }
            arrow(R.drawable.ic_prev, -1)
            arrow(R.drawable.ic_next, 1)

            val sw = Switch(this)
            sw.isChecked = item.visible
            sw.thumbTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.text))
            sw.trackTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.accent))
            sw.setOnCheckedChangeListener { _, on ->
                item.visible = on
                saveDock()
            }
            val slp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (30 * d).toInt())
            slp.marginStart = (8 * d).toInt()
            row.addView(sw, slp)
            dockList.addView(row)
        }
    }

    private fun appName(component: ComponentName): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(component.packageName, 0)).toString()
    } catch (e: Exception) {
        component.packageName
    }

    /** Qué abre cada acceso (renglón chico). */
    private fun dockDetail(item: DockConfig.Item): String? {
        item.component?.let { return getString(R.string.dock_app_detail, appName(ComponentName.unflattenFromString(it)!!)) }
        Dock.saved(this, item.id)?.let { if (item.id != DockConfig.SETTINGS) return getString(R.string.dock_app_detail, appName(it)) }
        return when (item.id) {
            DockConfig.AUTO -> getString(R.string.dock_auto_detail)
            DockConfig.MAPS -> getString(R.string.dock_app_detail, Dock.navigatorName(this))
            else -> null
        }
    }

    private fun editDockItem(item: DockConfig.Item) {
        val canChangeApp = item.component != null ||
            item.id in listOf(DockConfig.AUTO, DockConfig.MAPS, DockConfig.RADIO, DockConfig.PHONE)
        val options = ArrayList<Pair<Int, () -> Unit>>()
        options.add(R.string.dock_icon to { chooseDockIcon(item) })
        options.add(R.string.dock_rename to { renameDockItem(item) })
        if (canChangeApp) options.add(R.string.dock_change_app to {
            dockTarget = item.id
            pickDockApp()
        })
        if (item.component != null) options.add(R.string.dock_remove to {
            dockItems.remove(item)
            saveDock()
        })
        AlertDialog.Builder(this, Theme.dialogTheme(this))
            .setTitle(item.label)
            .setItems(options.map { getString(it.first) }.toTypedArray()) { _, which -> options[which].second() }
            .setNegativeButton(R.string.offline_cancel, null)
            .show()
    }

    private fun renameDockItem(item: DockConfig.Item) {
        val input = android.widget.EditText(this)
        input.setText(item.label)
        input.setSelection(item.label.length)
        input.setSingleLine()
        input.setTextColor(getColor(R.color.text))
        val d = resources.displayMetrics.density
        val box = android.widget.FrameLayout(this)
        box.setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0)
        box.addView(input)
        AlertDialog.Builder(this, Theme.dialogTheme(this))
            .setTitle(R.string.dock_rename)
            .setView(box)
            .setPositiveButton(R.string.dock_ok) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    item.label = text
                    saveDock()
                }
            }
            .setNegativeButton(R.string.offline_cancel, null)
            .show()
    }

    /** Grilla de íconos de línea (y el de la app, si es una agregada). */
    private fun chooseDockIcon(item: DockConfig.Item) {
        val d = resources.displayMetrics.density
        val keys = ArrayList<String>()
        if (item.component != null) keys.add(DockConfig.ICON_ORIGINAL)
        keys.addAll(DockConfig.ICONS.keys)
        var dialog: AlertDialog? = null
        val grid = android.widget.GridLayout(this)
        grid.columnCount = 8
        grid.setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), 0)
        for (key in keys) {
            val b = android.widget.ImageButton(this)
            val drawable = if (key == DockConfig.ICON_ORIGINAL) {
                DockConfig.drawable(this, DockConfig.Item("x", "", key, true, item.component))
            } else getDrawable(DockConfig.ICONS[key]!!)
            b.setImageDrawable(drawable)
            b.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            b.setBackgroundResource(if (key == item.icon) R.drawable.bg_dock_item_active else R.drawable.bg_option)
            b.setPadding((9 * d).toInt(), (9 * d).toInt(), (9 * d).toInt(), (9 * d).toInt())
            b.contentDescription = if (key == DockConfig.ICON_ORIGINAL) getString(R.string.dock_icon_original) else key
            b.setOnClickListener {
                item.icon = key
                saveDock()
                dialog?.dismiss()
            }
            val lp = android.widget.GridLayout.LayoutParams()
            lp.width = (40 * d).toInt()
            lp.height = (40 * d).toInt()
            lp.setMargins((3 * d).toInt(), (3 * d).toInt(), (3 * d).toInt(), (3 * d).toInt())
            grid.addView(b, lp)
        }
        dialog = AlertDialog.Builder(this, Theme.dialogTheme(this))
            .setTitle(getString(R.string.dock_icon) + " · " + item.label)
            .setView(android.widget.ScrollView(this).apply { addView(grid) })
            .setNegativeButton(R.string.offline_cancel, null)
            .show()
    }

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
        // A cuánto baja la música mientras habla OsmAnd (la voz va por "Notificaciones").
        addRow(container, R.string.vol_duck, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), Prefs.duckVolume(this)) { value, _ ->
            Prefs.setDuckVolume(this, value)
        }

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
        private const val KB_DARK_LABELS = 5
        private const val KB_LIGHT = 6
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
        watchImport()
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

    /** Con calles-amba, los nombres los dibuja el launcher: se bajan las teselas sin nombres. */
    private fun sources(): List<CartoTiles> {
        val own = Roads.available(this)
        val dark = if (own) listOf(NavMap.DARK_TILES) else listOf(NavMap.DARK_TILES, NavMap.DARK_LABELS)
        val light = listOf(if (own) NavMap.LIGHT_BASE else NavMap.LIGHT_TILES)
        return when (Prefs.theme(this)) {
            Prefs.THEME_LIGHT -> light
            Prefs.THEME_DARK -> dark
            else -> dark + light
        }
    }

    /** Teselas de las zonas, sumando los temas que se van a bajar. */
    private fun estimateMb(boxes: List<BoundingBox>): Int {
        val tiles = boxes.sumOf { tileCount(it) }
        val kb = sources().sumOf {
            when {
                it === NavMap.DARK_TILES -> KB_DARK
                it === NavMap.DARK_LABELS -> KB_DARK_LABELS
                else -> KB_LIGHT
            }
        }
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
        if (importing || MapImport.running || activeButton != null) return
        if (importFound()) return
        if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), reqStorage)
            return
        }
        pickFile()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != reqStorage) return
        if (!importFound()) pickFile()
    }

    private val callesName = "calles-amba.sqlite"

    /** Carga lo que haya en el pendrive: calles y radares (rápido) y el mapa (en tandas). */
    private fun importFound(): Boolean {
        val calles = findOnUsb(callesName)
        val mapa = findOnUsb(importName)
        val destinos = findDestinos()
        if (calles == null && mapa == null && destinos == null) return false
        if (destinos != null) importDestinos(destinos)
        if (calles != null) importCalles(calles, temporary = false)
        if (mapa != null) importFile(mapa, temporary = false)
        return true
    }

    /**
     * destinos.txt: un destino por renglón, "Nombre; dirección". Los 3 primeros quedan a la vista.
     * Las direcciones se ubican con los mapas de OsmAnd (sin internet) y quedan también como favoritos.
     */
    private fun importDestinos(file: File) {
        val bytes = try {
            file.readBytes()
        } catch (e: Exception) {
            ByteArray(0)
        }
        // UTF-8 (con o sin BOM) o, si lo guardó el Bloc de notas viejo, Windows-1252.
        var text = String(bytes, Charsets.UTF_8)
        if (text.contains('\uFFFD')) text = String(bytes, charset("windows-1252"))
        val lines = text.removePrefix("\uFEFF").lines()
            .map { it.trim().removePrefix("\uFEFF") }
            .filter { it.contains(";") && !it.startsWith("#") }
            .filter { it.substringBefore(";").isNotBlank() && it.substringAfter(";").isNotBlank() }
        if (lines.isEmpty()) {
            showDestinos(getString(R.string.destinos_empty))
            return
        }
        if (NavLaunch.osmand(this) == null) {
            showDestinos(getString(R.string.destinos_need_osmand))
            return
        }
        showDestinos(getString(R.string.destinos_searching, lines.size))
        val old = Places.load(this)
        val found = arrayOfNulls<Place>(lines.size)
        val missing = ArrayList<String>()
        var pending = lines.size
        var finished = false
        fun finish() {
            if (finished) return
            finished = true
            val list = found.filterNotNull()
            if (list.isEmpty()) {
                showDestinos(getString(R.string.destinos_none))
                return
            }
            Places.save(this, list)
            OsmAnd.syncFavorites(this, old, list)
            val all = lines.indices.filter { found[it] == null }.map { lines[it].substringBefore(";").trim() }
            showDestinos(
                if (all.isEmpty()) getString(R.string.destinos_done, list.size)
                else getString(R.string.destinos_partial, list.size, all.joinToString(", "))
            )
        }
        // Si OsmAnd no contesta (por ejemplo, sin el mapa de Buenos Aires), se avisa igual.
        main.postDelayed({ if (!finished) {
            if (found.any { it != null }) finish() else {
                finished = true
                showDestinos(getString(R.string.destinos_timeout))
            }
        } }, 30_000)
        lines.forEachIndexed { i, line ->
            val name = line.substringBefore(";").trim()
            val address = line.substringAfter(";").trim()
            searchAddress(address) { place ->
                if (place != null) found[i] = Place(name, place.first, place.second) else missing.add(name)
                pending--
                if (pending == 0) finish()
            }
        }
    }

    /** Busca la dirección completa; si no aparece, prueba solo calle y altura. */
    private fun searchAddress(address: String, done: (Pair<Double, Double>?) -> Unit) {
        OsmAnd.search(this, address, -34.6037, -58.3816) { results ->
            val r = results.firstOrNull()
            if (r != null) return@search done(r.latitude to r.longitude)
            val short = address.substringBefore(",").trim()
            if (short == address) return@search done(null)
            OsmAnd.search(this, short, -34.6037, -58.3816) { again ->
                val r2 = again.firstOrNull()
                done(r2?.let { it.latitude to it.longitude })
            }
        }
    }

    private fun showDestinos(text: String) {
        offlineStatus.text = text
        Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()
    }

    /** calles-amba: se copia tal cual (unos 20 MB) y el inicio pasa a usarlo. */
    private fun importCalles(file: File, temporary: Boolean) {
        val app = applicationContext
        Thread {
            var error: String? = null
            try {
                val tmp = File(app.filesDir, Roads.FILE + ".tmp")
                file.inputStream().use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
                Roads.reset()
                val target = Roads.file(app)
                target.delete()
                if (!tmp.renameTo(target)) error = "no se pudo guardar"
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
            if (temporary) file.delete()
            Roads.reset()
            main.post {
                val text = if (error == null) app.getString(R.string.import_calles_done)
                else app.getString(R.string.import_failed) + " (calles): " + error
                Toast.makeText(app, text, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun isSqlite(file: File): Boolean = try {
        val head = ByteArray(15)
        file.inputStream().use { it.read(head) } == 15 && String(head, Charsets.US_ASCII) == "SQLite format 3"
    } catch (e: Exception) {
        false
    }

    /** Archivo elegido con el selector: se mira qué es por su contenido. */
    private fun isCallesFile(file: File): Boolean = try {
        android.database.sqlite.SQLiteDatabase.openDatabase(file.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='ways'", null).use { it.moveToFirst() }
        }
    } catch (e: Exception) {
        false
    }

    /** Busca el archivo en la raíz (o una carpeta adentro) de cada memoria conectada. */
    private fun findOnUsb(name: String): File? = findOnUsb { it.name == name }

    /** destinos.txt sin importar mayúsculas ni el ".txt" repetido que deja Windows al ocultar extensiones. */
    private fun findDestinos(): File? = findOnUsb {
        val n = it.name.lowercase()
        n.startsWith("destinos") && n.endsWith(".txt")
    }

    private fun findOnUsb(match: (File) -> Boolean): File? {
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
            val top = root.listFiles() ?: continue
            top.firstOrNull { it.isFile && match(it) && it.canRead() }?.let { return it }
            for (sub in top) {
                if (!sub.isDirectory) continue
                sub.listFiles()?.firstOrNull { it.isFile && match(it) && it.canRead() }?.let { return it }
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
        if (requestCode == reqDockApp) {
            onDockAppPicked(resultCode, data)
            return
        }
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
                if (ok && !isSqlite(temp)) {
                    importDestinos(temp)
                    temp.delete()
                } else if (ok && isCallesFile(temp)) importCalles(temp, temporary = true)
                else if (ok) importFile(temp, temporary = true) else {
                    temp.delete()
                    offlineStatus.setText(R.string.import_failed)
                }
            }
        }
    }

    private fun importFile(file: File, temporary: Boolean) {
        MapImport.start(this, file, temporary, ambaArea())
        watchImport()
    }

    /** Muestra el avance real de la importación (sigue aunque se salga de Ajustes). */
    private fun watchImport() {
        if (!MapImport.running) {
            MapImport.listener = null
            return
        }
        importing = true
        offlineProgress.visibility = View.VISIBLE
        offlineProgress.max = 100
        fun render(p: Int) {
            when (p) {
                -1, -2 -> {
                    importing = false
                    offlineProgress.visibility = View.GONE
                    if (p == -1) showSaved()
                    else offlineStatus.text = getString(R.string.import_failed) + (MapImport.lastError?.let { ": $it" } ?: "")
                }
                else -> {
                    offlineProgress.progress = p
                    offlineStatus.text = getString(if (MapImport.copying) R.string.import_copy_progress else R.string.import_progress, p)
                }
            }
        }
        render(MapImport.percent)
        MapImport.listener = { render(it) }
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
        MapImport.listener = null
        worker.shutdown()
        super.onDestroy()
    }
}
