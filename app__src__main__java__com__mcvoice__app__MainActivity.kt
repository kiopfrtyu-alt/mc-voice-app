package com.mcvoice.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast

/**
 * หน้าต่างหลัก: ห่อหน้าเว็บของรีเลย์ไว้ใน WebView
 * ไม่เรียก WebView.onPause() ตอนสลับไปเล่นเกม และมี VoiceService (บริการเบื้องหน้าชนิดไมค์)
 * คอยให้ระบบ Android ไม่ตัดไมค์ของแอปนี้
 */
class MainActivity : Activity() {

    companion object {
        @Volatile
        var instance: MainActivity? = null
        private const val REQ_PERMS = 100
    }

    private lateinit var web: WebView
    private var loaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        web = WebView(this)
        setContentView(
            web,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.webViewClient = WebViewClient()
        // เก็บตัวประมวลผลหน้าเว็บให้สำคัญไว้แม้ไม่ได้อยู่หน้าจอ (ตอนสลับไปเล่นเกม) เสียงจะได้ไม่ถูกลดลำดับ/พัก
        if (Build.VERSION.SDK_INT >= 26) {
            web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val ourHost = Uri.parse(BuildConfig.START_URL).host
                    val fromUs = request.origin?.host == ourHost
                    val micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                    if (fromUs && micOk) {
                        request.grant(request.resources)
                    } else {
                        request.deny()
                    }
                }
            }
        }

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
        if (!loaded) {
            loaded = true
            web.loadUrl(BuildConfig.START_URL)
        }
    }

    /** ให้ VoiceService (ปุ่มในแถบแจ้งเตือน) สั่งสคริปต์ในหน้าเว็บได้ */
    fun runJs(js: String) {
        runOnUiThread { web.evaluateJavascript(js, null) }
    }

    /** รันสคริปต์แล้วรับผลลัพธ์กลับ (ผลลัพธ์เป็นสตริง JSON) */
    fun evalJs(js: String, cb: (String?) -> Unit) {
        runOnUiThread { web.evaluateJavascript(js) { cb(it) } }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) {
            web.goBack()
        } else {
            moveTaskToBack(true) // ไม่ปิดแอป ปล่อยให้ทำงานเบื้องหลัง
        }
    }

    override fun onDestroy() {
        instance = null
        stopService(Intent(this, VoiceService::class.java))
        web.destroy()
        super.onDestroy()
    }
}
