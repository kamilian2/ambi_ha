package pl.kamil.ambiha

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Połączenie z Home Assistantem przez WebSocket:
 * stany wszystkich encji na bieżąco, wywoływanie usług, lista dashboardów.
 */
class HaClient(private val cfg: Config) {

    private val tag = "AmbiHa"
    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    /** entity_id -> stan (obiekt jak w API HA). */
    val states = ConcurrentHashMap<String, JSONObject>()

    @Volatile var status: String = "Nie skonfigurowano"
        private set
    @Volatile var connected = false
        private set

    private var ws: WebSocket? = null
    private var generation = 0
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, (JSONObject) -> Unit>()

    // ---------- powiadamianie ekranów (zawsze na wątku głównym) ----------
    private val stateListeners = mutableListOf<(String) -> Unit>()
    private val statusListeners = mutableListOf<() -> Unit>()

    fun onState(l: (String) -> Unit) { synchronized(stateListeners) { stateListeners.add(l) } }
    fun offState(l: (String) -> Unit) { synchronized(stateListeners) { stateListeners.remove(l) } }
    fun onStatus(l: () -> Unit) { synchronized(statusListeners) { statusListeners.add(l) } }
    fun offStatus(l: () -> Unit) { synchronized(statusListeners) { statusListeners.remove(l) } }

    private fun fireState(id: String) {
        main.post { synchronized(stateListeners) { stateListeners.toList() }.forEach { it(id) } }
    }
    private fun fireStatus() {
        main.post { synchronized(statusListeners) { statusListeners.toList() }.forEach { it() } }
    }
    private fun setStatus(s: String, ok: Boolean) {
        status = s; connected = ok; fireStatus()
    }

    // ---------- połączenie ----------

    @Synchronized fun connect() {
        generation++
        val gen = generation
        try { ws?.close(1000, "reconnect") } catch (_: Exception) {}
        ws = null
        connected = false
        pending.clear()
        val url = cfg.haUrl
        val token = cfg.haToken
        if (url.isEmpty() || token.isEmpty()) {
            setStatus("Podaj adres i token HA na stronie ustawień", false)
            return
        }
        val wsUrl = url.replaceFirst(Regex("^http", RegexOption.IGNORE_CASE), "ws") + "/api/websocket"
        setStatus("Łączenie z $url…", false)
        val req = try { Request.Builder().url(wsUrl).build() } catch (e: Exception) {
            setStatus("Zły adres HA: $url", false); return
        }
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen != generation) return
                try { handle(webSocket, JSONObject(text), token) } catch (e: Exception) { Log.w(tag, "msg: $e") }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (gen != generation) return
                setStatus("Brak połączenia z HA: ${t.message ?: t.javaClass.simpleName}", false)
                retry(gen)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (gen != generation) return
                setStatus("Połączenie zamknięte ($code) – ponawiam", false)
                retry(gen)
            }
        })
    }

    private fun retry(gen: Int) {
        main.postDelayed({ if (gen == generation) connect() }, 5000)
    }

    private fun handle(socket: WebSocket, m: JSONObject, token: String) {
        when (m.optString("type")) {
            "auth_required" -> socket.send(JSONObject().put("type", "auth").put("access_token", token).toString())
            "auth_invalid" -> {
                setStatus("Zły token – wklej nowy na stronie ustawień", false)
                generation++ // bez ponawiania z błędnym tokenem
                socket.close(1000, "auth")
            }
            "auth_ok" -> {
                setStatus("Połączono z HA (" + m.optString("ha_version") + ")", true)
                send(JSONObject().put("type", "get_states")) { r ->
                    val arr = r.optJSONArray("result") ?: JSONArray()
                    states.clear()
                    for (i in 0 until arr.length()) {
                        val s = arr.getJSONObject(i)
                        states[s.optString("entity_id")] = s
                    }
                    fireState("*")
                }
                send(JSONObject().put("type", "subscribe_events").put("event_type", "state_changed")) { }
            }
            "result" -> pending.remove(m.optInt("id"))?.invoke(m)
            "event" -> {
                val data = m.optJSONObject("event")?.optJSONObject("data") ?: return
                val id = data.optString("entity_id")
                val ns = data.optJSONObject("new_state")
                if (ns == null) states.remove(id) else states[id] = ns
                fireState(id)
            }
        }
    }

    /** Wysyła polecenie; odpowiedź przychodzi na wątku głównym. */
    fun send(msg: JSONObject, cb: ((JSONObject) -> Unit)? = null): Boolean {
        val socket = ws ?: return false
        val id = nextId.getAndIncrement()
        msg.put("id", id)
        if (cb != null) pending[id] = { r -> main.post { cb(r) } }
        return socket.send(msg.toString())
    }

    fun callService(domain: String, service: String, entityId: String?, data: JSONObject? = null,
                    done: ((Boolean, String) -> Unit)? = null) {
        val msg = JSONObject().put("type", "call_service").put("domain", domain).put("service", service)
        if (data != null) msg.put("service_data", data)
        if (entityId != null) msg.put("target", JSONObject().put("entity_id", entityId))
        val ok = send(msg) { r ->
            val success = r.optBoolean("success", false)
            done?.invoke(success, r.optJSONObject("error")?.optString("message") ?: "")
        }
        if (!ok) done?.invoke(false, "brak połączenia z HA")
    }

    /** Sprawdzenie adresu i tokenu (wywoływać poza wątkiem głównym). */
    fun test(url: String, token: String): String {
        val req = Request.Builder().url(url.trimEnd('/') + "/api/")
            .header("Authorization", "Bearer $token").build()
        http.newCall(req).execute().use { r ->
            return when (r.code) {
                200 -> JSONObject(r.body?.string() ?: "{}").optString("message", "OK")
                401 -> throw IllegalStateException("zły token (401)")
                else -> throw IllegalStateException("HTTP ${r.code}")
            }
        }
    }
}
