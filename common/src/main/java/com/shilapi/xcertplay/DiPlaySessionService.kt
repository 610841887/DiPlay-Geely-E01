package com.shilapi.xcertplay

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.shilapi.xcertplay.host.R

/** Keeps an explicitly started connection alive when another car app is in the foreground. */
class DiPlaySessionService : Service() {
    private var androidProjection = false

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || intent?.action == ACTION_STOP_ANDROID) {
            if (androidProjection != (intent.action == ACTION_STOP_ANDROID)) return START_NOT_STICKY
            disconnect()
            stopSelf()
            return START_NOT_STICKY
        }
        androidProjection = intent?.action == ACTION_START_ANDROID
        val projection = AndroidProjectionSession.owner
        if (androidProjection != (projection != null)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "DiPlay connection", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val openIntent = Intent(this, projection?.javaClass ?: CarPlayHostActivity::class.java).apply {
            if (projection != null) addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        val open = PendingIntent.getActivity(this, 0, openIntent, pendingFlags)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, DiPlaySessionService::class.java)
                .setAction(if (projection != null) ACTION_STOP_ANDROID else ACTION_STOP), pendingFlags,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder
            .setSmallIcon(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) R.drawable.ic_diplay_notification else R.drawable.ic_carplay)
            .setContentTitle(if (projection == null) "DiPlay" else getString(projection.projectionTitle))
            .setContentText(if (projection == null) "CarPlay connection running" else getString(R.string.android_projection_connected))
            .setContentIntent(open).setOngoing(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            builder.addAction(Notification.Action.Builder(null, "Disconnect", stop).build())
        } else {
            @Suppress("DEPRECATION")
            builder.addAction(0, "Disconnect", stop)
        }
        val notification = builder.build()
        if (Build.VERSION.SDK_INT >= 29) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (Build.VERSION.SDK_INT >= 30 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(1, notification, types)
        } else startForeground(1, notification)
        return START_NOT_STICKY
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        disconnect()
        stopSelf()
    }

    private fun disconnect() {
        if (androidProjection) AndroidProjectionSession.owner?.finish()
        else CarPlayBackgroundSession.stop()
    }

    companion object {
        const val ACTION_STOP = "com.shihab.diplay.DISCONNECT"
        internal const val ACTION_START_ANDROID = "com.shihab.diplay.START_ANDROID_PROJECTION"
        private const val ACTION_STOP_ANDROID = "com.shihab.diplay.STOP_ANDROID_PROJECTION"
        private const val CHANNEL = "diplay_connection"
    }
}
