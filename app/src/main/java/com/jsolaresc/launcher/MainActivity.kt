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
import android.provider.Settings
import android.view.View
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
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
    private lateinit var music: Music
    private lateinit var playButton: ImageButton
    private lateinit var trackTitle: TextView
    private lateinit var trackArtist: TextView
    private lateinit var musicSource: TextView

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
        playButton = findViewById(R.id.btn_play)
        trackTitle = findViewById(R.id.track_title)
        trackArtist = findViewById(R.id.track_artist)
        musicSource = findViewById(R.id.music_source)
        music = Music(this) { runOnUiThread { renderMusic() } }

        findViewById<View>(R.id.music_card).setOnClickListener {
            if (!music.hasAccess()) requestAccess()
            else Dock.open(this, Dock.MUSIC, getString(R.string.label_music))
        }
        // Mantener apretada la tarjeta: muestra qué sesiones de música ve el launcher.
        findViewById<View>(R.id.music_card).setOnLongClickListener {
            Toast.makeText(this, music.describeSessions(), Toast.LENGTH_LONG).show()
            true
        }

        findViewById<View>(R.id.btn_prev).setOnClickListener {
            if (!music.hasAccess()) requestAccess() else music.previous()
        }
        playButton.setOnClickListener {
            if (!music.hasAccess()) {
                requestAccess()
                return@setOnClickListener
            }
            when (music.playPause()) {
                Music.PlayResult.DONE -> {}
                Music.PlayResult.NEEDS_SOURCE -> activateBtSource()
                // Si el estéreo no tenía la fuente BT activa, el celular arranca y se
                // vuelve a pausar: en ese caso, activarla.
                Music.PlayResult.VERIFY -> handler.postDelayed({
                    if (!music.isPlaying) activateBtSource()
                }, 1500)
            }
        }
        findViewById<View>(R.id.btn_next).setOnClickListener {
            if (!music.hasAccess()) requestAccess() else music.next()
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

    /**
     * La pantalla de música BT del estéreo, al abrirse, cambia el audio a la
     * fuente Bluetooth y da Play (com.ts.bt.BtMusicActivity.onResume). La
     * abrimos un instante y volvemos al launcher.
     */
    private fun activateBtSource() {
        try {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .setComponent(Dock.BT_MUSIC)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
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

    private fun renderMusic() {
        if (!music.hasAccess()) {
            musicSource.text = getString(R.string.music_source)
            trackTitle.text = getString(R.string.music_no_access_title)
            trackArtist.text = getString(R.string.music_no_access)
            playButton.setImageResource(R.drawable.ic_play)
            return
        }
        val source = if (music.source == Music.BT_PACKAGE) "Bluetooth" else (music.source ?: "Bluetooth")
        musicSource.text = "$source · ${music.stateName}"
        trackTitle.text = music.title?.takeIf { it.isNotBlank() } ?: getString(R.string.music_idle_title)
        trackArtist.text = music.artist?.takeIf { it.isNotBlank() } ?: getString(R.string.music_idle_hint)
        playButton.setImageResource(if (music.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
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
        music.start()
        registerReceiver(volumeReceiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION"))
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        unregisterReceiver(volumeReceiver)
        music.stop()
        super.onPause()
    }

    // Es la pantalla de inicio: "atrás" no tiene a dónde volver.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
    }
}
