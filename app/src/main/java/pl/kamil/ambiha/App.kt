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
        try { startSyncService(this) } catch (e: Exception) { Log.w("AmbiHa", "service: $e") }
        scheduleWatchdog()
    }

    /** Co 15 min budzi aplikację, gdyby system ją zamknął (telewizor w czuwaniu nie robi pełnego restartu). */
    private fun scheduleWatchdog() {
        val am = getSystemService(ALARM_SERVICE) as android.app.AlarmManager
        val pi = android.app.PendingIntent.getBroadcast(this, 1,
            android.content.Intent(this, BootReceiver::class.java).setAction("pl.kamil.ambiha.WATCHDOG"),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        am.setInexactRepeating(android.app.AlarmManager.ELAPSED_REALTIME,
            android.os.SystemClock.elapsedRealtime() + 15 * 60_000L, android.app.AlarmManager.INTERVAL_FIFTEEN_MINUTES, pi)
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
