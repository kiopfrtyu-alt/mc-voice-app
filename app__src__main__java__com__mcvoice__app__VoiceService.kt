package com.mcvoice.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * บริการเบื้องหน้าชนิดไมค์ (foreground service, type = microphone)
 * ทำให้ Android ไม่ตัดไมค์ตอนผู้เล่นสลับไปเล่น Minecraft
 * แถบแจ้งเตือนมีปุ่ม "ปิด/เปิดไมค์" และ "ปิดแอป"
 */
class VoiceService : Service() {

    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_MUTE -> {
                // กดปุ่มไมค์ในหน้าเว็บ (สลับเปิด/ปิด)
                MainActivity.instance?.runJs("(function(){var b=document.getElementById('mic');if(b){b.click()}})()")
                return START_NOT_STICKY
            }
            ACTION_QUIT -> {
                MainActivity.instance?.finishAndRemoveTask()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        startInForeground()
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Minecraft Voice", NotificationManager.IMPORTANCE_LOW)
        )

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val muteIntent = PendingIntent.getService(
            this, 1,
            Intent(this, VoiceService::class.java).setAction(ACTION_MUTE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val quitIntent = PendingIntent.getService(
            this, 2,
            Intent(this, VoiceService::class.java).setAction(ACTION_QUIT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        @Suppress("DEPRECATION")
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Minecraft Voice กำลังทำงาน")
            .setContentText("ไมค์ทำงานเบื้องหลัง แตะเพื่อกลับมาที่แอป")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "ปิด/เปิดไมค์", muteIntent)
            .addAction(0, "ปิดแอป", quitIntent)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }

        if (wake == null) {
            val pm = getSystemService(PowerManager::class.java)
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mcvoice:service").apply {
                acquire(6 * 60 * 60 * 1000L)
            }
        }
    }

    override fun onDestroy() {
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "voice"
        private const val NOTIF_ID = 1
        const val ACTION_MUTE = "com.mcvoice.app.MUTE"
        const val ACTION_QUIT = "com.mcvoice.app.QUIT"
    }
}
