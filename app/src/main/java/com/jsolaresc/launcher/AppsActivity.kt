package com.jsolaresc.launcher

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast

/**
 * Lista de apps instaladas. Funciona en dos modos:
 * - normal: tocar una app la abre;
 * - elegir: guarda la app tocada para un botón del dock.
 */
class AppsActivity : Activity() {

    companion object {
        const val EXTRA_PICK_KEY = "pick_key"
        const val EXTRA_PICK_LABEL = "pick_label"
        /** Elegir una app para sumarla como fuente de música. */
        const val PICK_MUSIC_APP = "music_app"
    }

    private data class AppEntry(val label: String, val component: ComponentName, val icon: Drawable)

    private var apps: List<AppEntry> = emptyList()

    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(Theme.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_apps)

        val pickKey = intent.getStringExtra(EXTRA_PICK_KEY)
        val pickLabel = intent.getStringExtra(EXTRA_PICK_LABEL)

        val title = findViewById<TextView>(R.id.apps_title)
        title.text = if (pickKey != null) getString(R.string.pick_title, pickLabel) else getString(R.string.apps_title)

        findViewById<View>(R.id.apps_close).setOnClickListener { finish() }

        val grid = findViewById<GridView>(R.id.apps_grid)
        grid.setOnItemClickListener { _, _, position, _ ->
            val app = apps[position]
            if (pickKey == PICK_MUSIC_APP) {
                Sources.addApp(this, app.component.packageName)
                Sources.select(this, app.component.packageName)
                finish()
            } else if (pickKey != null) {
                Dock.save(this, pickKey, app.component)
                Toast.makeText(this, "${app.label} quedó en $pickLabel", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                launch(app.component)
            }
        }

        // Cargar íconos fuera del hilo principal: en el 8227L tarda.
        Thread {
            val loaded = loadApps()
            runOnUiThread {
                apps = loaded
                grid.adapter = Adapter()
            }
        }.start()
    }

    private fun loadApps(): List<AppEntry> {
        val pm = packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != packageName }
            .map {
                AppEntry(
                    label = it.loadLabel(pm).toString(),
                    component = ComponentName(it.activityInfo.packageName, it.activityInfo.name),
                    icon = it.loadIcon(pm)
                )
            }
        val known = apps.map { it.component }.toSet()

        // Las otras pantallas de inicio (la original del estéreo, Dashline…), por si
        // hace falta volver a una. Muchas no aparecen como app común.
        val homes = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .filter { it.activityInfo.packageName != packageName }
            .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) to it }
            .filter { (component, _) -> component !in known }
            .map { (component, info) ->
                AppEntry(
                    label = getString(R.string.home_label, info.loadLabel(pm).toString()),
                    component = component,
                    icon = info.loadIcon(pm)
                )
            }

        return (apps + homes).sortedBy { it.label.lowercase() }
    }

    private fun launch(component: ComponentName) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = apps.size
        override fun getItem(position: Int): Any = apps[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_app, parent, false)
            val app = apps[position]
            view.findViewById<ImageView>(R.id.app_icon).setImageDrawable(app.icon)
            view.findViewById<TextView>(R.id.app_label).text = app.label
            return view
        }
    }
}
