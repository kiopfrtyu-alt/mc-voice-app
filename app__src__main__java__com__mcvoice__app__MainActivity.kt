package com.mcvoice.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast

/**
 * หน้าต่างหลัก: แสดง WebView ของรีเลย์ (ตัว WebView เป็นของ WebHost ไม่ใช่ของ Activity นี้)
 * ตอนออกจากหน้าจอ WebView จะถูกย้ายไปหน้าต่างลอย 1 พิกเซลเพื่อไม่ให้เสียงถูกพัก
 * มี VoiceService (บริการเบื้องหน้าชนิดไมค์) คอยให้ระบบ Android ไม่ตัดไมค์ของแอปนี้
 */
class MainActivity : Activity() {

    companion object {
        @Volatile
        var instance: MainActivity? = null
        private const val REQ_PERMS = 100
    }

    private lateinit var container: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        container = FrameLayout(this)
        setContentView(
            container,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        WebHost.attach(this, container)

        val need = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isEmpty()) {
            startEverything()
        } else {
            requestPermissions(need.toTypedArray(), REQ_PERMS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "ต้องอนุญาตไมค์ก่อน แอปถึงจะส่งเสียงได้ (ตั้งค่า > แอป > Demnit DumVc > สิทธิ์)", Toast.LENGTH_LONG).show()
        }
        startEverything()
    }

    private fun startEverything() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            try {
                startForegroundService(Intent(this, VoiceService::class.java))
            } catch (e: Exception) {
                Toast.makeText(this, "เริ่มบริการเบื้องหลังไม่ได้: " + e.message, Toast.LENGTH_LONG).show()
            }
        }
        WebHost.load()
        askOverlayIfNeeded()
    }

    /** ขอสิทธิ์ "แสดงทับแอปอื่น" (จำเป็นต่อการกันเสียงหลุด) ถามไม่เกิน 3 ครั้ง */
    private fun askOverlayIfNeeded() {
        if (Settings.canDrawOverlays(this)) return
        val prefs = getSharedPreferences("app", Context.MODE_PRIVATE)
        val n = prefs.getInt("overlay_asked", 0)
        if (n >= 3) return
        prefs.edit().putInt("overlay_asked", n + 1).apply()
        WebHost.askOverlay(applicationContext)
    }

    override fun onStart() {
        super.onStart()
        WebHost.attach(this, container)
    }

    override fun onResume() {
        super.onResume()
        Bubble.refresh(applicationContext)
    }

    override fun onStop() {
        // ออกจากหน้าจอ (สลับไปเกม/พับแอป): ย้าย WebView ไปหน้าต่างลอยเพื่อให้เสียงไม่ถูกพัก
        WebHost.toKeeper(this, container)
        super.onStop()
    }

    /** ให้ผู้เรียกเดิมใช้ต่อได้ (ตัวจริงอยู่ที่ WebHost) */
    fun runJs(js: String) = WebHost.runJs(js)

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val web = WebHost.get()
        if (web != null && web.canGoBack()) {
            web.goBack()
        } else {
            moveTaskToBack(true) // ไม่ปิดแอป ปล่อยให้ทำงานเบื้องหลัง
        }
    }

    override fun onDestroy() {
        instance = null
        if (isFinishing) {
            Bubble.hide(applicationContext)
            WebHost.destroy(applicationContext)
            stopService(Intent(this, VoiceService::class.java))
        }
        super.onDestroy()
    }
}
