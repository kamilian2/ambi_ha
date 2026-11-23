package pl.kamil.ambiha

import android.graphics.Color
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Pętla synchronizacji: kolory Ambilight z telewizora -> lampy w Home Assistant i taśmy WLED.
 */
class Engine(private val cfg: Config, private val tv: JointSpace, private val ha: HaClient) {

    companion object {
        val ZONES = listOf(
            "all" to "Cały ekran (średnia)", "left" to "Lewa krawędź", "right" to "Prawa krawędź",
            "top" to "Górna krawędź", "bottom" to "Dolna krawędź",
            "top_left" to "Lewy górny róg", "top_right" to "Prawy górny róg",
            "left_half" to "Lewa połowa", "right_half" to "Prawa połowa"
        )
        private const val BACKUP_SCENE = "ambi_ha_kopia"
    }

    private val tag = "AmbiHa"
    private val exec = Executors.newSingleThreadScheduledExecutor()
    private val udp: DatagramSocket by lazy { DatagramSocket() }

    @Volatile var status = "Wyłączona"
        private set
    @Volatile var running = false
        private set
    @Volatile var fpsReal = 0.0
        private set
    /** Ostatnie kolory stref (do podglądu): strefa -> #RRGGBB. */
    @Volatile var preview: Map<String, String> = emptyMap()
        private set
    @Volatile var sides: Map<String, List<IntArray>> = emptyMap()
        private set

    private var lastTick = 0L
    private var frames = 0
    private var fpsWindow = 0L
    private var lastPowerCheck = 0L
    private var tvOn = true
    private var wasActive = false

    // stan każdej lampy: wygładzony kolor, ostatnio wysłany, czas
    private class LampState(var r: Float = 0f, var g: Float = 0f, var b: Float = 0f,
                            var sentRgb: IntArray? = null, var sentBri: Int = -1, var sentAt: Long = 0, var off: Boolean = false) {
        /** Polecenie wysłane, HA jeszcze nie potwierdził – nie wysyłamy kolejnego (bez kolejki = bez opóźnienia). */
        @Volatile var inFlight = false
    }
    private val lampStates = HashMap<String, LampState>()
    private val wledSmooth = HashMap<String, FloatArray>()

    fun start() {
        exec.scheduleWithFixedDelay({ tick() }, 500, 20, TimeUnit.MILLISECONDS)
    }

    private fun tick() {
        try {
            val active = cfg.enabled
            if (!active) {
                if (wasActive) stopSync()
                status = "Wyłączona"; running = false; return
            }
            val now = System.currentTimeMillis()
            if (now - lastTick < 1000L / cfg.fps) return
            lastTick = now

            // telewizor w czuwaniu -> przerwa
            if (cfg.standbyStop && now - lastPowerCheck > 5000) {
                lastPowerCheck = now
                tvOn = tv.powerOn() != false
            }
            if (!tvOn) {
                if (wasActive) stopSync()
                status = "Telewizor w czuwaniu – przerwa"; running = false; return
            }
            if (!wasActive) startSync()

            val s = tv.ambilight()
            sides = s
            val zones = HashMap<String, String>()
            for ((z, _) in ZONES) zoneColor(s, z)?.let { zones[z] = hex(it) }
            preview = zones

            sendLamps(s, now)
            sendWled(s)

            frames++
            if (now - fpsWindow >= 2000) {
                fpsReal = frames * 1000.0 / (now - fpsWindow).coerceAtLeast(1)
                frames = 0; fpsWindow = now
            }
            running = true
            status = "Działa – %.1f klatek/s".format(fpsReal) +
                (if (!ha.connected && cfg.lamps.isNotEmpty()) " · brak połączenia z HA" else "")
        } catch (e: Exception) {
            running = false
            status = "Błąd: " + (e.message ?: e.javaClass.simpleName)
            Log.w(tag, "tick: $e")
            try { Thread.sleep(1500) } catch (_: Exception) {}
        }
    }

    // ---------------- strefy ----------------

    private fun avg(list: List<IntArray>): IntArray? {
        if (list.isEmpty()) return null
        var r = 0; var g = 0; var b = 0
        for (c in list) { r += c[0]; g += c[1]; b += c[2] }
        return intArrayOf(r / list.size, g / list.size, b / list.size)
    }

    private fun part(list: List<IntArray>?, from: Float, to: Float): List<IntArray> {
        if (list.isNullOrEmpty()) return emptyList()
        val a = (list.size * from).toInt().coerceIn(0, list.size - 1)
        val b = (list.size * to).toInt().coerceIn(a + 1, list.size)
        return list.subList(a, b)
    }

    fun zoneColor(s: Map<String, List<IntArray>>, zone: String): IntArray? = when (zone) {
        "left", "right", "top", "bottom" -> avg(s[zone] ?: emptyList()) ?: avg(s.values.flatten())
        "top_left" -> avg(part(s["top"], 0f, 0.3f) + (s["left"] ?: emptyList()))
        "top_right" -> avg(part(s["top"], 0.7f, 1f) + (s["right"] ?: emptyList()))
        "left_half" -> avg(part(s["top"], 0f, 0.5f) + (s["left"] ?: emptyList()) + part(s["bottom"], 0.5f, 1f))
        "right_half" -> avg(part(s["top"], 0.5f, 1f) + (s["right"] ?: emptyList()) + part(s["bottom"], 0f, 0.5f))
        else -> avg(s.values.flatten())
    }

    /** Nasycenie i jasność z ustawień. */
    private fun process(c: IntArray, extraBri: Int = 100): FloatArray {
        val hsv = FloatArray(3)
        Color.RGBToHSV(c[0], c[1], c[2], hsv)
        hsv[1] = (hsv[1] * cfg.saturation / 100f).coerceIn(0f, 1f)
        hsv[2] = (hsv[2] * cfg.brightness / 100f * extraBri / 100f).coerceIn(0f, 1f)
        val out = Color.HSVToColor(hsv)
        return floatArrayOf(Color.red(out).toFloat(), Color.green(out).toFloat(), Color.blue(out).toFloat())
    }

    private fun hex(c: IntArray) = "#%02X%02X%02X".format(c[0].coerceIn(0, 255), c[1].coerceIn(0, 255), c[2].coerceIn(0, 255))

    // ---------------- lampy Home Assistanta ----------------

    private fun sendLamps(s: Map<String, List<IntArray>>, now: Long) {
        if (!ha.connected) return
        val k = cfg.smoothing / 100f
        val transition = cfg.transition / 1000.0
        val minBri = cfg.minBrightness
        for (lamp in cfg.lamps) {
            if (!lamp.optBoolean("enabled", true)) continue
            val id = lamp.optString("entity")
            if (id.isBlank()) continue
            val raw = zoneColor(s, lamp.optString("zone", "all")) ?: continue
            val c = process(raw, lamp.optInt("brightness", 100))
            val st = lampStates.getOrPut(id) { LampState(c[0], c[1], c[2]) }
            st.r = st.r * k + c[0] * (1 - k); st.g = st.g * k + c[1] * (1 - k); st.b = st.b * k + c[2] * (1 - k)

            val hz = lamp.optDouble("max_hz", 4.0).coerceIn(0.5, 20.0)
            if (now - st.sentAt < 1000 / hz) continue
            // poprzednie polecenie jeszcze w drodze -> poczekaj, potem wyślij od razu najnowszy kolor
            if (st.inFlight && now - st.sentAt < 2000) continue

            val mx = maxOf(st.r, st.g, st.b)
            val bri = (mx / 255f * 100).toInt()          // jasność w %
            if (bri < minBri) {
                if (!st.off) {
                    st.inFlight = true
                    ha.callService("light", "turn_off", id, JSONObject().put("transition", transition)) { _, _ -> st.inFlight = false }
                    st.off = true; st.sentAt = now; st.sentBri = 0
                }
                continue
            }
            // kolor w pełnej jasności + osobno jasność (żarówki tak lepiej odwzorowują barwę)
            val rgb = intArrayOf((st.r / mx * 255).toInt(), (st.g / mx * 255).toInt(), (st.b / mx * 255).toInt())
            val prev = st.sentRgb
            val diff = if (prev == null) 999 else Math.abs(prev[0] - rgb[0]) + Math.abs(prev[1] - rgb[1]) + Math.abs(prev[2] - rgb[2])
            if (!st.off && diff < 12 && Math.abs(st.sentBri - bri) < 3 && now - st.sentAt < 10000) continue

            val data = JSONObject().put("rgb_color", JSONArray(rgb.toList()))
            if (!cfg.fastMode) data.put("brightness_pct", bri.coerceIn(1, 100))
            if (transition > 0) data.put("transition", transition)
            st.inFlight = true
            ha.callService("light", "turn_on", id, data) { _, _ -> st.inFlight = false }
            st.sentRgb = rgb; st.sentBri = bri; st.sentAt = now; st.off = false
        }
    }

    private fun startSync() {
        wasActive = true
        lampStates.clear()
        // zapamiętaj stan lamp, żeby przywrócić go po zakończeniu
        val ids = cfg.lamps.filter { it.optBoolean("enabled", true) }.map { it.optString("entity") }.filter { it.isNotBlank() }
        if (cfg.restore && ids.isNotEmpty() && ha.connected) {
            ha.callService("scene", "create", null, JSONObject().put("scene_id", BACKUP_SCENE)
                .put("snapshot_entities", JSONArray(ids)))
        }
    }

    private fun stopSync() {
        wasActive = false
        if (cfg.restore && cfg.lamps.isNotEmpty() && ha.connected) {
            ha.callService("scene", "turn_on", "scene.$BACKUP_SCENE", JSONObject().put("transition", 1))
        }
        // WLED wraca sam do swojego efektu po kilku sekundach bez danych
        preview = emptyMap()
    }

    // ---------------- WLED (UDP, protokół DNRGB, port 21324) ----------------

    private fun sendWled(s: Map<String, List<IntArray>>) {
        val k = cfg.smoothing / 100f * 0.6f // taśmy reagują szybciej – lżejsze wygładzanie
        for (w in cfg.wled) {
            if (!w.optBoolean("enabled", true)) continue
            val host = w.optString("host").trim()
            if (host.isEmpty()) continue
            val colors: List<IntArray> = if (w.optString("mode", "zone") == "around") around(s, w)
            else {
                val n = w.optInt("leds", 30).coerceIn(1, 1500)
                val c = zoneColor(s, w.optString("zone", "all")) ?: intArrayOf(0, 0, 0)
                List(n) { c }
            }
            val bri = w.optInt("brightness", 100)
            val prev = wledSmooth[host]?.takeIf { it.size == colors.size * 3 } ?: FloatArray(colors.size * 3)
            val bytes = ByteArray(colors.size * 3)
            colors.forEachIndexed { i, c ->
                val p = process(c, bri)
                for (j in 0..2) {
                    prev[i * 3 + j] = prev[i * 3 + j] * k + p[j] * (1 - k)
                    bytes[i * 3 + j] = prev[i * 3 + j].toInt().coerceIn(0, 255).toByte()
                }
            }
            wledSmooth[host] = prev
            try {
                val addr = InetAddress.getByName(host)
                var start = 0
                while (start < colors.size) {
                    val count = minOf(489, colors.size - start)
                    val pkt = ByteArray(4 + count * 3)
                    pkt[0] = 4; pkt[1] = 2 // DNRGB, po 2 s bez danych WLED wraca do swojego efektu
                    pkt[2] = (start shr 8).toByte(); pkt[3] = (start and 0xFF).toByte()
                    System.arraycopy(bytes, start * 3, pkt, 4, count * 3)
                    udp.send(DatagramPacket(pkt, pkt.size, addr, w.optInt("port", 21324)))
                    start += count
                }
            } catch (e: Exception) {
                Log.w(tag, "WLED $host: $e")
            }
        }
    }

    /** Taśma dookoła ekranu: kolory krawędzi rozłożone na diody, od wybranego rogu, zgodnie z ruchem wskazówek zegara. */
    private fun around(s: Map<String, List<IntArray>>, w: JSONObject): List<IntArray> {
        fun sample(side: String, n: Int, reverse: Boolean): List<IntArray> {
            val src = s[side]?.takeIf { it.isNotEmpty() } ?: listOf(zoneColor(s, side) ?: intArrayOf(0, 0, 0))
            return List(n) { i ->
                val t = if (n == 1) 0.5f else i.toFloat() / (n - 1)
                val pos = (if (reverse) 1 - t else t) * (src.size - 1)
                val a = src[pos.toInt().coerceIn(0, src.size - 1)]
                val b = src[(pos.toInt() + 1).coerceIn(0, src.size - 1)]
                val f = pos - pos.toInt()
                intArrayOf((a[0] + (b[0] - a[0]) * f).toInt(), (a[1] + (b[1] - a[1]) * f).toInt(), (a[2] + (b[2] - a[2]) * f).toInt())
            }
        }
        val flip = w.optBoolean("flip", false) // odwraca kierunek próbkowania krawędzi telewizora
        // kolejność zgodnie z ruchem wskazówek zegara, zaczynając od lewego dolnego rogu
        val segs = listOf(
            sample("left", w.optInt("left", 0), !flip),   // z dołu do góry
            sample("top", w.optInt("top", 0), flip),      // od lewej do prawej
            sample("right", w.optInt("right", 0), flip),  // z góry na dół
            sample("bottom", w.optInt("bottom", 0), !flip) // od prawej do lewej
        )
        val order = when (w.optString("start", "bottom_left")) {
            "top_left" -> listOf(1, 2, 3, 0)
            "top_right" -> listOf(2, 3, 0, 1)
            "bottom_right" -> listOf(3, 0, 1, 2)
            else -> listOf(0, 1, 2, 3)
        }
        var list = order.flatMap { segs[it] }
        if (!w.optBoolean("clockwise", true)) list = list.reversed()
        return list
    }

    fun shutdown() { exec.shutdownNow() }
}
