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
        /** Elegir una app y devolverla a quien llamó (Ajustes → barra inferior). */
        const val PICK_RETURN = "return"
        const val EXTRA_COMPONENT = "component"
        const val EXTRA_APP_LABEL = "app_label"
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
            if (pickKey == PICK_RETURN) {
                setResult(
                    RESULT_OK, Intent()
                        .putExtra(EXTRA_COMPONENT, app.component.flattenToString())
                        .putExtra(EXTRA_APP_LABEL, app.label)
                )
                finish()
            } else if (pickKey == PICK_MUSIC_APP) {
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

        if (pickKey == null) setupTrash(grid)
        reload()
    }

    private fun reload() {
        val grid = findViewById<GridView>(R.id.apps_grid)
        // Cargar íconos fuera del hilo principal: en el 8227L tarda.
        Thread {
            val loaded = loadApps()
            runOnUiThread {
                apps = loaded
                grid.adapter = Adapter()
            }
        }.start()
    }

    /** Al volver de desinstalar, la lista se actualiza. */
    private var uninstalling = false

    override fun onResume() {
        super.onResume()
        if (uninstalling) {
            uninstalling = false
            reload()
        }
    }

    /** Mantener apretada una app y arrastrarla al tacho: Android pide confirmar la desinstalación. */
    private fun setupTrash(grid: GridView) {
        val trash = findViewById<View>(R.id.apps_trash)
        grid.setOnItemLongClickListener { _, view, position, _ ->
            val app = apps[position]
            val data = android.content.ClipData.newPlainText("app", app.component.packageName)
            trash.visibility = View.VISIBLE
            @Suppress("DEPRECATION")
            if (android.os.Build.VERSION.SDK_INT >= 24) view.startDragAndDrop(data, View.DragShadowBuilder(view), app, 0)
            else view.startDrag(data, View.DragShadowBuilder(view), app, 0)
            true
        }
        trash.setOnDragListener { v, event ->
            when (event.action) {
                android.view.DragEvent.ACTION_DRAG_ENTERED -> v.setBackgroundResource(R.drawable.bg_trash_active)
                android.view.DragEvent.ACTION_DRAG_EXITED -> v.setBackgroundResource(R.drawable.bg_trash)
                android.view.DragEvent.ACTION_DROP -> (event.localState as? AppEntry)?.let { uninstall(it) }
                android.view.DragEvent.ACTION_DRAG_ENDED -> {
                    v.setBackgroundResource(R.drawable.bg_trash)
                    v.visibility = View.GONE
                }
            }
            true
        }
    }

    private fun uninstall(app: AppEntry) {
        val pkg = app.component.packageName
        val system = try {
            (packageManager.getApplicationInfo(pkg, 0).flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (e: Exception) {
            true
        }
        if (system) {
            Toast.makeText(this, getString(R.string.apps_system, app.label), Toast.LENGTH_LONG).show()
            return
        }
        uninstalling = true
        try {
            startActivity(Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:$pkg")))
        } catch (e: Exception) {
            uninstalling = false
            Toast.makeText(this, R.string.open_failed, Toast.LENGTH_SHORT).show()
        }
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
