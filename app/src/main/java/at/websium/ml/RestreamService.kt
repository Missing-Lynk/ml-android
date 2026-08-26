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
        val label = intent?.getStringExtra(EXTRA_LABEL)
        val usesMicrophone = intent?.getBooleanExtra(EXTRA_MICROPHONE, false) == true
        goForeground(buildNotification(label), usesMicrophone)
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

    /**
     * The microphone type is claimed only while that source is in use. Declaring it on every
     * broadcast would need RECORD_AUDIO granted for any of them, and a silent track needs no
     * such permission.
     */
    private fun goForeground(notification: Notification, usesMicrophone: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification)
            return
        }

        /*
         * The microphone type arrived in API 30, a release after the types themselves. Android 10
         * has no while-in-use restriction on the microphone for a foreground service, so there is
         * nothing to claim there and the source works without it.
         */
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        if (usesMicrophone && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        startForeground(NOTIFICATION_ID, notification, type)
    }

    private fun buildNotification(label: String?): Notification {
        createChannel()

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        /*
         * Explicit by package rather than by class, because the receiver is registered by the
         * activity at runtime: the broadcast has to reach the machine, and the machine lives
         * with the activity rather than here.
         */
        val stop = PendingIntent.getBroadcast(
            this,
            1,
            Intent(ACTION_STOP).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_stream)
            .setContentTitle(getString(R.string.stream_notification_title))
            .setContentText(label ?: getString(R.string.stream_notification_text))
            .setContentIntent(open)
            .addAction(R.drawable.ic_stream_stop, getString(R.string.stream_stop), stop)
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
        /** the notification's Stop action; the activity listens for it */
        const val ACTION_STOP = "at.websium.ml.STOP_RESTREAM"

        private const val CHANNEL_ID = "restream"
        private const val NOTIFICATION_ID = 1
        private const val WIFI_LOCK_TAG = "MissingLynk:restream"
        private const val WAKE_LOCK_TAG = "MissingLynk:restream"
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_MICROPHONE = "microphone"

        /**
         * Bring the service up for a broadcast to the destination called [label]. The name rather
         * than the URL, because the notification is read by anyone looking at the phone and the
         * URL ends in a stream key.
         */
        fun start(context: Context, label: String, usesMicrophone: Boolean) {
            val intent = Intent(context, RestreamService::class.java)
                .putExtra(EXTRA_LABEL, label)
                .putExtra(EXTRA_MICROPHONE, usesMicrophone)
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
