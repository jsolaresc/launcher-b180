package com.jsolaresc.launcher

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings

/**
 * Controla directamente la sesión de música activa (la del Bluetooth del
 * estéreo, com.android.bluetooth), igual que Dashline. Nunca manda teclas
 * multimedia genéricas: en este estéreo las toma otro reproductor y pausa el BT.
 */
class Music(private val ctx: Context, private val onChange: () -> Unit) {

    companion object {
        const val BT_PACKAGE = "com.android.bluetooth"
    }

    private val listener = ComponentName(ctx, MediaListener::class.java)
    private val manager = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private var controller: MediaController? = null
    private var listening = false

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
            // El permiso se acaba de revocar.
        }
        pickSession()
    }

    fun stop() {
        if (listening) manager.removeOnActiveSessionsChangedListener(sessionsListener)
        listening = false
        controller?.unregisterCallback(callback)
        controller = null
    }

    /** Prioridad: la que está sonando > la del Bluetooth > la primera. */
    private fun pickSession() {
        val sessions = try {
            manager.getActiveSessions(listener)
        } catch (e: SecurityException) {
            emptyList()
        }
        val chosen = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull { it.packageName == BT_PACKAGE }
            ?: sessions.firstOrNull()

        if (chosen?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = chosen
            controller?.registerCallback(callback)
        }
        onChange()
    }

    val hasSession get() = controller != null
    val source: String? get() = controller?.packageName

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

    /** Devuelve false si no hay sesión a la que hablarle. */
    fun playPause(): Boolean {
        val c = controller ?: return false
        if (isPlaying) c.transportControls.pause() else c.transportControls.play()
        return true
    }

    fun next() = controller?.transportControls?.skipToNext()
    fun previous() = controller?.transportControls?.skipToPrevious()

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
