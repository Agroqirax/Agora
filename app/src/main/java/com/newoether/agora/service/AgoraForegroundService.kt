package com.newoether.agora.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.newoether.agora.MainActivity
import com.newoether.agora.R

class AgoraForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "agora_generation"
        const val NOTIFICATION_ID = 1
        private var instance: AgoraForegroundService? = null

        fun start(context: Context) {
            val intent = Intent(context, AgoraForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun updateText(text: String) {
            instance?.updateNotificationText(text)
        }

        fun stop(context: Context) {
            val intent = Intent(context, AgoraForegroundService::class.java)
            context.stopService(intent)
        }

        fun createChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Generation",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Hidden ongoing notification while Agora is generating"
                setShowBadge(false)
                setSound(null, null)
            }
            manager.createNotificationChannel(channel)
        }

        fun showCompletionNotification(context: Context, responseText: String) {
            val channel = NotificationChannel(
                "agora_completed",
                "Response Ready",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Shown when a response finishes generating"
                setShowBadge(true)
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)

            val pendingIntent = PendingIntent.getActivity(
                context, 1,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val preview = if (responseText.length > 200) responseText.take(200) + "…" else responseText
            val notification = Notification.Builder(context, "agora_completed")
                .setContentTitle(context.getString(R.string.agora_responded))
                .setContentText(preview)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setStyle(Notification.BigTextStyle().bigText(preview))
                .build()

            manager.notify(2, notification)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Generating response…")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    private fun updateNotificationText(text: String) {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
