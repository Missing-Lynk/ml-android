package at.websium.ml

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * Keeps the broadcast alive while the app is not on screen.
 *
 * A backgrounded app has its network torn down, which takes the RTSP session and the RTMP
 * upload together and leaves the app reconnecting for as long as it stays off screen. A
 * foreground service is what exempts the process from that, and the notification is the price
 * Android charges for it.
 *
 * The service holds no pipeline. The player stays with the activity, which survives the screen
 * going off; what it could not survive was the process being treated as background. Starting and
 * stopping this alongside the restream is therefore all that is needed.
 *
 * The Wi-Fi lock is separate from the service and just as load-bearing: Wi-Fi power saving parks
 * the radio when the screen goes off, which an upload notices as a stall long before any timeout
 * fires.
 */
class RestreamService : Service() {

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val destination = intent?.getStringExtra(EXTRA_DESTINATION)
        goForeground(buildNotification(destination))
        acquireLocks()
        /*
         * Not sticky: the restream belongs to a session the activity owns, and a service restarted
         * on its own would have nothing to keep alive.
         */
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseLocks()
        super.onDestroy()
    }

    private fun goForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(destination: String?): Notification {
        createChannel()

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_stream)
            .setContentTitle(getString(R.string.stream_notification_title))
            .setContentText(destination ?: getString(R.string.stream_notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.stream_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun acquireLocks() {
        if (wifiLock != null) {
            return
        }

        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wifi.createWifiLock(mode, WIFI_LOCK_TAG).apply { acquire() }

        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    companion object {
        private const val CHANNEL_ID = "restream"
        private const val NOTIFICATION_ID = 1
        private const val WIFI_LOCK_TAG = "MissingLynk:restream"
        private const val WAKE_LOCK_TAG = "MissingLynk:restream"
        private const val EXTRA_DESTINATION = "destination"

        /**
         * Bring the service up for a broadcast to [destination], which must already be redacted:
         * it is shown in a notification, and a stream key must never reach one.
         */
        fun start(context: Context, destination: String) {
            val intent = Intent(context, RestreamService::class.java)
                .putExtra(EXTRA_DESTINATION, destination)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RestreamService::class.java))
        }
    }
}
