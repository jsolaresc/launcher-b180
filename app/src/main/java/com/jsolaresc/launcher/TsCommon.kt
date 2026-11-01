package com.jsolaresc.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel

/**
 * Cliente del servicio que la app del estéreo (com.ts.MainUI) deja abierto a
 * otras apps: com.ts.main.common.MainUI, interfaz ITsCommon. Con él se lee el
 * archivo que suena por USB, la frecuencia de la radio y la fuente activa, y se
 * mandan las mismas teclas que los botones del frente o del volante.
 * Los códigos salen del APK del estéreo (ITsCommon.Stub).
 */
class TsCommon(private val ctx: Context) {

    companion object {
        private const val DESCRIPTOR = "com.ts.main.common.ITsCommon"
        private const val TX_ENTER_MODE = 1
        private const val TX_SEND_KEY = 6
        private const val TX_WORK_MODE = 10
        private const val TX_NIGHT = 14
        private const val TX_CUR_TIME = 26
        private const val TX_TOTAL_TIME = 27
        private const val TX_PLAY_STATE = 28
        private const val TX_SONG_NAME = 29
        private const val TX_BAND = 33
        private const val TX_FREQ = 34

        // Fuentes (StSet.WORKMODE_*)
        const val MODE_RADIO = 1
        const val MODE_USB = 3
        const val MODE_BT = 5

        // Teclas (KeyDef.PKEY_*)
        const val KEY_NEXT = 44
        const val KEY_PREV = 45
        const val KEY_PLAY = 90
        const val KEY_PAUSE = 91
    }

    @Volatile
    private var binder: IBinder? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
        }
    }

    val connected get() = binder != null

    fun connect() {
        if (bound) return
        bound = try {
            ctx.bindService(
                Intent("android.intent.action.MAIN_UI").setPackage("com.ts.MainUI"),
                connection,
                Context.BIND_AUTO_CREATE
            )
        } catch (e: Exception) {
            false
        }
    }

    fun disconnect() {
        if (!bound) return
        try {
            ctx.unbindService(connection)
        } catch (e: Exception) {
        }
        bound = false
        binder = null
    }

    private fun <T> call(code: Int, arg: Int? = null, read: (Parcel) -> T): T? {
        val b = binder ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            if (arg != null) data.writeInt(arg)
            b.transact(code, data, reply, 0)
            reply.readException()
            read(reply)
        } catch (e: Exception) {
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /** Luces del auto encendidas (cable de iluminación, Mcu.GetIll()). */
    fun isNight(): Boolean? = call(TX_NIGHT) { it.readInt() != 0 }

    fun workMode(): Int? = call(TX_WORK_MODE) { it.readInt() }
    fun enterMode(mode: Int) = call(TX_ENTER_MODE, mode) { } != null
    fun sendKey(key: Int) = call(TX_SEND_KEY, key) { } != null
    fun songName(): String? = call(TX_SONG_NAME) { it.readString() }?.trim()?.takeIf { it.isNotEmpty() }
    fun playState(): Int? = call(TX_PLAY_STATE) { it.readInt() }
    fun currentTime(): Int? = call(TX_CUR_TIME) { it.readInt() }
    fun totalTime(): Int? = call(TX_TOTAL_TIME) { it.readInt() }
    fun band(): String? = call(TX_BAND) { it.readString() }
    fun freq(): String? = call(TX_FREQ) { it.readString() }?.trim()?.takeIf { it.isNotEmpty() }
}
