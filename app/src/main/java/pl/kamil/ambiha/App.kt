package pl.kamil.ambiha

import android.app.Application
import android.util.Log
import fi.iki.elonen.NanoHTTPD

/** Wspólne obiekty: ustawienia, Home Assistant, API telewizora, pętla synchronizacji, serwer ustawień. */
class App : Application() {
    companion object { lateinit var instance: App private set }

    lateinit var config: Config private set
    lateinit var ha: HaClient private set
    lateinit var tv: JointSpace private set
    lateinit var engine: Engine private set
    private var server: ApiServer? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        installCrashLogger(this)
        config = Config(this)
        ha = HaClient(config)
        tv = JointSpace(config)
        engine = Engine(config, tv, ha)

        var lastUrl = config.haUrl
        var lastToken = config.haToken
        config.addListener {
            if (config.haUrl != lastUrl || config.haToken != lastToken) {
                lastUrl = config.haUrl; lastToken = config.haToken
                ha.connect()
            }
        }
        ha.connect()
        engine.start()
        startServer()
    }

    fun startServer() {
        if (server?.isAlive == true) return
        server = try {
            val v = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" }
            ApiServer(API_PORT, this, v).also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        } catch (e: Exception) {
            Log.e("AmbiHa", "Serwer ustawień nie wystartował", e); null
        }
    }
}
