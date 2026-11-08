package com.jsolaresc.launcher

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * Pantalla invisible que aparece y se va enseguida. OsmAnd solo aplica los márgenes
 * (el lugar del panel y el dock) y los botones ocultos al volver a primer plano;
 * esto lo hace "volver" sin que se note.
 */
class RelayoutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Handler(Looper.getMainLooper()).postDelayed({
            finish()
            overridePendingTransition(0, 0)
        }, 60)
    }
}
