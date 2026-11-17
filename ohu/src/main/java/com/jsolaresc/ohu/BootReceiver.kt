package com.jsolaresc.ohu

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Al encender el estéreo: la pantalla de inicio abre Open Headunit sola (con cuenta regresiva). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        HomeActivity.prefs(context).edit().putBoolean(HomeActivity.KEY_PENDING, true).apply()
    }
}
