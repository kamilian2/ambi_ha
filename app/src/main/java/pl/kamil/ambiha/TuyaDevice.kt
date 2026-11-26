package pl.kamil.ambiha

import android.util.Log
import org.json.JSONObject
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Bezpośrednie sterowanie żarówką Tuya w sieci lokalnej (bez Home Assistanta i chmury).
 * Obsługuje protokoły 3.3, 3.4 i 3.5 (wybór automatyczny). Kolor wysyłany przez DP 28
 * („control_data” – tryb szybkiej zmiany koloru, ten sam co synchronizacja z muzyką)
 * albo DP 24 („colour_data”).
 */
class TuyaDevice(
    val ip: String,
    private val devId: String,
    localKey: String,
    private val wantVersion: String, // "auto", "3.3", "3.4", "3.5"
    private val dp: String,          // "28" albo "24"
) {
    private val tag = "AmbiTuya"
    private val key = localKey.trim().toByteArray(Charsets.UTF_8)
    private val rnd = SecureRandom()

    @Volatile var version: String = ""
        private set
    @Volatile var lastError: String = ""
        private set
    @Volatile private var socket: Socket? = null
    private var out: OutputStream? = null
    private var input: DataInputStream? = null
    private var sessionKey: ByteArray = key
    private var seq = 1
    private var nextAttempt = 0L
    private var colourModeSent = false

    val connected get() = socket?.isConnected == true && socket?.isClosed == false

    // ---------------- kryptografia ----------------

    private fun ecb(k: ByteArray, data: ByteArray, encrypt: Boolean, pad: Boolean): ByteArray {
        val c = Cipher.getInstance(if (pad) "AES/ECB/PKCS5Padding" else "AES/ECB/NoPadding")
        c.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"))
        return c.doFinal(data)
    }

    private fun gcmEncrypt(k: ByteArray, iv: ByteArray, aad: ByteArray?, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(128, iv))
        if (aad != null) c.updateAAD(aad)
        return c.doFinal(data) // szyfrogram + tag (16 B)
    }

    private fun gcmDecrypt(k: ByteArray, iv: ByteArray, aad: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(aad)
        return c.doFinal(data)
    }

    private fun hmac(k: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(k, "HmacSHA256")) }.doFinal(data)

    private fun crc(data: ByteArray): ByteArray {
        val c = CRC32(); c.update(data)
        return ByteBuffer.allocate(4).putInt(c.value.toInt()).array()
    }

    // ---------------- ramki ----------------

    /** Ramka 0x55AA (3.3 – CRC, 3.4 – HMAC). */
    private fun frame55(cmd: Int, payload: ByteArray, hmacKey: ByteArray?): ByteArray {
        val tailLen = if (hmacKey != null) 32 else 4
        val head = ByteBuffer.allocate(16).putInt(0x000055AA).putInt(seq++).putInt(cmd)
            .putInt(payload.size + tailLen + 4).array()
        val body = head + payload
        val tail = if (hmacKey != null) hmac(hmacKey, body) else crc(body)
        return body + tail + byteArrayOf(0, 0, 0xAA.toByte(), 0x55)
    }

    /** Ramka 0x6699 (3.5 – AES-GCM). */
    private fun frame66(cmd: Int, plain: ByteArray, k: ByteArray): ByteArray {
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val head = ByteBuffer.allocate(18).putInt(0x00006699).putShort(0).putInt(seq++).putInt(cmd)
            .putInt(12 + plain.size + 16).array()
        val enc = gcmEncrypt(k, iv, head.copyOfRange(4, 18), plain)
        return head + iv + enc + byteArrayOf(0, 0, 0x99.toByte(), 0x66)
    }

    /** Czyta jedną ramkę: (komenda, odszyfrowana treść – bez gwarancji formatu). */
    private fun readFrame(): Pair<Int, ByteArray> {
        val inp = input ?: throw IllegalStateException("brak połączenia")
        val prefix = inp.readInt()
        return when (prefix) {
            0x000055AA -> {
                inp.readInt() // seq
                val cmd = inp.readInt()
                val len = inp.readInt()
                val body = ByteArray(len); inp.readFully(body)
                val tailLen = if (version == "3.3") 4 else 32
                val data = body.copyOfRange(0, maxOf(0, len - tailLen - 4))
                cmd to data
            }
            0x00006699 -> {
                val rest = ByteArray(14); inp.readFully(rest) // 2 B zarezerwowane + seq + cmd + len
                val bb = ByteBuffer.wrap(rest)
                bb.short; bb.int
                val cmd = bb.int
                val len = bb.int
                val body = ByteArray(len + 4); inp.readFully(body)
                val iv = body.copyOfRange(0, 12)
                val enc = body.copyOfRange(12, len)
                val plain = gcmDecrypt(sessionKey, iv, rest, enc)
                cmd to plain
            }
            else -> throw IllegalStateException("nieznana ramka")
        }
    }

    // ---------------- połączenie ----------------

    private fun open() {
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(ip, 6668), 2500)
        s.soTimeout = 3000
        socket = s; out = s.getOutputStream(); input = DataInputStream(s.getInputStream())
        seq = 1
    }

    fun close() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null; out = null; input = null
        colourModeSent = false
    }

    /** Uzgodnienie klucza sesji (3.4 i 3.5). */
    private fun negotiate(v: String) {
        val localNonce = ByteArray(16).also { rnd.nextBytes(it) }
        sessionKey = key
        val o = out!!
        // krok 1: nasz nonce
        o.write(if (v == "3.5") frame66(3, localNonce, key) else frame55(3, ecb(key, localNonce, true, true), key))
        o.flush()
        // krok 2: nonce urządzenia
        var remote: ByteArray? = null
        for (attempt in 0 until 3) {
            val (cmd, data) = readFrame()
            if (cmd == 4) {
                val plain = if (v == "3.5") data else decryptLenient(data, key)
                if (plain.size >= 48) { remote = plain.copyOfRange(plain.size - 48, plain.size - 32); break }
            }
        }
        val rn = remote ?: throw IllegalStateException("urządzenie nie odpowiedziało na uzgodnienie klucza")
        // krok 3: potwierdzenie
        val finish = hmac(key, rn)
        o.write(if (v == "3.5") frame66(5, finish, key) else frame55(5, ecb(key, finish, true, true), key))
        o.flush()
        val x = ByteArray(16) { (localNonce[it].toInt() xor rn[it].toInt()).toByte() }
        sessionKey = if (v == "3.5") gcmEncrypt(key, localNonce.copyOfRange(0, 12), null, x).copyOfRange(0, 16)
        else ecb(key, x, true, false)
    }

    /** Odszyfrowanie odpowiedzi 3.4 – z kodem powrotu na początku albo bez. */
    private fun decryptLenient(data: ByteArray, k: ByteArray): ByteArray {
        for (skip in intArrayOf(4, 0)) {
            val d = if (data.size > skip) data.copyOfRange(skip, data.size) else continue
            if (d.size % 16 != 0) continue
            try { return ecb(k, d, false, true) } catch (_: Exception) {}
        }
        return ByteArray(0)
    }

    /** Łączy się i ustala wersję protokołu. */
    @Synchronized fun ensureConnected(): Boolean {
        if (connected) return true
        val now = System.currentTimeMillis()
        if (now < nextAttempt) return false
        nextAttempt = now + 3000
        val versions = if (wantVersion == "auto") listOf("3.5", "3.4", "3.3") else listOf(wantVersion)
        var err = ""
        for (v in versions) {
            try {
                open()
                version = v
                if (v != "3.3") negotiate(v)
                socket?.soTimeout = 0
                startReader()
                lastError = ""
                Log.i(tag, "$ip: połączono, protokół $v")
                return true
            } catch (e: Exception) {
                err = "$v: ${e.message ?: e.javaClass.simpleName}"
                close()
            }
        }
        version = ""
        lastError = "Nie można połączyć ($err)"
        return false
    }

    /** Wątek czytający odpowiedzi (żeby nie zapchać bufora); koniec strumienia = rozłączenie. */
    private fun startReader() {
        val s = socket ?: return
        Thread {
            try {
                val buf = ByteArray(1024)
                val inp = s.getInputStream()
                while (!s.isClosed && inp.read(buf) >= 0) { /* odpowiedzi nie są potrzebne */ }
            } catch (_: Exception) {}
            if (socket === s) close()
        }.apply { isDaemon = true }.start()
    }

    // ---------------- wysyłanie ----------------

    @Synchronized private fun sendDps(dps: JSONObject) {
        val o = out ?: throw IllegalStateException("brak połączenia")
        val t = System.currentTimeMillis() / 1000
        val packet = when (version) {
            "3.3" -> {
                val json = JSONObject().put("devId", devId).put("uid", devId).put("t", t.toString()).put("dps", dps)
                val enc = ecb(key, json.toString().toByteArray(), true, true)
                frame55(7, "3.3".toByteArray() + ByteArray(12) + enc, null)
            }
            "3.4" -> {
                val json = JSONObject().put("protocol", 5).put("t", t).put("data", JSONObject().put("dps", dps))
                val plain = "3.4".toByteArray() + ByteArray(12) + json.toString().toByteArray()
                frame55(13, ecb(sessionKey, plain, true, true), sessionKey)
            }
            else -> {
                val json = JSONObject().put("protocol", 5).put("t", t).put("data", JSONObject().put("dps", dps))
                frame66(13, "3.5".toByteArray() + ByteArray(12) + json.toString().toByteArray(), sessionKey)
            }
        }
        o.write(packet)
        o.flush()
    }

    private fun send(dps: JSONObject): Boolean {
        if (!ensureConnected()) return false
        return try {
            sendDps(dps); true
        } catch (e: Exception) {
            lastError = "Wysyłanie: ${e.message ?: e.javaClass.simpleName}"
            close(); false
        }
    }

    private fun hsvHex(r: Int, g: Int, b: Int): String {
        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV(r, g, b, hsv)
        val h = hsv[0].toInt().coerceIn(0, 360)
        val s = (hsv[1] * 1000).toInt().coerceIn(0, 1000)
        val v = (hsv[2] * 1000).toInt().coerceIn(10, 1000)
        return "%04x%04x%04x".format(h, s, v)
    }

    /** Kolor (0–255). smooth = żarówka sama płynnie przechodzi do koloru. */
    fun setColor(r: Int, g: Int, b: Int, smooth: Boolean): Boolean {
        if (!ensureConnected()) return false
        // po połączeniu raz: włącz żarówkę w trybie koloru
        if (!colourModeSent) {
            if (!send(JSONObject().put("20", true).put("21", "colour"))) return false
            colourModeSent = true
        }
        val hsv = hsvHex(r, g, b)
        val dps = if (dp == "24") JSONObject().put("24", hsv)
        else JSONObject().put("28", (if (smooth) "1" else "0") + hsv + "00000000")
        return send(dps)
    }

    /** Biel: jasność 1–100 %, barwa w kelwinach. */
    fun setWhite(brightnessPct: Int, kelvin: Int): Boolean {
        val bri = (brightnessPct * 10).coerceIn(10, 1000)
        val temp = ((kelvin - 2700) / (6500.0 - 2700) * 1000).toInt().coerceIn(0, 1000)
        return send(JSONObject().put("20", true).put("21", "white").put("22", bri).put("23", temp))
    }

    fun turnOff(): Boolean {
        colourModeSent = false // następny kolor najpierw włączy żarówkę
        return send(JSONObject().put("20", false))
    }
}
