package com.jsolaresc.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.KeyEvent
import net.osmand.aidlapi.IOsmAndAidlCallback
import net.osmand.aidlapi.IOsmAndAidlInterface
import net.osmand.aidlapi.customization.MapMarginsParams
import net.osmand.aidlapi.favorite.AFavorite
import net.osmand.aidlapi.favorite.AddFavoriteParams
import net.osmand.aidlapi.favorite.RemoveFavoriteParams
import net.osmand.aidlapi.favorite.group.AFavoriteGroup
import net.osmand.aidlapi.favorite.group.AddFavoriteGroupParams
import net.osmand.aidlapi.gpx.AGpxBitmap
import net.osmand.aidlapi.logcat.OnLogcatMessageParams
import net.osmand.aidlapi.navigation.ADirectionInfo
import net.osmand.aidlapi.navigation.ANavigationVoiceRouterMessageParams
import net.osmand.aidlapi.navigation.NavigateParams
import net.osmand.aidlapi.navigation.OnVoiceNavigationParams
import net.osmand.aidlapi.search.SearchParams
import net.osmand.aidlapi.search.SearchResult

/**
 * Conexión con OsmAnd por su interfaz pública (AIDL): reservarle al panel del launcher
 * su lugar en el mapa, ocultar botones que sobran, navegar, buscar direcciones,
 * guardar los destinos como favoritos y enterarse de cuándo habla la voz.
 */
object OsmAnd {
    private const val SERVICE = "net.osmand.aidl.OsmandAidlServiceV2"
    const val FAVORITES_GROUP = "Launcher"

    @Volatile
    private var api: IOsmAndAidlInterface? = null
    private var bound = false
    private var appCtx: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private val onConnect = ArrayList<(IOsmAndAidlInterface) -> Unit>()

    /** La voz de OsmAnd va a decir algo (lista de frases). */
    var onVoice: ((List<String>) -> Unit)? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val a = IOsmAndAidlInterface.Stub.asInterface(service)
            api = a
            registerVoice(a)
            val pending = ArrayList(onConnect)
            onConnect.clear()
            pending.forEach { safe { it(a) } }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            api = null
            bound = false
        }
    }

    fun connect(ctx: Context) {
        if (bound) return
        val pkg = NavLaunch.osmand(ctx) ?: return
        appCtx = ctx.applicationContext
        bound = try {
            ctx.applicationContext.bindService(
                Intent(SERVICE).setPackage(pkg), connection, Context.BIND_AUTO_CREATE
            )
        } catch (e: Exception) {
            false
        }
    }

    /** Corre la acción ya, o apenas se conecte. */
    private fun withApi(ctx: Context, action: (IOsmAndAidlInterface) -> Unit) {
        val a = api
        if (a != null) {
            safe { action(a) }
            return
        }
        onConnect.add(action)
        connect(ctx)
    }

    private inline fun safe(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
        }
    }

    /**
     * Lugar del panel del launcher: el costado derecho y el dock, en píxeles.
     * Ojo: OsmAnd 5.4 intercambia derecha y abajo al recibirlos; se compensa acá.
     */
    fun setMargins(ctx: Context, left: Int, top: Int, right: Int, bottom: Int) = withApi(ctx) {
        it.setMapMargins(MapMarginsParams(left, top, bottom, right, listOf("car", "default", "bicycle", "pedestrian")))
    }

    /** Pocos botones en el inicio; con el mapa completo, todos. */
    fun setMinimal(ctx: Context, minimal: Boolean) = withApi(ctx) {
        val hidden = listOf(
            "map.view.layers", "map.view.compass", "map.view.menu", "map.view.route_planning",
            "map.view.quick_action", "map.view.map_3d", "map.view.quick_search"
        )
        if (minimal) it.setDisabledIds(hidden) else it.setEnabledIds(hidden)
    }

    fun navigate(ctx: Context, place: Place): Boolean {
        if (NavLaunch.osmand(ctx) == null) return false
        withApi(ctx) {
            it.navigate(NavigateParams(null, 0.0, 0.0, place.name, place.lat, place.lon, "car", true, false))
        }
        return true
    }

    /** Busca una dirección con los mapas de OsmAnd (sin internet). */
    fun search(ctx: Context, query: String, lat: Double, lon: Double, onResult: (List<SearchResult>) -> Unit) =
        withApi(ctx) { a ->
            a.search(SearchParams(query, SearchParams.SEARCH_TYPE_ADDRESS, lat, lon, 1, 5), object : Callback() {
                override fun onSearchComplete(resultSet: MutableList<SearchResult>?) {
                    val list = resultSet?.toList() ?: emptyList()
                    main.post { onResult(list) }
                }
            })
        }

    /** Los destinos rápidos quedan como favoritos en el grupo "Launcher" (se ven en el mapa). */
    fun syncFavorites(ctx: Context, old: List<Place>, now: List<Place>) = withApi(ctx) { a ->
        a.addFavoriteGroup(AddFavoriteGroupParams(AFavoriteGroup(FAVORITES_GROUP, "#8AB4F8", true)))
        for (p in old) {
            safe { a.removeFavorite(RemoveFavoriteParams(fav(p))) }
        }
        for (p in now) {
            safe { a.addFavorite(AddFavoriteParams(fav(p))) }
        }
    }

    private fun fav(p: Place) = AFavorite(p.lat, p.lon, p.name, "", "", FAVORITES_GROUP, "#8AB4F8", true)

    private var voiceCallbackId = 0L

    private fun registerVoice(a: IOsmAndAidlInterface) = safe {
        val params = ANavigationVoiceRouterMessageParams()
        params.setSubscribeToUpdates(true)
        params.callbackId = voiceCallbackId
        voiceCallbackId = a.registerForVoiceRouterMessages(params, object : Callback() {
            override fun onVoiceRouterNotify(params: OnVoiceNavigationParams?) {
                val text = params?.played?.takeIf { it.isNotEmpty() } ?: params?.commands ?: return
                main.post { onVoice?.invoke(text) }
            }
        })
    }

    /** Respuestas de OsmAnd: solo se usan algunas. */
    private open class Callback : IOsmAndAidlCallback.Stub() {
        override fun onSearchComplete(resultSet: MutableList<SearchResult>?) {}
        override fun onUpdate() {}
        override fun onAppInitialized() {}
        override fun onGpxBitmapCreated(bitmap: AGpxBitmap?) {}
        override fun updateNavigationInfo(directionInfo: ADirectionInfo?) {}
        override fun onContextMenuButtonClicked(buttonId: Int, pointId: String?, layerId: String?) {}
        override fun onVoiceRouterNotify(params: OnVoiceNavigationParams?) {}
        override fun onKeyEvent(params: KeyEvent?) {}
        override fun onLogcatMessage(params: OnLogcatMessageParams?) {}
    }
}
