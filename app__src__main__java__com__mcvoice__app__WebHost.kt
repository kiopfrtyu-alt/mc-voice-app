package com.mcvoice.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast

/**
 * เจ้าของ WebView ตัวเดียวของแอป (อยู่รอดนอกหน้าจอ Activity)
 * - เปิดแอป: WebView อยู่ใน Activity ตามปกติ
 * - สลับไปเกม/พับแอป: ย้าย WebView ไปอยู่ในหน้าต่างลอยขนาด 1 พิกเซล เพื่อให้ Android/Chromium
 *   ยังมองว่าหน้าเว็บ "แสดงอยู่" ไม่พักการเล่นเสียงของหน้าเว็บ
 * ต้องได้รับสิทธิ์ "แสดงทับแอปอื่น" ถ้าไม่มีสิทธิ์ WebView จะอยู่ใน Activity เหมือนเดิม
 */
object WebHost {
    private val main = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var wrapper: MutableContextWrapper? = null
    private var keeper: FrameLayout? = null
    private var keeperShown = false
    private var loaded = false

    fun get(): WebView? = web

    @SuppressLint("SetJavaScriptEnabled")
    fun obtain(ctx: Context): WebView {
        web?.let { return it }
        val app = ctx.applicationContext
        val wr = MutableContextWrapper(app)
        wrapper = wr
        val w = WebView(wr)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.mediaPlaybackRequiresUserGesture = false
        w.webViewClient = WebViewClient()
        w.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                main.post {
                    val ourHost = Uri.parse(BuildConfig.START_URL).host
                    val fromUs = request.origin?.host == ourHost
                    val micOk = app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                    if (fromUs && micOk) {
                        request.grant(request.resources)
                    } else {
                        request.deny()
                    }
                }
            }
        }
        w.addJavascriptInterface(Bridge(app), "AndroidBubble")
        if (Build.VERSION.SDK_INT >= 26) {
            // เก็บตัวประมวลผลหน้าเว็บให้สำคัญไว้แม้ไม่ได้อยู่หน้าจอ
            w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        }
        web = w
        return w
    }

    fun load() {
        val w = web ?: return
        if (!loaded) {
            loaded = true
            w.loadUrl(BuildConfig.START_URL)
        }
    }

    /** แสดง WebView ใน Activity (เรียกตอนเปิดแอป/กลับเข้าแอป) */
    fun attach(activity: Activity, container: FrameLayout) {
        val w = obtain(activity)
        if (w.parent === container) return
        wrapper?.setBaseContext(activity)
        (w.parent as? ViewGroup)?.removeView(w)
        container.addView(
            w,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
    }

    /** ย้าย WebView ไปอยู่ในหน้าต่างลอย 1 พิกเซล (เรียกตอนแอปออกจากหน้าจอ) */
    fun toKeeper(activity: Activity, container: FrameLayout) {
        val w = web ?: return
        val app = activity.applicationContext
        if (!Settings.canDrawOverlays(app)) return
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val k = keeper ?: FrameLayout(app).also { keeper = it }
        if (!keeperShown) {
            val lp = WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.START
            try {
                wm.addView(k, lp)
                keeperShown = true
            } catch (e: Exception) {
                return // เปิดหน้าต่างลอยไม่ได้ ปล่อยให้ WebView อยู่ใน Activity ต่อ
            }
        }
        if (w.parent === k) return
        val dm = app.resources.displayMetrics
        (w.parent as? ViewGroup)?.removeView(w)
        wrapper?.setBaseContext(app)
        // ขนาดจริงของหน้าเว็บยังเท่าหน้าจอ (ถูกตัดให้เห็นแค่ 1 พิกเซล) หน้าเว็บจึงไม่ต้องจัดหน้าใหม่
        k.addView(w, FrameLayout.LayoutParams(dm.widthPixels, dm.heightPixels))
    }

    fun runJs(js: String) {
        main.post { web?.evaluateJavascript(js, null) }
    }

    fun evalJs(js: String, cb: (String?) -> Unit) {
        main.post {
            val w = web
            if (w == null) cb(null) else w.evaluateJavascript(js) { cb(it) }
        }
    }

    /** ปิดทุกอย่าง: หน้าต่างลอย + WebView */
    fun destroy(ctx: Context) {
        val app = ctx.applicationContext
        main.post {
            try {
                keeper?.let { (app.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it) }
            } catch (e: Exception) {
            }
            keeper = null
            keeperShown = false
            web?.let {
                (it.parent as? ViewGroup)?.removeView(it)
                try { it.destroy() } catch (e: Exception) {}
            }
            web = null
            wrapper = null
            loaded = false
        }
    }

    fun askOverlay(app: Context) {
        Toast.makeText(
            app,
            "เปิดสวิตช์ \"แสดงทับแอปอื่น\" ของ Demnit DumVc เพื่อให้เสียงไม่หลุดตอนเล่นเกม แล้วกลับมาที่แอป",
            Toast.LENGTH_LONG
        ).show()
        try {
            app.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + app.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
        }
    }

    /** สะพานให้หน้าเว็บสั่งปุ่มลอย (ใช้ได้เฉพาะหน้าของเราเอง) */
    class Bridge(private val app: Context) {
        @JavascriptInterface
        fun isEnabled(): Boolean = Bubble.enabled(app)

        @JavascriptInterface
        fun setEnabled(on: Boolean) {
            WebHost.main.post {
                Bubble.setEnabled(app, on)
                if (on && !Bubble.canDraw(app)) WebHost.askOverlay(app)
            }
        }

        @JavascriptInterface
        fun setState(s: String) {
            Bubble.setState(s)
        }
    }
}
