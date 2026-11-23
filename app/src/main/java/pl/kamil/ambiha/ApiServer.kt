package pl.kamil.ambiha

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject

/**
 * Strona ustawień (GET /) i API.
 *
 *  GET  /config, /status, /lights (lampy RGB z HA), /log
 *  POST /settings          zmiana ustawień
 *  POST /test_ha, /test_tv sprawdzenie połączeń
 *  POST /pair/request      parowanie z TV (PIN pojawi się na ekranie)
 *  POST /pair/grant        {"pin":"1234"}
 *  POST /on, /off, /toggle synchronizacja (np. z automatyzacji HA)
 */
class ApiServer(port: Int, private val ctx: Context, private val version: String) : NanoHTTPD(port) {

    private val app get() = App.instance

    override fun serve(session: IHTTPSession): Response {
        return try {
            val uri = session.uri.trimEnd('/').ifEmpty { "/" }
            if (session.method == Method.OPTIONS) return json(ok())
            if (session.method == Method.GET) {
                return when (uri) {
                    "/", "/index.html" -> newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8",
                        ctx.assets.open("web/index.html").bufferedReader(Charsets.UTF_8).use { it.readText() })
                    "/config" -> json(ok().put("version", version).put("config", app.config.masked())
                        .put("zones", JSONArray(Engine.ZONES.map { JSONObject().put("value", it.first).put("name", it.second) }))
                        .put("ip", JSONArray(localIpAddresses())))
                    "/status" -> json(status())
                    "/lights" -> json(ok().put("lights", lights()))
                    "/log" -> newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8",
                        lastCrash(ctx).ifEmpty { "Brak zapisanych błędów." })
                    else -> json(err("Nieznany adres"), Response.Status.NOT_FOUND)
                }
            }
            val j = readBody(session).let { if (it.isBlank()) JSONObject() else JSONObject(it) }
            when (uri) {
                "/settings" -> { app.config.update(j); json(ok().put("config", app.config.masked())) }
                "/on" -> { app.config.update(JSONObject().put("enabled", true)); json(ok()) }
                "/off" -> { app.config.update(JSONObject().put("enabled", false)); json(ok()) }
                "/toggle" -> { app.config.update(JSONObject().put("enabled", !app.config.enabled)); json(ok()) }
                "/test_ha" -> {
                    val url = j.optString("url", "").ifBlank { app.config.haUrl }.trim().trimEnd('/')
                    val token = j.optString("token", "").ifBlank { app.config.haToken }.trim()
                    if (url.isEmpty() || token.isEmpty()) throw IllegalArgumentException("Podaj adres i token HA")
                    json(ok().put("message", app.ha.test(url, token)))
                }
                "/test_tv" -> json(ok().put("message", app.tv.test()))
                "/pair/request" -> json(ok().put("message", app.tv.pairRequest()))
                "/pair/grant" -> json(ok().put("message", app.tv.pairGrant(j.optString("pin"))))
                "/pair/forget" -> {
                    app.config.update(JSONObject().put("tv_user", "").put("tv_pass", "")); json(ok())
                }
                else -> json(err("Nieznany adres"), Response.Status.NOT_FOUND)
            }
        } catch (e: Exception) {
            json(err(e.message ?: e.toString()), Response.Status.BAD_REQUEST)
        }
    }

    private fun ok() = JSONObject().put("ok", true)
    private fun err(m: String) = JSONObject().put("ok", false).put("error", m)

    private fun status(): JSONObject {
        val e = app.engine
        val sides = JSONObject()
        // gdy synchronizacja jest wyłączona – odczytaj kolory tylko do podglądu
        val src = if (e.running) e.sides else try { app.tv.ambilight() } catch (ex: Exception) { emptyMap() }
        for ((k, v) in src) sides.put(k, JSONArray(v.map { "#%02X%02X%02X".format(it[0], it[1], it[2]) }))
        return ok().put("enabled", app.config.enabled).put("running", e.running).put("status", e.status)
            .put("ha", app.ha.status).put("ha_connected", app.ha.connected)
            .put("preview", JSONObject(e.preview as Map<*, *>)).put("sides", sides)
    }

    /** Lampy z HA, które umieją kolor. */
    private fun lights(): JSONArray {
        val out = JSONArray()
        for ((id, s) in app.ha.states.entries.sortedBy { it.key }) {
            if (!id.startsWith("light.")) continue
            val a = s.optJSONObject("attributes") ?: JSONObject()
            val modes = a.optJSONArray("supported_color_modes")?.toString() ?: ""
            val color = listOf("rgb", "hs", "xy", "rgbw", "rgbww").any { modes.contains("\"$it\"") }
            out.put(JSONObject().put("entity_id", id).put("name", a.optString("friendly_name", id))
                .put("color", color).put("state", s.optString("state")))
        }
        return out
    }

    private fun readBody(session: IHTTPSession): String {
        val len = session.headers["content-length"]?.trim()?.toIntOrNull() ?: 0
        if (len <= 0) return ""
        val buf = ByteArray(len)
        var read = 0
        while (read < len) {
            val r = session.inputStream.read(buf, read, len - read)
            if (r < 0) break
            read += r
        }
        return String(buf, 0, read, Charsets.UTF_8)
    }

    private fun json(o: JSONObject, status: Response.Status = Response.Status.OK): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", o.toString())
            .also { it.addHeader("Access-Control-Allow-Origin", "*") }
}
