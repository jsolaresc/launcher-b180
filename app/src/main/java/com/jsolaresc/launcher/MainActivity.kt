package com.jsolaresc.launcher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.osmdroid.views.MapView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val locale = Locale("es", "AR")
    private val timeFormat = SimpleDateFormat("HH:mm", locale)
    private val dateFormat = SimpleDateFormat("EEEE d MMM", locale)
    private val dialogTheme get() = Theme.dialogTheme(this)

    /** Con el inicio sobre OsmAnd, los diálogos se muestran encima (si no, quedarían detrás). */
    private fun AlertDialog.Builder.showHere(): AlertDialog {
        val dialog = create()
        if (overlay != null) dialog.window?.setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
        return dialog
    }

    // ---------- Inicio con el mapa de OsmAnd ----------

    private var overlay: HomeOverlay? = null

    private fun osmandHomeWanted() = Prefs.osmandHome(this) && NavLaunch.osmand(this) != null &&
        android.provider.Settings.canDrawOverlays(this)

    /** OsmAnd adelante, con el panel del launcher encima. */
    private fun bringOsmAnd() {
        val o = overlay ?: return
        val pkg = NavLaunch.osmand(this) ?: return
        o.setFull(false)
        o.show()
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return
        try {
            super.startActivity(
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        } catch (e: Exception) {
        }
        // OsmAnd aplica los márgenes cuando su mapa ya está abierto.
        handler.postDelayed({ overlay?.applyToOsmAnd() }, 1500)
        handler.postDelayed({ overlay?.applyToOsmAnd() }, 5000)
    }

    /** Sin el permiso de Accesibilidad no se sabe qué app está adelante: se esconde al abrir otra desde acá. */
    override fun startActivity(intent: Intent?) {
        val o = overlay
        if (o != null && intent != null) {
            val target = intent.component?.packageName ?: intent.`package`
            val toOsmAnd = target != null && NavLaunch.isOsmand(target)
            val toHome = intent.component?.className == MainActivity::class.java.name
            if (!toOsmAnd && !toHome) o.hide()
        }
        super.startActivity(intent)
    }

    private fun onForeground(pkg: String, cls: String?) {
        val o = overlay ?: return
        when {
            NavLaunch.isOsmand(pkg) -> o.show()
            pkg == packageName && cls == MainActivity::class.java.name -> o.show()
            else -> o.hide()
        }
    }

    // ---------- Voz de OsmAnd sobre la música del celular ----------

    private var pausedForVoice = false
    private val resumeAfterVoice = Runnable {
        if (pausedForVoice) {
            pausedForVoice = false
            music.play()
        }
    }

    /**
     * El Bluetooth de este estéreo no baja ni pausa la música cuando OsmAnd habla, y la voz
     * no se escucha. El launcher pausa la música del celular mientras dura la indicación.
     */
    private fun onNavigationVoice(phrases: List<String>) {
        if (!Prefs.pauseForVoice(this) || !source.isBluetooth) return
        if (!pausedForVoice) {
            if (!music.isPlaying) return
            if (music.pause()) pausedForVoice = true else return
        }
        val chars = phrases.sumOf { it.length }
        val ms = (1200L + chars * 75L).coerceAtMost(9000L)
        handler.removeCallbacks(resumeAfterVoice)
        handler.postDelayed(resumeAfterVoice, ms)
    }
    private var startedNight = false
    private var resumedFully = false

    // Reloj y estado
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var statusWifi: ImageView
    private lateinit var weatherIcon: ImageView
    private lateinit var weatherTemp: TextView

    // Música
    private lateinit var music: Music
    private lateinit var source: Source
    private lateinit var sourceButton: View
    private lateinit var sourceIcon: ImageView
    private lateinit var musicSource: TextView
    private lateinit var trackAlbum: TextView
    private lateinit var playButton: ImageButton
    private lateinit var trackTitle: TextView
    private lateinit var trackArtist: TextView
    private lateinit var cover: ImageView
    private lateinit var coverPlaceholder: ImageView
    private lateinit var progressGroup: View
    private lateinit var progress: ProgressBar
    private lateinit var timeNow: TextView
    private lateinit var timeTotal: TextView
    private lateinit var volume: SeekBar
    private lateinit var audio: AudioManager

    // Mapa
    private lateinit var navMap: NavMap
    private lateinit var navBanner: View
    private lateinit var navIcon: ImageView
    private lateinit var navTitle: TextView
    private lateinit var navText: TextView
    private lateinit var mapSearch: View
    private lateinit var speedText: TextView
    private lateinit var speedUnit: TextView
    private lateinit var speedBadge: View
    private lateinit var speedLimitView: TextView
    private lateinit var speedGroup: View
    private lateinit var mapButtons: View
    private lateinit var pickCross: View
    private lateinit var pickBar: View
    private lateinit var speedLimit: SpeedLimit
    private lateinit var weather: Weather
    private var currentLimit: Int? = null
    private lateinit var cameras: Cameras
    private lateinit var cameraAlert: View
    private lateinit var cameraAlertTitle: TextView
    private lateinit var cameraAlertDetail: TextView
    private lateinit var btnAlerts: ImageButton
    private var alertVisible = false

    // Servicio del estéreo: USB, radio y fuente activa.
    private lateinit var ts: TsCommon
    private var tsMode: Int? = null
    private var tsTitle: String? = null
    private var tsPlaying = false
    private var tsCur = 0
    private var tsTotal = 0
    private var radioFreq: String? = null
    private var radioBand: String? = null

    // Destinos
    private lateinit var placesView: LinearLayout
    private var places: MutableList<Place> = mutableListOf()
    private val placeDetails = mutableListOf<TextView>()
    /** -1: agregando uno nuevo; >= 0: moviendo ese; null: no se está eligiendo. */
    private var pickingIndex: Int? = null

    // Tarjeta inferior (destinos, reloj, brújula o velocímetro)
    private var bottomCard = Prefs.CARD_PLACES
    private var cardMain: TextView? = null
    private var cardSub: TextView? = null
    private var cardNeedle: ImageView? = null

    private var online = false
    private var startedWithRoads = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshVolume()
        }
    }

    // La hora se actualiza justo al cambiar de minuto.
    private val tick = object : Runnable {
        override fun run() {
            updateClock()
            val seconds = Calendar.getInstance().get(Calendar.SECOND)
            handler.postDelayed(this, (60 - seconds) * 1000L)
        }
    }

    // USB y radio no tienen sesión de música: se consulta al estéreo cada segundo.
    private val tsTick = object : Runnable {
        override fun run() {
            pollTs()
            handler.postDelayed(this, 1000)
        }
    }

    // Tema automático: luces del auto (cable de iluminación) o, si no, el sol.
    private val themeTick = object : Runnable {
        override fun run() {
            checkAutoTheme()
            handler.postDelayed(this, 20_000)
        }
    }

    // El progreso de la canción avanza de a un segundo mientras suena.
    private val progressTick = object : Runnable {
        override fun run() {
            renderProgress()
            if (music.isPlaying) handler.postDelayed(this, 1000)
        }
    }

    override fun attachBaseContext(base: Context) = super.attachBaseContext(Theme.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RealTime.installTlsFix()
        startedWithRoads = Roads.available(this)
        NavMap.configure(this)
        CartoTiles.purgeOld(this)
        startedNight = Theme.isNight(this)
        setContentView(R.layout.activity_main)
        ts = TsCommon(this)
        ts.connect()

        setupMap()
        setupMusic()
        setupDock()
        if (NavLaunch.osmand(this) != null) {
            OsmAnd.connect(this)
            OsmAnd.onVoice = { onNavigationVoice(it) }
        }

        placesView = findViewById(R.id.places)
        places = Places.load(this)
        savedPlaces = places.toList()

        clock = findViewById(R.id.clock)
        date = findViewById(R.id.date)
        statusWifi = findViewById(R.id.status_wifi)
        weatherIcon = findViewById(R.id.weather_icon)
        weatherTemp = findViewById(R.id.weather_temp)
        weather = Weather { temp, icon ->
            weatherTemp.text = "$temp°"
            weatherIcon.setImageResource(icon)
        }

        if (osmandHomeWanted()) {
            overlay = HomeOverlay(this).also { it.attach() }
            Foreground.listener = { pkg, cls -> onForeground(pkg, cls) }
            // "Mapa" en el dock: OsmAnd completo.
            findViewById<View>(R.id.dock_maps).setOnClickListener { overlay?.setFull(true) }
        }
    }

    // ---------- Mapa ----------

    private fun setupMap() {
        val mapPanel = findViewById<View>(R.id.map_panel)
        mapPanel.clipToOutline = true
        navMap = NavMap(this, mapPanel)
        navBanner = findViewById(R.id.nav_banner)
        navIcon = findViewById(R.id.nav_icon)
        navTitle = findViewById(R.id.nav_title)
        navText = findViewById(R.id.nav_text)
        mapSearch = findViewById(R.id.map_search)
        speedText = findViewById(R.id.speed)
        speedUnit = findViewById(R.id.speed_unit)
        speedBadge = findViewById(R.id.speed_badge)
        speedLimitView = findViewById(R.id.speed_limit)
        speedGroup = findViewById(R.id.speed_group)
        mapButtons = findViewById(R.id.map_buttons)
        pickCross = findViewById(R.id.pick_cross)
        pickBar = findViewById(R.id.pick_bar)

        navBanner.setOnClickListener { openMaps() }
        mapSearch.setOnClickListener { openMaps() }
        findViewById<View>(R.id.btn_open_maps).setOnClickListener { setFullMap(!fullMap) }
        findViewById<View>(R.id.map_status).setOnClickListener {
            if (!navMap.hasPermission()) requestLocation()
        }
        findViewById<View>(R.id.pick_cancel).setOnClickListener { endPick() }
        findViewById<View>(R.id.pick_save).setOnClickListener { savePick() }

        speedLimit = SpeedLimit(this) { limit, street ->
            currentLimit = limit
            navMap.street = street
            renderSpeed(navMap.lastFix)
        }
        cameraAlert = findViewById(R.id.camera_alert)
        cameraAlertTitle = findViewById(R.id.camera_alert_title)
        cameraAlertDetail = findViewById(R.id.camera_alert_detail)
        btnAlerts = findViewById(R.id.btn_alerts)
        cameras = Cameras(this, findViewById<MapView>(R.id.map), { currentLimit }) { camera, meters ->
            renderCameraAlert(camera, meters)
        }
        renderAlertsButton()
        btnAlerts.setOnClickListener {
            cameras.soundOn = !cameras.soundOn
            renderAlertsButton()
            Toast.makeText(this, if (cameras.soundOn) R.string.alerts_on else R.string.alerts_off, Toast.LENGTH_SHORT).show()
        }

        navMap.onLocation = { loc ->
            renderSpeed(loc)
            cameras.update(loc, online)
            speedLimit.update(loc, online)
            weather.update(loc, online)
            renderPlaceDistances()
            renderCardLive(loc)
            checkClock(loc)
        }

        if (!navMap.hasPermission()) requestLocation()
    }

    private fun openMaps() = Dock.open(this, Dock.MAPS, getString(R.string.label_maps))

    /**
     * Si la fecha del estéreo está muy corrida (por ejemplo, 2018), las conexiones seguras
     * fallan y no anda nada online. El GPS trae la hora real: se avisa una vez por sesión.
     */
    private var clockWarned = false

    private fun checkClock(loc: android.location.Location) {
        // La fecha del estéreo la pisa el GPS del fabricante (2007): no tiene sentido pedir que se corrija
        // a mano. El launcher usa la fecha real; si tiene permiso, además pide la hora a internet.
        if (clockWarned || !RealTime.systemClockWrong()) return
        clockWarned = true
        RealTime.tryNetworkResync(this)
    }

    /** Mapa propio en pantalla completa (sin columna ni dock). Google Maps se abre desde el dock. */
    private var fullMap = false

    private fun setFullMap(on: Boolean) {
        fullMap = on
        val gone = if (on) View.GONE else View.VISIBLE
        findViewById<View>(R.id.right_column).visibility = gone
        findViewById<View>(R.id.dock_bar).visibility = gone
        findViewById<android.widget.ImageButton>(R.id.btn_open_maps).apply {
            setImageResource(if (on) R.drawable.ic_close_full else R.drawable.ic_open_full)
            contentDescription = getString(if (on) R.string.map_exit_fullscreen else R.string.map_fullscreen)
        }
    }

    private fun renderAlertsButton() {
        btnAlerts.setImageResource(if (cameras.soundOn) R.drawable.ic_bell else R.drawable.ic_bell_off)
    }

    private fun renderCameraAlert(camera: Cameras.Camera?, meters: Int) {
        val show = camera != null && pickingIndex == null
        if (show != alertVisible) {
            alertVisible = show
            cameraAlert.visibility = if (show) View.VISIBLE else View.GONE
            renderNav()
        }
        if (camera == null) return
        val distance = "${(meters / 10) * 10} m"
        if (camera.kind == Cameras.Camera.RED_LIGHT) {
            cameraAlertTitle.text = getString(R.string.camera_red, distance)
            cameraAlertDetail.text = getString(R.string.camera_red_detail)
        } else {
            cameraAlertTitle.text = getString(R.string.camera_speed, distance)
            cameraAlertDetail.text = camera.limit?.let { getString(R.string.camera_speed_detail_limit, it) }
                ?: getString(R.string.camera_speed_detail)
        }
    }

    private fun renderSpeed(loc: Location?) {
        val fresh = loc != null && android.os.SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos < 10_000_000_000L && loc.hasSpeed()
        val kmh = if (fresh) (loc!!.speed * 3.6f).roundToInt() else null
        speedText.text = kmh?.toString() ?: "--"

        val limit = currentLimit
        speedLimitView.visibility = if (limit != null) View.VISIBLE else View.GONE
        speedLimitView.text = limit?.toString()

        // Excedido (con 3 km/h de margen por la precisión del GPS): todo en rojo.
        val over = limit != null && kmh != null && kmh > limit + 3
        speedBadge.setBackgroundResource(if (over) R.drawable.bg_speed_over else R.drawable.bg_speed_dark)
        val color = if (over) Color.parseColor("#F6AEA9") else getColor(R.color.text)
        speedText.setTextColor(color)
        speedUnit.setTextColor(if (over) color else getColor(R.color.muted))
    }

    private fun renderNav() {
        val info = Nav.current
        val picking = pickingIndex != null
        // Sin barra de búsqueda: el destino se elige en Organic Maps; el mapa gana espacio.
        mapSearch.visibility = View.GONE
        if (info == null || picking || overlay != null) {
            navBanner.visibility = View.GONE
            return
        }
        mapSearch.visibility = View.GONE
        navBanner.visibility = View.VISIBLE
        navTitle.text = info.title ?: info.text
        val detail = listOfNotNull(info.text.takeIf { info.title != null }, info.sub)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        navText.text = detail
        navText.visibility = if (detail.isEmpty()) View.GONE else View.VISIBLE
        if (info.icon != null) {
            navIcon.setImageBitmap(info.icon)
            if (info.tintIcon) navIcon.setColorFilter(Color.WHITE) else navIcon.clearColorFilter()
            navIcon.visibility = View.VISIBLE
        } else {
            navIcon.visibility = View.GONE
        }
    }

    private fun requestLocation() {
        requestPermissions(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            1
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        navMap.stop()
        navMap.start()
    }

    // ---------- Destinos rápidos ----------

    private fun renderBottomCard() {
        bottomCard = Prefs.bottomCard(this)
        cardMain = null
        cardSub = null
        cardNeedle = null
        if (bottomCard == Prefs.CARD_PLACES) {
            renderPlaces()
            return
        }
        placesView.removeAllViews()
        placeDetails.clear()
        val d = resources.displayMetrics.density
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER

        if (bottomCard == Prefs.CARD_COMPASS) {
            val needle = ImageView(this)
            needle.setImageResource(R.drawable.ic_compass_needle)
            row.addView(needle, LinearLayout.LayoutParams((52 * d).toInt(), (52 * d).toInt()))
            cardNeedle = needle
        }
        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        texts.gravity = Gravity.CENTER
        val main = TextView(this)
        main.setTextColor(getColor(R.color.text))
        main.textSize = if (bottomCard == Prefs.CARD_COMPASS) 22f else 30f
        main.includeFontPadding = false
        main.gravity = Gravity.CENTER
        val sub = TextView(this)
        sub.setTextColor(getColor(R.color.muted))
        sub.textSize = 10f
        sub.gravity = Gravity.CENTER
        texts.addView(main)
        texts.addView(sub)
        val textParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        if (cardNeedle != null) textParams.marginStart = (14 * d).toInt()
        row.addView(texts, textParams)
        placesView.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        cardMain = main
        cardSub = sub
        if (bottomCard == Prefs.CARD_CLOCK) updateClock() else renderCardLive(navMap.lastFix)
    }

    private fun renderCardLive(loc: Location?) {
        val main = cardMain ?: return
        val sub = cardSub ?: return
        when (bottomCard) {
            Prefs.CARD_SPEEDO -> {
                val fresh = loc != null && android.os.SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos < 10_000_000_000L && loc.hasSpeed()
                main.text = if (fresh) "${(loc!!.speed * 3.6f).roundToInt()} km/h" else "-- km/h"
                sub.text = currentLimit?.let { getString(R.string.speedo_limit, it) } ?: ""
            }
            Prefs.CARD_COMPASS -> {
                val bearing = loc?.takeIf { it.hasBearing() && it.hasSpeed() && it.speed > 1.5f }?.bearing
                if (bearing == null) {
                    main.text = "—"
                    sub.text = ""
                    return
                }
                val names = arrayOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")
                main.text = names[((bearing + 22.5f) / 45f).toInt() % 8]
                sub.text = "${bearing.roundToInt()}°"
                cardNeedle?.rotation = -bearing
            }
        }
    }

    private fun checkAutoTheme() {
        if (Prefs.theme(this) != Prefs.THEME_AUTO) return
        // De noche siempre oscuro; de día, oscuro si prendés las luces (túnel, lluvia).
        val night = ts.isNight() == true || Theme.sunIsDown(navMap.lastFix)
        if (night != Prefs.autoNight(this)) {
            Prefs.setAutoNight(this, night)
            recreate()
        }
    }

    /** 3 destinos a la vista y el 4.º lugar es "Más" (o "Agregar" si no hay más). */
    private fun renderPlaces() {
        placesView.removeAllViews()
        placeDetails.clear()
        val gap = (6 * resources.displayMetrics.density).toInt()
        val extra = Places.more(places)
        for (row in 0 until 2) {
            val rowView = LinearLayout(this)
            rowView.orientation = LinearLayout.HORIZONTAL
            val rowParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            if (row == 1) rowParams.topMargin = gap
            placesView.addView(rowView, rowParams)

            for (col in 0 until 2) {
                val index = row * 2 + col
                val tile = LayoutInflater.from(this).inflate(R.layout.item_place, rowView, false)
                val params = tile.layoutParams as LinearLayout.LayoutParams
                if (col == 1) params.marginStart = gap
                val name = tile.findViewById<TextView>(R.id.place_name)
                val icon = tile.findViewById<ImageView>(R.id.place_icon)
                val detail = tile.findViewById<TextView>(R.id.place_detail)

                val place = if (index < Places.VISIBLE) places.getOrNull(index) else null
                when {
                    place != null -> {
                        name.text = place.name
                        icon.setImageResource(place.iconRes)
                        detail.tag = place
                        placeDetails.add(detail)
                        tile.setOnClickListener { navigateTo(place) }
                        tile.setOnLongClickListener {
                            placeOptions(index)
                            true
                        }
                    }
                    index == 3 && extra.isNotEmpty() -> {
                        // "Más": abre la lista con el resto, del ancho de la tarjeta.
                        name.text = getString(R.string.places_more)
                        icon.setImageResource(R.drawable.ic_chevron_down)
                        detail.text = getString(R.string.places_more_count, extra.size)
                        tile.setOnClickListener { showMorePlaces() }
                    }
                    index == places.size || (index == 3 && extra.isEmpty()) -> {
                        tile.setBackgroundResource(R.drawable.bg_tile_add)
                        (tile as LinearLayout).gravity = Gravity.CENTER
                        name.text = getString(R.string.add_place)
                        name.setTextColor(getColor(R.color.muted))
                        name.textAlignment = View.TEXT_ALIGNMENT_CENTER
                        icon.setImageResource(R.drawable.ic_plus)
                        detail.visibility = View.GONE
                        tile.setOnClickListener { addPlace() }
                    }
                    else -> tile.visibility = View.INVISIBLE
                }
                rowView.addView(tile, params)
            }
        }
        renderPlaceDistances()
    }

    private fun distanceText(place: Place): String {
        val here = navMap.lastFix ?: return "—"
        val result = FloatArray(1)
        Location.distanceBetween(here.latitude, here.longitude, place.lat, place.lon, result)
        val meters = result[0]
        return if (meters < 1000) "${(meters / 10).roundToInt() * 10} m" else String.format(locale, "%.1f km", meters / 1000)
    }

    /** Lista desplegable con los otros destinos (orden alfabético) y "Agregar". */
    private fun showMorePlaces() {
        val density = resources.displayMetrics.density
        val menu = LinearLayout(this)
        menu.orientation = LinearLayout.VERTICAL
        menu.setBackgroundResource(R.drawable.bg_menu)
        val pad = (6 * density).toInt()
        menu.setPadding(pad, pad, pad, pad)
        val scroll = android.widget.ScrollView(this)
        scroll.addView(menu)
        val popup = PopupWindow(scroll, placesView.width, ViewGroup.LayoutParams.WRAP_CONTENT, true)

        for (place in Places.more(places)) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setBackgroundResource(R.drawable.bg_menu_item)
            row.setPadding((10 * density).toInt(), 0, (10 * density).toInt(), 0)
            val name = TextView(this)
            name.text = place.name
            name.setTextColor(getColor(R.color.text))
            name.textSize = 12f
            name.setTypeface(name.typeface, android.graphics.Typeface.BOLD)
            row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val dist = TextView(this)
            dist.text = distanceText(place)
            dist.setTextColor(getColor(R.color.muted))
            dist.textSize = 10f
            row.addView(dist)
            row.setOnClickListener {
                popup.dismiss()
                navigateTo(place)
            }
            row.setOnLongClickListener {
                popup.dismiss()
                placeOptions(places.indexOf(place))
                true
            }
            menu.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (40 * density).toInt()))
        }
        val add = TextView(this)
        add.text = "+  " + getString(R.string.add_place)
        add.setTextColor(getColor(R.color.muted))
        add.textSize = 11f
        add.gravity = Gravity.CENTER_VERTICAL
        add.setPadding((10 * density).toInt(), 0, 0, 0)
        add.setBackgroundResource(R.drawable.bg_menu_item)
        add.setOnClickListener {
            popup.dismiss()
            addPlace()
        }
        menu.addView(add, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (40 * density).toInt()))

        popup.elevation = 12 * density
        // Se abre hacia arriba desde la tarjeta, que está abajo de todo.
        val max = (resources.displayMetrics.heightPixels * 0.7f).toInt()
        scroll.measure(
            View.MeasureSpec.makeMeasureSpec(placesView.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(max, View.MeasureSpec.AT_MOST)
        )
        popup.height = scroll.measuredHeight
        popup.showAsDropDown(placesView, 0, -placesView.height - scroll.measuredHeight - pad)
    }

    /**
     * Agregar destino: con OsmAnd, por dirección (la busca en sus mapas, sin internet);
     * si no, marcándolo en el mapa del launcher.
     */
    private fun addPlace() {
        if (NavLaunch.osmand(this) == null) {
            startPick(-1)
            return
        }
        askName("") { name ->
            askName("", R.string.place_address_title, R.string.place_address_hint) { address ->
                val here = navMap.lastFix
                OsmAnd.search(this, address, here?.latitude ?: -34.6037, here?.longitude ?: -58.3816) { results ->
                    val r = results.firstOrNull()
                    if (r == null) {
                        Toast.makeText(this, getString(R.string.place_not_found, address), Toast.LENGTH_LONG).show()
                    } else {
                        places.add(Place(name, r.latitude, r.longitude))
                        savePlaces()
                        Toast.makeText(this, getString(R.string.place_found, name, r.localName ?: address), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun renderPlaceDistances() {
        val here = navMap.lastFix
        for (detail in placeDetails) {
            val place = detail.tag as? Place ?: continue
            if (here == null) {
                detail.text = "—"
                continue
            }
            val result = FloatArray(1)
            Location.distanceBetween(here.latitude, here.longitude, place.lat, place.lon, result)
            val meters = result[0]
            detail.text = if (meters < 1000) "${(meters / 10).roundToInt() * 10} m"
            else String.format(locale, "%.1f km", meters / 1000)
        }
    }

    private fun navigateTo(place: Place) {
        // Inicio con OsmAnd: navega ahí mismo, sin cambiar de pantalla.
        if (overlay != null && OsmAnd.navigate(this, place)) {
            bringOsmAnd()
            return
        }
        // Con OsmAnd: navega directo desde tu posición (sin confirmar) y el launcher vuelve al inicio.
        val osmand = NavLaunch.osmand(this)
        if (osmand != null) {
            val uri = "osmand.api://navigate?dest_lat=${place.lat}&dest_lon=${place.lon}" +
                "&dest_name=${Uri.encode(place.name)}&profile=car&force=true"
            try {
                NavLaunch.returnHome = true
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(osmand).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {
                NavLaunch.returnHome = false
            }
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${place.lat},${place.lon}"))
            .setPackage("com.google.android.apps.maps")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.place_no_maps, Toast.LENGTH_SHORT).show()
        }
    }

    private fun placeOptions(index: Int) {
        val place = places[index]
        val options = arrayOf(
            getString(R.string.place_rename),
            getString(R.string.place_move),
            getString(R.string.place_delete)
        )
        AlertDialog.Builder(this, dialogTheme)
            .setTitle(place.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> askName(place.name) { name ->
                        places[index] = place.copy(name = name)
                        savePlaces()
                    }
                    1 -> if (NavLaunch.osmand(this) != null) {
                        // Cambiar la dirección (se busca en los mapas de OsmAnd).
                        askName("", R.string.place_address_title, R.string.place_address_hint) { address ->
                            val here = navMap.lastFix
                            OsmAnd.search(this, address, here?.latitude ?: -34.6037, here?.longitude ?: -58.3816) { results ->
                                val r = results.firstOrNull()
                                if (r == null) {
                                    Toast.makeText(this, getString(R.string.place_not_found, address), Toast.LENGTH_LONG).show()
                                } else {
                                    places[index] = place.copy(lat = r.latitude, lon = r.longitude)
                                    savePlaces()
                                }
                            }
                        }
                    } else startPick(index)
                    2 -> {
                        places.removeAt(index)
                        savePlaces()
                    }
                }
            }
            .showHere()
    }

    private fun startPick(index: Int) {
        pickingIndex = index
        navMap.setPicking(true)
        pickCross.visibility = View.VISIBLE
        pickBar.visibility = View.VISIBLE
        speedGroup.visibility = View.GONE
        mapButtons.visibility = View.GONE
        renderNav()
    }

    private fun endPick() {
        pickingIndex = null
        navMap.setPicking(false)
        pickCross.visibility = View.GONE
        pickBar.visibility = View.GONE
        speedGroup.visibility = View.VISIBLE
        mapButtons.visibility = View.VISIBLE
        renderNav()
    }

    private fun savePick() {
        val index = pickingIndex ?: return
        val point = navMap.center
        endPick()
        if (index >= 0) {
            places[index] = places[index].copy(lat = point.latitude, lon = point.longitude)
            savePlaces()
        } else {
            askName("") { name ->
                places.add(Place(name, point.latitude, point.longitude))
                savePlaces()
            }
        }
    }

    private fun askName(
        current: String,
        title: Int = R.string.place_name_title,
        hint: Int = R.string.place_name_hint,
        onName: (String) -> Unit
    ) {
        val input = EditText(this)
        input.setText(current)
        input.hint = getString(hint)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        input.setSingleLine()
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this)
        container.setPadding(pad, pad / 2, pad, 0)
        container.addView(input)
        AlertDialog.Builder(this, dialogTheme)
            .setTitle(title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) onName(name)
            }
            .setNegativeButton(R.string.cancel, null)
            .showHere()
    }

    private var savedPlaces: List<Place> = emptyList()

    private fun savePlaces() {
        Places.save(this, places)
        // También quedan como favoritos de OsmAnd (grupo "Launcher"), marcados en su mapa.
        OsmAnd.syncFavorites(this, savedPlaces, places.toList())
        savedPlaces = places.toList()
        renderPlaces()
    }

    // ---------- Música ----------

    private fun setupMusic() {
        sourceButton = findViewById(R.id.source_button)
        sourceIcon = findViewById(R.id.source_icon)
        musicSource = findViewById(R.id.music_source)
        playButton = findViewById(R.id.btn_play)
        trackTitle = findViewById(R.id.track_title)
        trackArtist = findViewById(R.id.track_artist)
        trackAlbum = findViewById(R.id.track_album)
        cover = findViewById(R.id.cover)
        coverPlaceholder = findViewById(R.id.cover_placeholder)
        progressGroup = findViewById(R.id.progress_group)
        progress = findViewById(R.id.progress)
        timeNow = findViewById(R.id.time_now)
        timeTotal = findViewById(R.id.time_total)

        source = Sources.selected(this)
        music = Music(this) { runOnUiThread { renderMusic() } }
        music.preferredPackage = source.sessionPackage

        sourceButton.setOnClickListener { showSourceMenu() }
        findViewById<View>(R.id.track_row).setOnClickListener { openSource() }
        // Mantener apretada: en radio, ponerle nombre a la emisora; si no, diagnóstico de sesiones.
        findViewById<View>(R.id.track_row).setOnLongClickListener {
            val freq = radioFreq
            if (source.id == Sources.RADIO && freq != null) {
                askName(stationName(freq) ?: "", R.string.station_name_title, R.string.station_name_hint) { name ->
                    getSharedPreferences("stations", Context.MODE_PRIVATE).edit().putString(freq, name).apply()
                    renderMusic()
                }
            } else {
                Toast.makeText(this, music.describeSessions(), Toast.LENGTH_LONG).show()
            }
            true
        }

        findViewById<View>(R.id.btn_prev).setOnClickListener { skip(next = false) }
        findViewById<View>(R.id.btn_next).setOnClickListener { skip(next = true) }
        playButton.setOnClickListener {
            if (source.sessionPackage == null) {
                tsPlayPause()
                return@setOnClickListener
            }
            if (!music.hasAccess()) {
                requestAccess()
                return@setOnClickListener
            }
            when (music.playPause()) {
                Music.PlayResult.DONE -> {}
                Music.PlayResult.NEEDS_SOURCE -> startSource()
                // Si el estéreo no tenía la fuente BT activa, el celular arranca y se
                // vuelve a pausar: en ese caso, activarla.
                Music.PlayResult.VERIFY -> handler.postDelayed({
                    if (!music.isPlaying && source.isBluetooth) startSource()
                }, 1500)
            }
        }

        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        volume = findViewById(R.id.volume)
        volume.max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        volume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })
        findViewById<View>(R.id.vol_down).setOnClickListener { stepVolume(-1) }
        findViewById<View>(R.id.vol_up).setOnClickListener { stepVolume(1) }
    }

    /**
     * Pone a sonar la fuente elegida.
     * - Bluetooth, USB y radio: se le pide al estéreo que cambie de fuente (y Play);
     *   si a los 2 segundos no cambió, se abre su pantalla un instante y se vuelve.
     * - Apps de música: se abren.
     */
    private fun startSource() {
        val mode = source.tsMode
        if (mode == null) {
            openSource()
            return
        }
        if (ts.connected) {
            ts.enterMode(mode)
            when {
                source.isBluetooth -> music.play()
                source.id == Sources.USB -> ts.sendKey(TsCommon.KEY_PLAY)
            }
            handler.postDelayed({ if (!sourceActive()) flashOpen() }, 1800)
        } else {
            flashOpen()
        }
    }

    private fun sourceActive(): Boolean = when {
        source.isBluetooth -> music.isPlaying
        source.tsMode != null -> ts.workMode() == source.tsMode
        else -> true
    }

    private fun skip(next: Boolean) {
        if (source.sessionPackage == null) {
            // USB y radio: las mismas teclas que el volante (siguiente/anterior).
            if (tsMode == source.tsMode) ts.sendKey(if (next) TsCommon.KEY_NEXT else TsCommon.KEY_PREV)
            else startSource()
            return
        }
        if (!music.hasAccess()) {
            requestAccess()
            return
        }
        val done = if (next) music.next() else music.previous()
        if (!done) startSource()
    }

    private fun tsPlayPause() {
        if (tsMode != source.tsMode) {
            startSource()
            return
        }
        // La radio no tiene pausa: el botón solo indica que está al aire.
        if (source.id == Sources.USB) ts.sendKey(if (tsPlaying) TsCommon.KEY_PAUSE else TsCommon.KEY_PLAY)
    }

    private fun pollTs() {
        if (!ts.connected) return
        val mode = ts.workMode()
        val changed = mode != tsMode
        tsMode = mode
        when (source.id) {
            Sources.USB -> {
                if (mode == TsCommon.MODE_USB) {
                    tsTitle = ts.songName()?.substringAfterLast('/')?.substringBeforeLast('.')
                    tsPlaying = ts.playState() == 1
                    tsCur = ts.currentTime() ?: 0
                    tsTotal = ts.totalTime() ?: 0
                } else {
                    tsPlaying = false
                }
                renderMusic()
            }
            Sources.RADIO -> {
                if (mode == TsCommon.MODE_RADIO) {
                    radioFreq = ts.freq()
                    radioBand = ts.band()
                }
                renderMusic()
            }
            else -> if (changed) renderMusic()
        }
    }

    private fun stationName(freq: String): String? =
        getSharedPreferences("stations", Context.MODE_PRIVATE).getString(freq, null)

    private fun openSource() {
        val intent = Sources.openIntent(this, source) ?: return
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun selectSource(newSource: Source) {
        source = newSource
        Sources.select(this, newSource.id)
        music.preferredPackage = newSource.sessionPackage
        renderMusic()
        startSource()
    }

    private fun showSourceMenu() {
        val density = resources.displayMetrics.density
        val menu = LinearLayout(this)
        menu.orientation = LinearLayout.VERTICAL
        menu.setBackgroundResource(R.drawable.bg_menu)
        val pad = (5 * density).toInt()
        menu.setPadding(pad, pad, pad, pad)
        menu.elevation = 12 * density

        val popup = PopupWindow(menu, sourceButton.width + (16 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.elevation = 12 * density

        val title = TextView(this)
        title.text = getString(R.string.source_title)
        title.setTextColor(getColor(R.color.muted))
        title.textSize = 9f
        title.setPadding((8 * density).toInt(), (6 * density).toInt(), 0, (6 * density).toInt())
        menu.addView(title)

        for (item in Sources.all(this)) {
            val row = LayoutInflater.from(this).inflate(R.layout.item_source, menu, false)
            bindSourceIcon(row.findViewById(R.id.source_item_icon), item)
            row.findViewById<TextView>(R.id.source_item_name).text = item.name
            val detailView = row.findViewById<TextView>(R.id.source_item_detail)
            detailView.text = item.detail
            detailView.visibility = if (item.detail.isEmpty()) View.GONE else View.VISIBLE
            val selected = item.id == source.id
            row.isSelected = selected
            row.findViewById<View>(R.id.source_item_check).visibility = if (selected) View.VISIBLE else View.INVISIBLE
            row.setOnClickListener {
                popup.dismiss()
                selectSource(item)
            }
            val pkg = item.appPackage
            if (pkg != null) {
                row.setOnLongClickListener {
                    popup.dismiss()
                    AlertDialog.Builder(this, dialogTheme)
                        .setMessage(getString(R.string.source_remove, item.name))
                        .setPositiveButton(R.string.remove) { _, _ ->
                            Sources.removeApp(this, pkg)
                            source = Sources.selected(this)
                            music.preferredPackage = source.sessionPackage
                            renderMusic()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .showHere()
                    true
                }
            }
            menu.addView(row)
        }

        val add = TextView(this)
        add.text = "+  " + getString(R.string.source_add)
        add.setTextColor(getColor(R.color.muted))
        add.textSize = 10f
        add.gravity = Gravity.CENTER_VERTICAL
        add.setBackgroundResource(R.drawable.bg_header)
        add.setPadding((10 * density).toInt(), 0, 0, 0)
        add.setOnClickListener {
            popup.dismiss()
            startActivity(
                Intent(this, AppsActivity::class.java)
                    .putExtra(AppsActivity.EXTRA_PICK_KEY, AppsActivity.PICK_MUSIC_APP)
                    .putExtra(AppsActivity.EXTRA_PICK_LABEL, getString(R.string.label_music))
            )
        }
        menu.addView(add, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (34 * density).toInt()))

        popup.showAsDropDown(sourceButton, -(8 * density).toInt(), -sourceButton.height)
    }

    private fun bindSourceIcon(view: ImageView, item: Source) {
        if (item.iconRes != null) {
            val circle = GradientDrawable()
            circle.shape = GradientDrawable.OVAL
            circle.setColor(item.color)
            view.background = circle
            view.setImageResource(item.iconRes)
        } else {
            view.background = null
            view.setPadding(0, 0, 0, 0)
            view.setImageDrawable(Sources.appIcon(this, item))
        }
    }

    /**
     * Abre la pantalla de la fuente en el estéreo un instante y vuelve al launcher.
     * Al abrirse, esa pantalla cambia el audio a su fuente y da Play
     * (por ejemplo, com.ts.bt.BtMusicActivity.onResume).
     */
    private fun flashOpen() {
        val intent = Sources.openIntent(this, source) ?: return
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.open_failed, Toast.LENGTH_SHORT).show()
            return
        }
        handler.postDelayed({
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION
                    )
            )
        }, 1200)
    }

    private fun requestAccess() {
        Toast.makeText(this, R.string.grant_access, Toast.LENGTH_LONG).show()
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    /** "Bluetooth · <nombre del celular conectado>", o "Bluetooth" si no se puede saber. */
    private fun btLabel(): String {
        val name = try {
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            adapter?.bondedDevices?.firstOrNull { d ->
                try {
                    d.javaClass.getMethod("isConnected").invoke(d) as Boolean
                } catch (e: Exception) {
                    false
                }
            }?.name
        } catch (e: Exception) {
            null
        }
        return if (name.isNullOrBlank()) "Bluetooth" else "Bluetooth · $name"
    }

    private fun renderMusic() {
        bindSourceIcon(sourceIcon, source)
        sourceIcon.setPadding(
            if (source.iconRes != null) (3 * resources.displayMetrics.density).toInt() else 0,
            if (source.iconRes != null) (3 * resources.displayMetrics.density).toInt() else 0,
            if (source.iconRes != null) (3 * resources.displayMetrics.density).toInt() else 0,
            if (source.iconRes != null) (3 * resources.displayMetrics.density).toInt() else 0
        )
        musicSource.text = if (source.isBluetooth) btLabel() else source.name
        trackAlbum.visibility = View.GONE

        if (source.sessionPackage == null) {
            renderTsMusic()
            return
        }

        // Sin portada desde el celular (Bluetooth): se busca por artista y canción.
        val art = music.art ?: Covers.cached(this, music.artist, music.title).also {
            if (it == null && music.hasSession) Covers.lookup(this, music.artist, music.title, online) { renderMusic() }
        }
        cover.visibility = if (art != null) View.VISIBLE else View.GONE
        coverPlaceholder.visibility = if (art != null) View.GONE else View.VISIBLE
        if (art != null) cover.setImageBitmap(art)

        when {
            !music.hasAccess() -> {
                trackTitle.text = getString(R.string.music_no_access_title)
                trackArtist.text = getString(R.string.music_no_access)
            }
            music.hasSession -> {
                trackTitle.text = music.title?.takeIf { it.isNotBlank() } ?: source.name
                trackArtist.text = music.artist?.takeIf { it.isNotBlank() } ?: music.stateName
                val album = music.album?.takeIf { it.isNotBlank() && it != music.title }
                trackAlbum.text = album
                trackAlbum.visibility = if (album != null) View.VISIBLE else View.GONE
            }
            source.sessionPackage == null -> {
                trackTitle.text = source.name
                trackArtist.text = getString(R.string.source_open_hint)
            }
            else -> {
                trackTitle.text = getString(R.string.music_idle_title)
                trackArtist.text = getString(R.string.music_idle_hint)
            }
        }
        playButton.setImageResource(if (music.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)

        handler.removeCallbacks(progressTick)
        handler.post(progressTick)
    }

    private fun renderTsMusic() {
        cover.visibility = View.GONE
        coverPlaceholder.visibility = View.VISIBLE
        val active = tsMode == source.tsMode
        if (source.id == Sources.RADIO) {
            coverPlaceholder.setImageResource(R.drawable.ic_radio)
            val freq = radioFreq
            if (active && freq != null) {
                val band = radioBand ?: "FM"
                trackTitle.text = stationName(freq) ?: "$band $freq"
                trackArtist.text = if (stationName(freq) != null) "$band $freq" else getString(R.string.station_hint)
            } else {
                trackTitle.text = source.name
                trackArtist.text = getString(if (ts.connected) R.string.source_not_active else R.string.source_open_hint)
            }
            playButton.setImageResource(if (active) R.drawable.ic_radio_onair else R.drawable.ic_play)
            progressGroup.visibility = View.GONE
        } else {
            coverPlaceholder.setImageResource(R.drawable.ic_file)
            if (active) {
                trackTitle.text = tsTitle ?: source.name
                trackArtist.text = getString(R.string.usb_label)
            } else {
                trackTitle.text = source.name
                trackArtist.text = getString(if (ts.connected) R.string.source_not_active else R.string.source_open_hint)
            }
            playButton.setImageResource(if (active && tsPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            if (active && tsTotal > 0) {
                // El reproductor del estéreo puede informar segundos o milisegundos.
                val factor = if (tsTotal > 20_000) 1L else 1000L
                val total = tsTotal * factor
                val cur = (tsCur * factor).coerceIn(0, total)
                progressGroup.visibility = View.VISIBLE
                progress.progress = (cur * 1000 / total).toInt()
                timeNow.text = formatTime(cur)
                timeTotal.text = formatTime(total)
            } else {
                progressGroup.visibility = View.GONE
            }
        }
    }

    private fun renderProgress() {
        if (source.sessionPackage == null) return
        val duration = music.duration
        if (!music.hasSession || duration <= 0) {
            progressGroup.visibility = View.GONE
            return
        }
        progressGroup.visibility = View.VISIBLE
        val pos = music.position()
        progress.progress = (pos * 1000 / duration).toInt()
        timeNow.text = formatTime(pos)
        timeTotal.text = formatTime(duration)
    }

    private fun formatTime(ms: Long): String {
        val total = ms / 1000
        return String.format(locale, "%d:%02d", total / 60, total % 60)
    }

    /** Un paso de volumen. Igual que el deslizable (adjustStreamVolume no siempre anda en el estéreo). */
    private fun stepVolume(delta: Int) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val next = (audio.getStreamVolume(AudioManager.STREAM_MUSIC) + delta).coerceIn(0, max)
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
        } catch (e: Exception) {
        }
        volume.progress = next
    }

    private fun refreshVolume() {
        volume.progress = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    }

    // ---------- Dock, reloj y conexión ----------

    private fun setupDock() {
        findViewById<View>(R.id.dock_apps).setOnClickListener {
            startActivity(Intent(this, AppsActivity::class.java))
        }
        bindDock(R.id.dock_radio, Dock.RADIO, getString(R.string.label_radio))
        bindDock(R.id.dock_phone, Dock.PHONE, getString(R.string.label_phone))
        bindDock(R.id.dock_maps, Dock.MAPS, getString(R.string.label_maps))
        findViewById<View>(R.id.dock_music).setOnClickListener { openSource() }
        findViewById<View>(R.id.dock_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun bindDock(viewId: Int, key: String, label: String) {
        val view = findViewById<View>(viewId)
        view.setOnClickListener { Dock.open(this, key, label) }
        // Mantener apretado: elegir otra app para ese botón.
        view.setOnLongClickListener {
            Dock.pick(this, key, label)
            true
        }
    }

    private fun updateClock() {
        // Fecha real aunque el estéreo diga 2007 (ver RealTime).
        val now = RealTime.date()
        clock.text = timeFormat.format(now)
        date.text = dateFormat.format(now)
        if (bottomCard == Prefs.CARD_CLOCK) {
            cardMain?.text = timeFormat.format(now)
            cardSub?.text = dateFormat.format(now)
        }
    }

    private fun setOnline(value: Boolean) {
        online = value
        statusWifi.setImageResource(if (value) R.drawable.ic_wifi else R.drawable.ic_wifi_off)
        navMap.offline = !value
        if (!value) {
            weatherTemp.text = "--°"
            weatherIcon.setImageResource(R.drawable.ic_cloud)
        } else {
            weather.update(navMap.lastFix, true)
        }
    }

    private fun watchNetwork() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        setOnline(caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val ok = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                runOnUiThread { setOnline(ok) }
            }

            override fun onLost(network: Network) {
                runOnUiThread { setOnline(false) }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: Exception) {
        }
    }

    private fun unwatchNetwork() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback?.let {
            try {
                cm.unregisterNetworkCallback(it)
            } catch (e: Exception) {
            }
        }
        networkCallback = null
    }

    override fun onResume() {
        super.onResume()
        // El tema pudo cambiar en Ajustes.
        if (Theme.isNight(this) != startedNight) {
            recreate()
            return
        }
        if (overlay != null && resumedFully) {
            places = Places.load(this)
            savedPlaces = places.toList()
            renderBottomCard()
            bringOsmAnd()
            return
        }
        resumedFully = true
        // Los destinos pudieron cambiar desde Ajustes (destinos.txt).
        places = Places.load(this)
        savedPlaces = places.toList()
        renderBottomCard()
        handler.removeCallbacks(themeTick)
        handler.postDelayed(themeTick, 3000)
        handler.removeCallbacks(tick)
        handler.post(tick)
        refreshVolume()
        registerReceiver(volumeReceiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION"))

        // La fuente pudo cambiar desde la lista de apps ("Agregar otra app de música").
        source = Sources.selected(this)
        music.preferredPackage = source.sessionPackage
        music.start()
        handler.removeCallbacks(tsTick)
        handler.post(tsTick)

        // Si se cargaron las calles sin conexión, el mapa cambia (nombres propios): se rearma.
        if (startedWithRoads != Roads.available(this)) {
            recreate()
            return
        }
        watchNetwork()
        findViewById<TextView>(R.id.where_to_hint).text = getString(R.string.where_to_hint_app, Dock.navigatorName(this))
        cameras.reloadIfCleared()
        navMap.start()
        Nav.listener = { runOnUiThread { renderNav() } }
        renderNav()
        renderPlaceDistances()
        if (overlay != null) bringOsmAnd()
    }

    override fun onPause() {
        // Con OsmAnd de fondo, el launcher sigue andando (música, reloj, GPS) aunque no esté adelante.
        if (overlay != null) {
            super.onPause()
            return
        }
        if (!resumedFully) {
            super.onPause()
            return
        }
        resumedFully = false
        handler.removeCallbacks(tick)
        handler.removeCallbacks(progressTick)
        handler.removeCallbacks(tsTick)
        handler.removeCallbacks(themeTick)
        unregisterReceiver(volumeReceiver)
        music.stop()
        unwatchNetwork()
        navMap.stop()
        Nav.listener = null
        super.onPause()
    }

    override fun onDestroy() {
        overlay?.let {
            it.detach()
            Foreground.listener = null
            if (resumedFully) {
                handler.removeCallbacksAndMessages(null)
                try {
                    unregisterReceiver(volumeReceiver)
                } catch (e: Exception) {
                }
                music.stop()
                unwatchNetwork()
                navMap.stop()
                Nav.listener = null
            }
        }
        overlay = null
        ts.disconnect()
        cameras.release()
        super.onDestroy()
    }

    // Es la pantalla de inicio: "atrás" cancela la elección de lugar y nada más.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (pickingIndex != null) endPick()
        else if (fullMap) setFullMap(false)
    }
}
