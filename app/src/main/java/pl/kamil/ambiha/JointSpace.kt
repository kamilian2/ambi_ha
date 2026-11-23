package pl.kamil.ambiha

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * API telewizora Philips (JointSpace v6): kolory Ambilight liczone przez telewizor z obrazu
 * (działa dla TV, HDMI i aplikacji). Nowsze telewizory wymagają jednorazowego parowania (PIN na ekranie).
 */
class JointSpace(private val cfg: Config) {

    companion object {
        /** Klucz używany przez aplikacje Philips do podpisu przy parowaniu (publicznie znany, m.in. z biblioteki HA). */
        private const val SHARED_KEY = "ZmVay1EQVFOaZhwQ4Kv81ypLAZNczV9sG4KkseXWn1NEk6cXmPKO/MCa9sryslvLCFMnNe4Z4CPXzToowvhHvA=="
        private val JSON = "application/json".toMediaType()
    }

    // telewizor ma własny, niepodpisany certyfikat – łączymy się tylko z nim, w sieci lokalnej
    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {}
        override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }
    private val http: OkHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustAll), SecureRandom()) }.socketFactory, trustAll)
        .hostnameVerifier { _, _ -> true }
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    // ---------- Digest auth ----------
    private var realm = ""
    private var nonce = ""
    private var qop = ""
    private var opaque = ""
    private var nc = 0

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun digestHeader(method: String, uri: String, user: String, pass: String): String {
        nc++
        val ncs = "%08x".format(nc)
        val cnonce = md5(System.nanoTime().toString()).substring(0, 16)
        val ha1 = md5("$user:$realm:$pass")
        val ha2 = md5("$method:$uri")
        val useQop = qop.split(',').map { it.trim() }.contains("auth")
        val resp = if (useQop) md5("$ha1:$nonce:$ncs:$cnonce:auth:$ha2") else md5("$ha1:$nonce:$ha2")
        val sb = StringBuilder("Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", response=\"$resp\", algorithm=MD5")
        if (useQop) sb.append(", qop=auth, nc=$ncs, cnonce=\"$cnonce\"")
        if (opaque.isNotEmpty()) sb.append(", opaque=\"$opaque\"")
        return sb.toString()
    }

    private fun parseChallenge(h: String) {
        fun v(k: String) = Regex("$k=\"?([^\",]*)\"?").find(h)?.groupValues?.get(1) ?: ""
        realm = v("realm"); nonce = v("nonce"); qop = v("qop"); opaque = v("opaque"); nc = 0
    }

    /** Zapytanie do TV. user/pass = dane z parowania (digest), null = bez logowania. */
    private fun call(method: String, url: String, body: JSONObject?, user: String?, pass: String?): Pair<Int, String> {
        val uri = url.substringAfter("://").let { it.substring(it.indexOf('/')) }
        fun build(auth: String?): Request {
            val b = Request.Builder().url(url)
            if (auth != null) b.header("Authorization", auth)
            if (method == "POST") b.post((body ?: JSONObject()).toString().toRequestBody(JSON)) else b.get()
            return b.build()
        }
        var auth = if (user != null && nonce.isNotEmpty()) digestHeader(method, uri, user, pass ?: "") else null
        http.newCall(build(auth)).execute().use { r ->
            if (r.code != 401 || user == null) return r.code to (r.body?.string() ?: "")
            parseChallenge(r.header("WWW-Authenticate") ?: "")
        }
        auth = digestHeader(method, uri, user, pass ?: "")
        http.newCall(build(auth)).execute().use { r -> return r.code to (r.body?.string() ?: "") }
    }

    private fun host() = cfg.tvHost

    /** Pobiera kolory Ambilight. Zwraca mapę strona -> lista kolorów (r,g,b). */
    fun ambilight(): Map<String, List<IntArray>> {
        val (code, text) = get("/6/ambilight/processed")
        if (code != 200) throw IllegalStateException("TV odpowiada kodem $code")
        val layer = JSONObject(text).optJSONObject("layer1") ?: throw IllegalStateException("brak danych Ambilight")
        val out = HashMap<String, List<IntArray>>()
        for (side in listOf("left", "top", "right", "bottom")) {
            val o = layer.optJSONObject(side) ?: continue
            val keys = o.keys().asSequence().mapNotNull { it.toIntOrNull() }.sorted().toList()
            out[side] = keys.map { k ->
                val c = o.getJSONObject(k.toString())
                intArrayOf(c.optInt("r"), c.optInt("g"), c.optInt("b"))
            }
        }
        return out
    }

    /** Czy telewizor jest włączony (a nie w czuwaniu). Null = nie wiadomo. */
    fun powerOn(): Boolean? = try {
        val (code, text) = get("/6/powerstate")
        if (code == 200) JSONObject(text).optString("powerstate").equals("On", true) else null
    } catch (e: Exception) { null }

    /** GET: najpierw HTTPS 1926 z parowaniem, a gdy nie sparowano – HTTP 1925 bez logowania. */
    private fun get(path: String): Pair<Int, String> {
        val user = cfg.tvUser
        return if (user.isNotEmpty()) call("GET", "https://${host()}:1926$path", null, user, cfg.tvPass)
        else call("GET", "http://${host()}:1925$path", null, null, null)
    }

    // ---------- parowanie ----------

    private var pairDevice: JSONObject? = null
    private var pairAuthKey = ""
    private var pairTimestamp = 0L

    private fun device(id: String) = JSONObject()
        .put("device_name", "ambi_ha").put("device_os", "Android").put("app_name", "Ambi HA")
        .put("app_id", "app.id").put("type", "native").put("id", id)

    /** Krok 1: prośba o parowanie – telewizor pokazuje PIN. */
    fun pairRequest(): String {
        val id = (1..16).map { "abcdefghijklmnopqrstuvwxyz0123456789"[SecureRandom().nextInt(36)] }.joinToString("")
        val dev = device(id)
        val body = JSONObject().put("scope", JSONArray(listOf("read", "write", "control"))).put("device", dev)
        val (code, text) = call("POST", "https://${host()}:1926/6/pair/request", body, null, null)
        val j = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
        if (code != 200 || j.optString("error_id") != "SUCCESS") throw IllegalStateException("TV odrzucił parowanie (kod $code): $text")
        pairDevice = dev
        pairAuthKey = j.optString("auth_key")
        pairTimestamp = j.optLong("timestamp")
        return "Na ekranie telewizora pojawił się PIN – wpisz go i kliknij „Potwierdź PIN”."
    }

    /** Krok 2: potwierdzenie PIN-em. Zapisuje dane logowania w ustawieniach. */
    fun pairGrant(pin: String): String {
        val dev = pairDevice ?: throw IllegalStateException("Najpierw kliknij „Paruj z telewizorem”")
        val key = Base64.decode(SHARED_KEY, Base64.DEFAULT)
        val msg = (pairTimestamp.toString() + pin.trim()).toByteArray()
        val raw = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }.doFinal(msg)
        // dwa warianty podpisu spotykane w różnych wersjach oprogramowania TV
        val signatures = listOf(
            Base64.encodeToString(raw, Base64.NO_WRAP),
            Base64.encodeToString(raw.joinToString("") { "%02x".format(it) }.toByteArray(), Base64.NO_WRAP)
        )
        var last = ""
        for (sig in signatures) {
            val body = JSONObject()
                .put("auth", JSONObject().put("auth_AppId", "1").put("pin", pin.trim())
                    .put("auth_timestamp", pairTimestamp).put("auth_signature", sig))
                .put("device", dev)
            nonce = ""
            val (code, text) = call("POST", "https://${host()}:1926/6/pair/grant", body, dev.getString("id"), pairAuthKey)
            last = "kod $code: $text"
            if (code == 200) {
                cfg.update(JSONObject().put("tv_user", dev.getString("id")).put("tv_pass", pairAuthKey))
                nonce = ""
                return "Sparowano z telewizorem."
            }
        }
        throw IllegalStateException("Zły PIN albo minął czas ($last)")
    }

    /** Test połączenia z TV: opis wyniku. */
    fun test(): String {
        val a = ambilight()
        return "OK – Ambilight: " + listOf("left", "top", "right", "bottom").joinToString(", ") { "${it} ${a[it]?.size ?: 0}" }
    }
}
