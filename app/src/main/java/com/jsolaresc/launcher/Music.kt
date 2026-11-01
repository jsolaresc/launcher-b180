package com.jsolaresc.launcher

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.provider.Settings

/**
 * Controla directamente la sesión de música de la fuente elegida (por ejemplo,
 * la del Bluetooth del estéreo, com.android.bluetooth), igual que Dashline.
 * Nunca manda teclas multimedia genéricas: en este estéreo las toma otro
 * reproductor y pausa el Bluetooth.
 */
class Music(private val ctx: Context, private val onChange: () -> Unit) {

    companion object {
        const val BT_PACKAGE = "com.android.bluetooth"
    }

    private val listener = ComponentName(ctx, MediaListener::class.java)
    private val manager = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private var controller: MediaController? = null
    private var listening = false

    /** Paquete de la fuente elegida. Si es null, no hay sesión que controlar (USB, radio). */
    var preferredPackage: String? = BT_PACKAGE
        set(value) {
            field = value
            pickSession()
        }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = onChange()
        override fun onMetadataChanged(metadata: MediaMetadata?) = onChange()
        override fun onSessionDestroyed() {
            pickSession()
        }
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { pickSession() }

    fun hasAccess(): Boolean {
        val enabled = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.contains(ctx.packageName)
    }

    fun start() {
        if (!hasAccess()) {
            onChange()
            return
        }
        try {
            manager.addOnActiveSessionsChangedListener(sessionsListener, listener)
            listening = true
        } catch (e: SecurityException) {
        }
        pickSession()
    }

    fun stop() {
        if (listening) manager.removeOnActiveSessionsChangedListener(sessionsListener)
        listening = false
        controller?.unregisterCallback(callback)
        controller = null
    }

    private fun pickSession() {
        val sessions = try {
            manager.getActiveSessions(listener)
        } catch (e: SecurityException) {
            emptyList()
        }
        val pkg = preferredPackage
        val chosen = if (pkg == null) null else sessions.firstOrNull { it.packageName == pkg }

        if (chosen?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = chosen
            controller?.registerCallback(callback)
        }
        onChange()
    }

    val hasSession get() = controller != null

    val isPlaying: Boolean
        get() = when (controller?.playbackState?.state) {
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING -> true
            else -> false
        }

    val title: String? get() = controller?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
    val artist: String?
        get() = controller?.metadata?.let {
            it.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: it.getString(MediaMetadata.METADATA_KEY_ALBUM)
        }

    /** Portada, si la fuente la manda (las apps del estéreo sí; el Bluetooth de este estéreo, no). */
    val art: Bitmap?
        get() = controller?.metadata?.let {
            it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: it.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: it.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        }

    /** Duración en ms; 0 si la fuente no la informa. */
    val duration: Long get() = controller?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L

    /** Posición estimada en ms, avanzando entre actualizaciones. */
    fun position(): Long {
        val state = controller?.playbackState ?: return 0L
        var pos = state.position
        if (pos < 0) return 0L
        if (state.state == PlaybackState.STATE_PLAYING && state.lastPositionUpdateTime > 0) {
            pos += ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed).toLong()
        }
        val d = duration
        return if (d > 0) pos.coerceIn(0, d) else pos
    }

    val stateName: String
        get() = when (controller?.playbackState?.state) {
            PlaybackState.STATE_PLAYING -> "reproduciendo"
            PlaybackState.STATE_PAUSED -> "en pausa"
            PlaybackState.STATE_STOPPED -> "detenida"
            PlaybackState.STATE_NONE -> "sin estado"
            PlaybackState.STATE_BUFFERING -> "cargando"
            PlaybackState.STATE_CONNECTING -> "conectando"
            PlaybackState.STATE_ERROR -> "error"
            null -> "sin sesión"
            else -> "estado ${controller?.playbackState?.state}"
        }

    /**
     * - sonando: pausa directo;
     * - en pausa: play directo, y hay que verificar que arranque;
     * - detenida o sin sesión: hay que activar la fuente.
     */
    fun playPause(): PlayResult {
        val c = controller ?: return PlayResult.NEEDS_SOURCE
        if (isPlaying) {
            c.transportControls.pause()
            return PlayResult.DONE
        }
        if (c.playbackState?.state == PlaybackState.STATE_PAUSED) {
            c.transportControls.play()
            return PlayResult.VERIFY
        }
        return PlayResult.NEEDS_SOURCE
    }

    enum class PlayResult { DONE, VERIFY, NEEDS_SOURCE }

    fun next(): Boolean {
        val c = controller ?: return false
        c.transportControls.skipToNext()
        return true
    }

    fun previous(): Boolean {
        val c = controller ?: return false
        c.transportControls.skipToPrevious()
        return true
    }

    /** Para diagnóstico: todas las sesiones que ve el launcher. */
    fun describeSessions(): String {
        val sessions = try {
            manager.getActiveSessions(listener)
        } catch (e: SecurityException) {
            return "Sin permiso"
        }
        if (sessions.isEmpty()) return "No hay sesiones de música activas"
        return sessions.joinToString("\n") { "${it.packageName}: ${it.playbackState?.state}" }
    }
}
