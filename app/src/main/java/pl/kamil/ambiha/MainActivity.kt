package pl.kamil.ambiha

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/** Ekran na telewizorze: włącz/wyłącz synchronizację, podgląd kolorów, adres strony ustawień. */
class MainActivity : Activity() {

    private val app get() = App.instance
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private val boxes = HashMap<String, View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashLogger(this)
        startSyncService(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(48f), dp(32f), dp(48f), dp(32f))
            setBackgroundColor(Color.parseColor("#0B1722"))
        }
        col.addView(text("Ambi HA", 34f, true, "#FFFFFF"))
        col.addView(text("Kolory Ambilight → lampy w Home Assistant i taśmy WLED", 18f, false, "#9FC3DB"))

        // podgląd: ekran z krawędziami
        val frame = FrameLayout(this)
        val w = dp(420f); val h = dp(236f); val t = dp(26f)
        fun edge(zone: String, lp: FrameLayout.LayoutParams) {
            val v = View(this).apply { background = GradientDrawable().apply { cornerRadius = dp(6f).toFloat(); setColor(Color.DKGRAY) } }
            frame.addView(v, lp); boxes[zone] = v
        }
        edge("top", FrameLayout.LayoutParams(w, t, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        edge("bottom", FrameLayout.LayoutParams(w, t, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        edge("left", FrameLayout.LayoutParams(t, h, Gravity.START or Gravity.CENTER_VERTICAL))
        edge("right", FrameLayout.LayoutParams(t, h, Gravity.END or Gravity.CENTER_VERTICAL))
        edge("all", FrameLayout.LayoutParams(w - 2 * t - dp(20f), h - 2 * t - dp(20f), Gravity.CENTER))
        col.addView(frame, LinearLayout.LayoutParams(w + 2 * t + dp(20f), h + 2 * t + dp(20f)).apply { topMargin = dp(20f) })

        toggle = Button(this).apply {
            isAllCaps = false; setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(30f), dp(14f), dp(30f), dp(14f)); setTextColor(Color.WHITE)
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                    cornerRadius = dp(12f).toFloat(); setColor(Color.parseColor("#1E6FA8")); setStroke(dp(3f), Color.WHITE)
                })
                addState(intArrayOf(), GradientDrawable().apply { cornerRadius = dp(12f).toFloat(); setColor(Color.parseColor("#24435C")) })
            }
            setOnClickListener { app.config.update(JSONObject().put("enabled", !app.config.enabled)); refresh() }
        }
        col.addView(toggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(20f) })

        status = text("", 18f, false, "#C8E6FA").apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.3f) }
        col.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(16f) })

        setContentView(col)
        toggle.requestFocus()
    }

    private fun text(t: String, sp: Float, bold: Boolean, color: String) = TextView(this).apply {
        text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(Color.parseColor(color))
        if (bold) typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
    }

    private val loop = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 300) }
    }

    override fun onResume() { super.onResume(); handler.post(loop) }
    override fun onPause() { handler.removeCallbacks(loop); super.onPause() }

    private fun refresh() {
        val on = app.config.enabled
        toggle.text = if (on) "■  Wyłącz synchronizację" else "▶  Włącz synchronizację"
        val p = app.engine.preview
        for ((zone, v) in boxes) {
            val c = p[zone]?.let { try { Color.parseColor(it) } catch (e: Exception) { null } } ?: Color.DKGRAY
            (v.background as GradientDrawable).setColor(c)
        }
        val ip = localIpAddresses().firstOrNull() ?: "IP_TELEWIZORA"
        val paired = if (app.config.tvUser.isNotEmpty()) "sparowano" else "niesparowano"
        status.text = "Synchronizacja: ${app.engine.status}\n" +
            "Telewizor: $paired · Home Assistant: ${app.ha.status}\n" +
            "Lampy: ${app.config.lamps.count { it.optBoolean("enabled", true) }} · WLED: ${app.config.wled.size}\n\n" +
            "Ustawienia: http://$ip:$API_PORT"
    }
}
