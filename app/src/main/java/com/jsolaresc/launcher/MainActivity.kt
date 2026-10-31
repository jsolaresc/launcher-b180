package com.jsolaresc.launcher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val locale = Locale("es", "AR")
    private val timeFormat = SimpleDateFormat("HH:mm", locale)
    private val dateFormat = SimpleDateFormat("EEEE d MMM", locale)

    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var dockClock: TextView
    private lateinit var volume: SeekBar
    private lateinit var audio: AudioManager

    // Mantiene la barra al día si el volumen cambia con la perilla o el volante.
    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshVolume()
        }
    }

    // Actualiza la hora justo al cambiar de minuto, sin despertar cada segundo.
    private val tick = object : Runnable {
        override fun run() {
            updateClock()
            val seconds = Calendar.getInstance().get(Calendar.SECOND)
            handler.postDelayed(this, (60 - seconds) * 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        clock = findViewById(R.id.clock)
        date = findViewById(R.id.date)
        dockClock = findViewById(R.id.dock_clock)

        bindDock(R.id.dock_maps, Dock.MAPS, getString(R.string.label_maps))
        bindDock(R.id.dock_music, Dock.MUSIC, getString(R.string.label_music))
        bindDock(R.id.dock_phone, Dock.PHONE, getString(R.string.label_phone))
        bindDock(R.id.dock_radio, Dock.RADIO, getString(R.string.label_radio))
        bindDock(R.id.dock_settings, Dock.SETTINGS, getString(R.string.label_settings))

        findViewById<View>(R.id.dock_apps).setOnClickListener {
            startActivity(Intent(this, AppsActivity::class.java))
        }
        findViewById<View>(R.id.map_panel).setOnClickListener {
            Dock.open(this, Dock.MAPS, getString(R.string.label_maps))
        }
        findViewById<View>(R.id.music_card).setOnClickListener {
            Dock.open(this, Dock.MUSIC, getString(R.string.label_music))
        }

        findViewById<View>(R.id.btn_prev).setOnClickListener {
            MediaKeys.send(this, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        }
        findViewById<View>(R.id.btn_play).setOnClickListener {
            MediaKeys.send(this, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        }
        findViewById<View>(R.id.btn_next).setOnClickListener {
            MediaKeys.send(this, KeyEvent.KEYCODE_MEDIA_NEXT)
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
        findViewById<View>(R.id.vol_down).setOnClickListener { stepVolume(AudioManager.ADJUST_LOWER) }
        findViewById<View>(R.id.vol_up).setOnClickListener { stepVolume(AudioManager.ADJUST_RAISE) }
    }

    private fun stepVolume(direction: Int) {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
        refreshVolume()
    }

    private fun refreshVolume() {
        volume.progress = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
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
        val now = Date()
        val time = timeFormat.format(now)
        clock.text = time
        dockClock.text = time
        date.text = dateFormat.format(now)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(tick)
        handler.post(tick)
        refreshVolume()
        registerReceiver(volumeReceiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION"))
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        unregisterReceiver(volumeReceiver)
        super.onPause()
    }

    // Es la pantalla de inicio: "atrás" no tiene a dónde volver.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
    }
}
