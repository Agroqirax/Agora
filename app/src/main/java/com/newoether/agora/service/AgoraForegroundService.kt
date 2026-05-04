package com.newoether.agora.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.newoether.agora.MainActivity
import com.newoether.agora.R

class AgoraForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "agora_generation"
        const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            val intent = Intent(context, AgoraForegroundService::class.java)
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, AgoraForegroundService::class.java)
            context.stopService(intent)
        }

        fun createChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Generation",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while Agora is generating a response"
                setShowBadge(false)
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Agora")
            .setContentText("Generating response…")
            .setSmallIcon(R.drawable.neurology_24)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
