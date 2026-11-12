package com.mcvoice.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * ปุ่มวงกลมลอยทับเกม (แบบแชตเฮดของ Discord)
 * สี: เขียว = ไมค์เปิด, แดง = ปิดไมค์, ส้ม = ระบบเสียงหลุด, เทา = ยังไม่ได้เชื่อม
 * แตะ = เปิด/ปิดไมค์, กดค้าง = เปิดแอป, ลากเพื่อย้ายที่
 * ต้องได้รับสิทธิ์ "แสดงทับแอปอื่น" และเปิดสวิตช์ในแอปก่อน
 */
object Bubble {
    private const val PREFS = "bubble"
    private var view: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var state = "off"

    fun enabled(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("on", false)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("on", on).apply()
        if (on) show(ctx) else hide(ctx)
    }

    fun canDraw(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    /** เรียกตอนเปิดแอป/กลับเข้าแอป: ถ้าเปิดสวิตช์ไว้และมีสิทธิ์แล้ว ให้แสดงปุ่ม */
    fun refresh(ctx: Context) {
        if (enabled(ctx) && canDraw(ctx)) show(ctx) else if (!enabled(ctx)) hide(ctx)
    }

    fun setState(s: String) {
        state = s
        val v = view ?: return
        v.post { paint(v) }
    }

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    private fun paint(v: TextView) {
        val color = when (state) {
            "on" -> Color.parseColor("#2EB872")
            "muted" -> Color.parseColor("#E53935")
            "warn" -> Color.parseColor("#FFA000")
            else -> Color.parseColor("#757575")
        }
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(color)
        bg.setStroke(dp(v.context, 2), Color.parseColor("#CCFFFFFF"))
        v.background = bg
        v.text = when (state) {
            "muted" -> "🔇"
            "warn" -> "⚠"
            else -> "🎤"
        }
        v.alpha = 0.88f
    }

    fun show(ctx0: Context) {
        val ctx = ctx0.applicationContext
        if (view != null || !canDraw(ctx)) return
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val size = dp(ctx, 52)
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = prefs.getInt("x", dp(ctx, 8))
        lp.y = prefs.getInt("y", dp(ctx, 160))

        val v = TextView(ctx)
        v.gravity = Gravity.CENTER
        v.textSize = 22f
        v.setTextColor(Color.WHITE)
        paint(v)

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var downAt = 0L
        var moved = false
        var longFired = false
        val longPress = Runnable {
            if (!moved) {
                longFired = true
                try {
                    ctx.startActivity(
                        Intent(ctx, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    )
                } catch (e: Exception) {
                }
            }
        }
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y
                    downAt = System.currentTimeMillis(); moved = false; longFired = false
                    v.postDelayed(longPress, 600)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && (Math.abs(dx) > dp(ctx, 8) || Math.abs(dy) > dp(ctx, 8))) {
                        moved = true
                        v.removeCallbacks(longPress)
                    }
                    if (moved) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        try { wm.updateViewLayout(v, lp) } catch (ex: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(longPress)
                    if (moved) {
                        prefs.edit().putInt("x", lp.x).putInt("y", lp.y).apply()
                    } else if (!longFired && e.action == MotionEvent.ACTION_UP) {
                        // แตะ = เปิด/ปิดไมค์ (กดปุ่มไมค์ในหน้าเว็บ)
                        MainActivity.instance?.runJs("(function(){var b=document.getElementById('mic');if(b){b.click()}})()")
                    }
                    true
                }
                else -> false
            }
        }
        try {
            wm.addView(v, lp)
            view = v
            params = lp
        } catch (e: Exception) {
            view = null
        }
    }

    fun hide(ctx0: Context) {
        val ctx = ctx0.applicationContext
        val v = view ?: return
        try {
            (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (e: Exception) {
        }
        view = null
        params = null
    }
}
