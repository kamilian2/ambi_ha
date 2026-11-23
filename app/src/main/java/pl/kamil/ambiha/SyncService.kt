package pl.kamil.ambiha

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/** Usługa w tle – trzyma aplikację przy życiu, żeby synchronizacja i strona ustawień działały cały czas. */
class SyncService : Service() {
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(1, notification())
        App.instance.startServer()
        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ambiha:sync").apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        App.instance.startServer()
        return START_STICKY
    }

    override fun onDestroy() {
        try { wake?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun notification(): Notification {
        val ch = "ambiha"
        val b = if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(ch, "Ambi HA", NotificationManager.IMPORTANCE_MIN))
            Notification.Builder(this, ch)
        } else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle("Ambi HA").setContentText("Synchronizacja lamp z Ambilight")
            .setSmallIcon(R.drawable.ic_app).setOngoing(true).build()
    }
}
