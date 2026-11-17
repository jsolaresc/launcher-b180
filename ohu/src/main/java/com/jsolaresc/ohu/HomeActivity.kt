package com.jsolaresc.ohu

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Inicio mínimo para usar el estéreo con Open Headunit como pantalla principal.
 * Al encender el estéreo abre Open Headunit solo, con una cuenta regresiva que se puede
 * cancelar; apretando Inicio se vuelve acá, con acceso a las apps y a los ajustes.
 */
class HomeActivity : Activity() {

    companion object {
        const val OHU = "com.andrerinas.headunitrevived"
        const val OHU_APK = "https://github.com/andreknieriem/open-headunit/releases/download/v.4.0.0/com.andrerinas.headunitrevived_4.0.0.apk"
        const val B180 = "com.jsolaresc.launcher"
        val STEREO_SETTINGS = ComponentName("com.ts.MainUI", "com.ts.set.SetMainActivity")

        const val KEY_PENDING = "pending_auto_open"
        const val KEY_ON_BOOT = "open_on_boot"
        const val KEY_ON_HOME = "open_on_home"
        private const val COUNTDOWN = 4

        fun prefs(ctx: Context) = ctx.getSharedPreferences("ohu", Context.MODE_PRIVATE)

        /** Primera vez que se abre desde que arrancó el proceso (por si el aviso de encendido llega tarde). */
        private var firstStart = true
    }

    private val bg = Color.parseColor("#0E1116")
    private val card = Color.parseColor("#1C222C")
    private val chip = Color.parseColor("#262D39")
    private val fgColor = Color.parseColor("#E8EAED")
    private val muted = Color.parseColor("#A8B0BC")
    private val accent = Color.parseColor("#8AB4F8")

    private val handler = Handler(Looper.getMainLooper())
    private var d = 1f
    private lateinit var root: FrameLayout
    private lateinit var clock: TextView
    private lateinit var status: TextView
    private lateinit var stay: TextView
    private lateinit var appsPanel: View
    private var remaining = 0
    private var pressedHome = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        d = resources.displayMetrics.density
        root = FrameLayout(this)
        root.setBackgroundColor(bg)
        root.addView(buildHome())
        appsPanel = buildApps()
        appsPanel.visibility = View.GONE
        root.addView(appsPanel)
        setContentView(root)

        val p = prefs(this)
        val bootWindow = firstStart && SystemClock.elapsedRealtime() < 5 * 60_000L
        firstStart = false
        if (p.getBoolean(KEY_ON_BOOT, true) && (p.getBoolean(KEY_PENDING, false) || bootWindow)) {
            p.edit().putBoolean(KEY_PENDING, false).apply()
            remaining = COUNTDOWN
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Apretaron Inicio: se cierra la lista de apps y, si está activado, se vuelve a Open Headunit.
        appsPanel.visibility = View.GONE
        pressedHome = true
    }

    override fun onResume() {
        super.onResume()
        tickClock()
        val p = prefs(this)
        if (p.getBoolean(KEY_PENDING, false) && p.getBoolean(KEY_ON_BOOT, true)) {
            p.edit().putBoolean(KEY_PENDING, false).apply()
            remaining = COUNTDOWN
        }
        if (pressedHome && p.getBoolean(KEY_ON_HOME, false)) remaining = COUNTDOWN
        pressedHome = false
        if (remaining > 0 && installed()) countdown() else renderStatus()
    }

    override fun onPause() {
        super.onPause()
        cancelCountdown()
        handler.removeCallbacks(clockTick)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Es la pantalla de inicio: Atrás solo cierra la lista de apps.
        if (appsPanel.visibility == View.VISIBLE) appsPanel.visibility = View.GONE
    }

    // ---------- Open Headunit ----------

    private fun installed() = packageManager.getLaunchIntentForPackage(OHU) != null

    private fun openOhu() {
        cancelCountdown()
        val i = packageManager.getLaunchIntentForPackage(OHU)
        if (i == null) {
            openUrl(OHU_APK)
            return
        }
        launch(i)
    }

    private val countdownTick = object : Runnable {
        override fun run() {
            if (remaining <= 0) {
                openOhu()
                return
            }
            status.text = "Abriendo Open Headunit en $remaining s…"
            stay.visibility = View.VISIBLE
            remaining--
            handler.postDelayed(this, 1000)
        }
    }

    private fun countdown() {
        handler.removeCallbacks(countdownTick)
        handler.post(countdownTick)
    }

    private fun cancelCountdown() {
        handler.removeCallbacks(countdownTick)
        remaining = 0
        if (::stay.isInitialized) {
            stay.visibility = View.GONE
            renderStatus()
        }
    }

    private fun renderStatus() {
        status.text = if (installed()) "Tocá para abrir Open Headunit"
        else "Open Headunit no está instalado · tocá para bajar la 4.0"
    }

    // ---------- Pantalla ----------

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius * d
    }

    private fun px(v: Int) = (v * d).toInt()

    private fun buildHome(): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(px(16), px(16), px(16), px(16))

        // Tarjeta grande: Android Auto.
        val big = LinearLayout(this)
        big.orientation = LinearLayout.VERTICAL
        big.gravity = Gravity.CENTER
        big.background = rounded(card, 24f)
        big.isClickable = true
        big.setOnClickListener { openOhu() }

        clock = TextView(this).apply {
            setTextColor(fgColor)
            textSize = 44f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        big.addView(clock)

        val icon = ImageView(this)
        icon.setImageResource(R.drawable.ic_launcher)
        big.addView(icon, LinearLayout.LayoutParams(px(96), px(96)).apply { topMargin = px(10) })

        val title = TextView(this).apply {
            text = "Android Auto"
            setTextColor(fgColor)
            textSize = 26f
            gravity = Gravity.CENTER
        }
        big.addView(title, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(10) })

        status = TextView(this).apply {
            setTextColor(muted)
            textSize = 15f
            gravity = Gravity.CENTER
        }
        big.addView(status, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(6) })

        stay = pill("Quedarme acá", chip, fgColor) { cancelCountdown() }
        stay.visibility = View.GONE
        big.addView(stay, LinearLayout.LayoutParams(-2, px(48)).apply { topMargin = px(16) })

        row.addView(big, LinearLayout.LayoutParams(0, -1, 1f))

        // Columna: salidas a todo lo demás.
        val side = LinearLayout(this)
        side.orientation = LinearLayout.VERTICAL
        side.setPadding(px(12), 0, 0, 0)
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add("Apps" to { cancelCountdown(); appsPanel.visibility = View.VISIBLE })
        items.add("Ajustes del estéreo" to { cancelCountdown(); launch(Intent().setComponent(STEREO_SETTINGS)) })
        items.add("Ajustes de Android" to { cancelCountdown(); launch(Intent(Settings.ACTION_SETTINGS)) })
        items.add("Wi-Fi" to { cancelCountdown(); launch(Intent(Settings.ACTION_WIFI_SETTINGS)) })
        if (packageManager.getLaunchIntentForPackage(B180) != null) {
            items.add("Launcher B180" to {
                cancelCountdown()
                launch(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(ComponentName(B180, "$B180.MainActivity")))
            })
        }
        items.add("Elegir pantalla de inicio" to { cancelCountdown(); launch(Intent(Settings.ACTION_HOME_SETTINGS)) })
        for ((label, action) in items) {
            side.addView(pill(label, card, fgColor) { action() }, LinearLayout.LayoutParams(-1, px(40)).apply { bottomMargin = px(6) })
        }
        side.addView(toggle("Abrir al encender", KEY_ON_BOOT, true))
        side.addView(toggle("Abrir también al apretar Inicio", KEY_ON_HOME, false))
        // Con scroll por si no entra todo en la pantalla del estéreo (1024×600 a 240 dpi).
        val scroll = android.widget.ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(side)
        row.addView(scroll, LinearLayout.LayoutParams(px(260), -1))
        return row
    }

    private fun pill(label: String, color: Int, fg: Int, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(fg)
        textSize = 15f
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(18), 0, px(18), 0)
        background = rounded(color, 16f)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun toggle(label: String, key: String, def: Boolean): View {
        val sw = Switch(this)
        sw.text = label
        sw.setTextColor(muted)
        sw.textSize = 14f
        sw.isChecked = prefs(this).getBoolean(key, def)
        sw.setPadding(px(6), px(6), px(6), px(6))
        sw.setOnCheckedChangeListener { _, on -> prefs(this).edit().putBoolean(key, on).apply() }
        return sw
    }

    private val clockTick = object : Runnable {
        override fun run() {
            tickClock()
        }
    }

    private fun tickClock() {
        // Solo la hora: la fecha del estéreo puede venir mal (error del GPS de fábrica).
        clock.text = SimpleDateFormat("HH:mm", Locale("es", "AR")).format(Date())
        handler.removeCallbacks(clockTick)
        handler.postDelayed(clockTick, 60_000L - System.currentTimeMillis() % 60_000L)
    }

    // ---------- Apps ----------

    private fun buildApps(): View {
        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setBackgroundColor(bg)
        panel.setPadding(px(16), px(12), px(16), px(12))
        panel.isClickable = true

        val bar = LinearLayout(this)
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.addView(pill("‹  Volver", chip, fgColor) { panel.visibility = View.GONE }, LinearLayout.LayoutParams(-2, px(48)))
        bar.addView(TextView(this).apply {
            text = "Apps"
            setTextColor(fgColor)
            textSize = 22f
            setPadding(px(16), 0, 0, 0)
        })
        panel.addView(bar)

        val apps = loadApps()
        val grid = GridView(this)
        grid.numColumns = 7
        grid.verticalSpacing = px(8)
        grid.adapter = object : BaseAdapter() {
            override fun getCount() = apps.size
            override fun getItem(position: Int) = apps[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val app = apps[position]
                val cell = (convertView as? LinearLayout) ?: LinearLayout(this@HomeActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(0, px(8), 0, px(8))
                    addView(ImageView(this@HomeActivity), LinearLayout.LayoutParams(px(52), px(52)))
                    addView(TextView(this@HomeActivity).apply {
                        setTextColor(fgColor)
                        textSize = 12f
                        maxLines = 1
                        gravity = Gravity.CENTER
                    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(4) })
                }
                (cell.getChildAt(0) as ImageView).setImageDrawable(app.loadIcon(packageManager))
                (cell.getChildAt(1) as TextView).text = app.loadLabel(packageManager)
                return cell
            }
        }
        grid.setOnItemClickListener { _, _, position, _ ->
            val a = apps[position].activityInfo
            launch(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(ComponentName(a.packageName, a.name)))
        }
        panel.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = px(10) })
        return panel
    }

    private fun loadApps(): List<ResolveInfo> {
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(main, 0)
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
    }

    // ---------- Utilidades ----------

    private fun launch(intent: Intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openUrl(url: String) = launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}
