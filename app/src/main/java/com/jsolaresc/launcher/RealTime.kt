package com.jsolaresc.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import java.security.KeyStore
import java.security.cert.CertPathValidator
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Fecha real aunque el estéreo diga 2007.
 *
 * La app del fabricante (com.ts.MainUI) pone el reloj del sistema con el primer dato del GPS
 * de cada arranque, y este GPS tiene la falla de "vuelta de semana": informa la fecha
 * 1024 semanas (~19,6 años) atrás. La hora y el día de la semana quedan bien; la fecha no.
 * Sin permisos de sistema el launcher no puede cambiar el reloj, así que corrige la fecha
 * para lo suyo: lo que muestra y las conexiones seguras (que fallan con la fecha mal).
 */
object RealTime {
    private const val MIN_VALID = 1_735_689_600_000L // 1/1/2025
    private const val ROLLOVER = 1024L * 7 * 24 * 3600 * 1000

    /** Cuánto hay que sumarle al reloj del sistema (0 si está bien). */
    fun offset(): Long {
        var t = System.currentTimeMillis()
        var off = 0L
        while (t < MIN_VALID) {
            t += ROLLOVER
            off += ROLLOVER
        }
        return off
    }

    fun now(): Long = System.currentTimeMillis() + offset()

    fun date(): Date = Date(now())

    fun systemClockWrong() = offset() != 0L

    /**
     * Si el launcher tiene permiso para tocar ajustes del sistema (se da una vez por depuración),
     * apaga y prende "fecha automática": Android vuelve a pedir la hora por internet y la corrige.
     */
    fun tryNetworkResync(ctx: Context) {
        if (!systemClockWrong()) return
        try {
            val cr = ctx.contentResolver
            Settings.Global.putInt(cr, Settings.Global.AUTO_TIME, 0)
            Settings.Global.putInt(cr, Settings.Global.AUTO_TIME, 1)
        } catch (e: Exception) {
        }
    }

    private var installed = false

    /**
     * Las conexiones seguras del launcher validan el certificado con la fecha real
     * (si el reloj está bien, se usa la validación normal de Android).
     */
    @SuppressLint("CustomX509TrustManager")
    @Synchronized
    fun installTlsFix() {
        if (installed) return
        installed = true
        try {
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as KeyStore?)
            val platform = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
            val anchors = KeyStore.getInstance("AndroidCAStore").apply { load(null) }

            val tm = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
                    platform.checkClientTrusted(chain, authType)

                override fun getAcceptedIssuers(): Array<X509Certificate> = platform.acceptedIssuers

                override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                    if (!systemClockWrong()) {
                        platform.checkServerTrusted(chain, authType)
                        return
                    }
                    // Misma validación (cadena hasta una autoridad confiable del sistema), con la fecha real.
                    try {
                        val path = CertificateFactory.getInstance("X.509").generateCertPath(chain.toList())
                        val params = PKIXParameters(anchors)
                        params.isRevocationEnabled = false
                        params.date = date()
                        CertPathValidator.getInstance("PKIX").validate(path, params)
                    } catch (e: Exception) {
                        throw CertificateException(e)
                    }
                }
            }
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf<TrustManager>(tm), null)
            HttpsURLConnection.setDefaultSSLSocketFactory(ctx.socketFactory)
        } catch (e: Exception) {
        }
    }
}
