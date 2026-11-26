package pl.kamil.ambiha

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ustawienia (zapisywane w telewizorze).
 *
 *  ha_url, ha_token            – Home Assistant
 *  tv_host, tv_user, tv_pass   – API telewizora (dane z parowania)
 *  enabled                     – synchronizacja włączona
 *  fps, brightness, saturation, smoothing, min_brightness, transition, standby_stop, restore
 *  lamps: [{entity, zone, enabled, max_hz, brightness}]
 *  wled:  [{host, mode: "zone"|"around", zone, leds, left, top, right, bottom, start, clockwise, brightness, enabled}]
 */
class Config(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("ambiha", Context.MODE_PRIVATE)
    private var data: JSONObject = try { JSONObject(prefs.getString("config", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    private val listeners = mutableListOf<() -> Unit>()
    fun addListener(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }

    @Synchronized fun snapshot(): JSONObject = JSONObject(data.toString())

    private fun s(k: String, d: String) = synchronized(this) { data.optString(k, d) }
    private fun i(k: String, d: Int) = synchronized(this) { data.optInt(k, d) }
    private fun b(k: String, d: Boolean) = synchronized(this) { data.optBoolean(k, d) }

    val haUrl get() = s("ha_url", "").trim().trimEnd('/')
    val haToken get() = s("ha_token", "").trim()
    val tvHost get() = s("tv_host", "127.0.0.1").trim().ifEmpty { "127.0.0.1" }
    val tvUser get() = s("tv_user", "")
    val tvPass get() = s("tv_pass", "")
    val enabled get() = b("enabled", false)
    val fps get() = i("fps", 12).coerceIn(1, 30)
    val brightness get() = i("brightness", 100).coerceIn(5, 200)
    val saturation get() = i("saturation", 120).coerceIn(50, 250)
    val smoothing get() = i("smoothing", 40).coerceIn(0, 95)
    val minBrightness get() = i("min_brightness", 3).coerceIn(0, 50)
    val transition get() = i("transition", 300).coerceIn(0, 3000)
    val standbyStop get() = b("standby_stop", true)
    val restore get() = b("restore", true)
    /** Szybki tryb: tylko kolor, bez zmiany jasności – jedno polecenie mniej dla żarówki. */
    val fastMode get() = b("fast_mode", false)
    /** Co zrobić z lampami po wyłączeniu synchronizacji: white | restore | off | none. */
    val offMode get() = s("off_mode", if (b("restore", true)) "restore" else "none")
    val whiteKelvin get() = i("white_kelvin", 4000).coerceIn(2000, 6500)
    val whiteBrightness get() = i("white_brightness", 100).coerceIn(1, 100)

    val lamps: List<JSONObject> get() = arr("lamps")
    val wled: List<JSONObject> get() = arr("wled")

    private fun arr(k: String): List<JSONObject> = synchronized(this) {
        val a = data.optJSONArray(k) ?: JSONArray()
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }

    /** Zmienia podane pola; pusty token nie kasuje zapisanego. */
    fun update(patch: JSONObject) {
        synchronized(this) {
            // klucze Tuya nie wracają na stronę – puste pole = zostaw zapisany klucz
            patch.optJSONArray("lamps")?.let { arr ->
                val old = data.optJSONArray("lamps") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val l = arr.optJSONObject(i) ?: continue
                    l.remove("tuya_key_set")
                    if (l.optString("tuya_key").isBlank()) {
                        for (j in 0 until old.length()) {
                            val o = old.optJSONObject(j) ?: continue
                            if (o.optString("entity") == l.optString("entity")) l.put("tuya_key", o.optString("tuya_key"))
                        }
                    }
                }
            }
            for (k in patch.keys()) {
                if (k == "ha_token" && patch.optString(k).isBlank()) continue
                if (k.endsWith("_set")) continue
                data.put(k, patch.get(k))
            }
            prefs.edit().putString("config", data.toString()).apply()
        }
        synchronized(listeners) { listeners.toList() }.forEach { it() }
    }

    /** Ustawienia dla strony WWW – bez tajnych danych. */
    fun masked(): JSONObject = snapshot().also {
        it.put("ha_token_set", it.optString("ha_token").isNotBlank()); it.put("ha_token", "")
        it.put("tv_paired", it.optString("tv_user").isNotBlank()); it.remove("tv_pass")
        it.put("tv_host", tvHost); it.put("enabled", enabled); it.put("fps", fps); it.put("brightness", brightness)
        it.put("saturation", saturation); it.put("smoothing", smoothing); it.put("min_brightness", minBrightness)
        it.put("transition", transition); it.put("standby_stop", standbyStop); it.put("restore", restore)
        it.put("fast_mode", fastMode); it.put("off_mode", offMode)
        it.put("white_kelvin", whiteKelvin); it.put("white_brightness", whiteBrightness)
        if (!it.has("lamps")) it.put("lamps", JSONArray())
        it.optJSONArray("lamps")?.let { arr ->
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { l ->
                l.put("tuya_key_set", l.optString("tuya_key").isNotBlank()); l.put("tuya_key", "")
            }
        }
        if (!it.has("wled")) it.put("wled", JSONArray())
    }
}
