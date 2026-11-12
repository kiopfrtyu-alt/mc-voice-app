package com.mcvoice.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import org.json.JSONArray

/**
 * บริการเบื้องหน้าชนิดไมค์ (foreground service, type = microphone)
 * ทำให้ Android ไม่ตัดไมค์ตอนผู้เล่นสลับไปเล่น Minecraft
 * แถบแจ้งเตือนมีปุ่ม: ใส่รหัส (พิมพ์รหัส 6 หลักได้เลยโดยไม่ต้องออกจากเกม), ปิด/เปิดไมค์, ปิดแอป
 */
class VoiceService : Service() {

    private var wake: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var status: String = DEFAULT_STATUS

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_MUTE -> {
                // กดปุ่มไมค์ในหน้าเว็บ (สลับเปิด/ปิด)
                WebHost.runJs("(function(){var b=document.getElementById('mic');if(b){b.click()}})()")
                return START_NOT_STICKY
            }
            ACTION_QUIT -> {
                quitAll()
                MainActivity.instance?.finishAndRemoveTask()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CODE -> {
                val raw = intent?.let { RemoteInput.getResultsFromIntent(it) }?.getCharSequence(KEY_CODE)?.toString() ?: ""
                val code = raw.filter { it.isDigit() }
                if (code.length != 6) {
                    status = "รหัสต้องเป็นตัวเลข 6 หลัก ลองกด \"ใส่รหัส\" ใหม่"
                    refresh()
                    return START_NOT_STICKY
                }
                status = "กำลังเชื่อมต่อด้วยรหัส $code ..."
                refresh()
                // ใส่รหัสลงช่องในหน้าเว็บแล้วกดเข้าห้อง (code มีแต่ตัวเลข 6 หลัก ใส่ในสคริปต์ได้อย่างปลอดภัย)
                WebHost.runJs(
                    "(function(c){var e=document.getElementById('code'),b=document.getElementById('go');" +
                        "if(e&&b){e.value=c;b.disabled=false;b.click()}})('" + code + "')"
                )
                handler.postDelayed({ readStatus() }, 3500)
                handler.postDelayed({ readStatus() }, 9000)
                return START_NOT_STICKY
            }
        }
        startInForeground()
        return START_NOT_STICKY
    }

    /** อ่านข้อความสถานะจากหน้าเว็บ แล้วแสดงในแถบแจ้งเตือน */
    private fun readStatus() {
        WebHost.evalJs("(function(){var s=document.getElementById('st');return s?s.textContent:''})()") { v ->
            if (v == null) return@evalJs
            val t = try {
                JSONArray("[$v]").getString(0)
            } catch (e: Exception) {
                v
            }
            if (t.isNotBlank() && t != "null") {
                status = t
                refresh()
            }
        }
    }

    /** ปิดทุกอย่าง: ปุ่มลอย หน้าเว็บ หน้าต่างลอย */
    private fun quitAll() {
        Bubble.hide(applicationContext)
        WebHost.destroy(applicationContext)
    }

    /** ปัดแอปออกจากหน้ารายการแอปล่าสุด = ปิดแอปทั้งหมด */
    override fun onTaskRemoved(rootIntent: Intent?) {
        quitAll()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private fun channel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Demnit DumVc", NotificationManager.IMPORTANCE_LOW)
        )
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
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
        // ช่องพิมพ์ในแถบแจ้งเตือนต้องใช้ PendingIntent แบบ mutable (ระบบเติมข้อความที่พิมพ์ลงใน Intent)
        val codeIntent = PendingIntent.getService(
            this, 3,
            Intent(this, VoiceService::class.java).setAction(ACTION_CODE),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val remote = RemoteInput.Builder(KEY_CODE).setLabel("รหัส 6 หลักจากแชตเกม").build()

        val codeAction = Notification.Action.Builder(0, "ใส่รหัส", codeIntent)
            .addRemoteInput(remote)
            .build()

        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Demnit DumVc กำลังทำงาน")
            .setContentText(status)
            .setStyle(Notification.BigTextStyle().bigText(status))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(codeAction)
            .addAction(0, "ปิด/เปิดไมค์", muteIntent)
            .addAction(0, "ปิดแอป", quitIntent)
            .build()
    }

    private fun refresh() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun startInForeground() {
        channel()
        val n = buildNotification()
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
        handler.removeCallbacksAndMessages(null)
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "voice"
        private const val NOTIF_ID = 1
        private const val KEY_CODE = "pair_code"
        private const val DEFAULT_STATUS = "ไมค์ทำงานเบื้องหลัง กด \"ใส่รหัส\" เพื่อพิมพ์รหัสจากแชตเกม"
        const val ACTION_MUTE = "com.mcvoice.app.MUTE"
        const val ACTION_QUIT = "com.mcvoice.app.QUIT"
        const val ACTION_CODE = "com.mcvoice.app.CODE"
    }
}
