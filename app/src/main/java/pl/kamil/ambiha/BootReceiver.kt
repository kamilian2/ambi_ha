package pl.kamil.ambiha

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Start po włączeniu telewizora. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try { startSyncService(context) } catch (_: Exception) {}
    }
}
