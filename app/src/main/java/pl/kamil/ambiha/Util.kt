package pl.kamil.ambiha

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.TypedValue
import java.net.Inet4Address
import java.net.NetworkInterface

const val API_PORT = 5004

fun Context.dp(v: Float): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

fun localIpAddresses(): List<String> = try {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .mapNotNull { it.hostAddress }
} catch (e: Exception) { emptyList() }

fun startSyncService(ctx: Context) {
    val i = Intent(ctx, SyncService::class.java)
    if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
}

fun installCrashLogger(ctx: Context) {
    val prev = Thread.getDefaultUncaughtExceptionHandler()
    if (prev is CrashLogger) return
    Thread.setDefaultUncaughtExceptionHandler(CrashLogger(ctx.applicationContext, prev))
}

class CrashLogger(private val ctx: Context, private val prev: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(t: Thread, e: Throwable) {
        try {
            val sw = java.io.StringWriter(); e.printStackTrace(java.io.PrintWriter(sw))
            java.io.File(ctx.filesDir, "crash.txt").writeText(java.util.Date().toString() + "\n" + sw)
        } catch (_: Exception) {}
        prev?.uncaughtException(t, e)
    }
}

fun lastCrash(ctx: Context): String = try {
    java.io.File(ctx.filesDir, "crash.txt").takeIf { it.exists() }?.readText() ?: ""
} catch (e: Exception) { "" }
