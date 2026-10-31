package com.jsolaresc.launcher

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast

/**
 * Accesos del dock. Cada botón abre la app guardada para ese lugar.
 * Si todavía no hay ninguna (radio, música BT, teléfono), abre el selector
 * para elegirla. Mantener apretado un botón permite cambiarla.
 */
object Dock {
    const val MAPS = "maps"
    const val MUSIC = "music"
    const val PHONE = "phone"
    const val RADIO = "radio"
    const val SETTINGS = "settings"

    private const val PREFS = "dock"
    private const val MAPS_PACKAGE = "com.google.android.apps.maps"

    fun saved(ctx: Context, key: String): ComponentName? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key, null)
            ?.let { ComponentName.unflattenFromString(it) }

    fun save(ctx: Context, key: String, component: ComponentName) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(key, component.flattenToString())
            .apply()
    }

    private fun defaultIntent(ctx: Context, key: String): Intent? = when (key) {
        MAPS -> ctx.packageManager.getLaunchIntentForPackage(MAPS_PACKAGE)
        SETTINGS -> Intent(Settings.ACTION_SETTINGS)
        else -> null
    }

    fun open(activity: Activity, key: String, label: String) {
        val component = saved(activity, key)
        val intent = if (component != null) {
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component)
        } else {
            defaultIntent(activity, key)
        }

        if (intent == null) {
            pick(activity, key, label)
            return
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            activity.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(activity, "No pude abrir $label. Elegí la app.", Toast.LENGTH_SHORT).show()
            pick(activity, key, label)
        }
    }

    fun pick(activity: Activity, key: String, label: String) {
        activity.startActivity(
            Intent(activity, AppsActivity::class.java)
                .putExtra(AppsActivity.EXTRA_PICK_KEY, key)
                .putExtra(AppsActivity.EXTRA_PICK_LABEL, label)
        )
    }
}
