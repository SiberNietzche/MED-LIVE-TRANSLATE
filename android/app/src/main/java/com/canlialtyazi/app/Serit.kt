package com.canlialtyazi.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/** Diğer uygulamaların üstünde duran pencereler için ortak yardımcılar. */
object Ust {
    fun izinVar(c: Context) = Settings.canDrawOverlays(c)
    fun dp(c: Context, v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), c.resources.displayMetrics).toInt()
    fun param(genislik: Int) = WindowManager.LayoutParams(
        genislik, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    )
}

/**
 * Altyazı şeridi: diğer uygulamaların (YouTube, Zoom, tarayıcı…) üstünde duran yarı saydam şerit.
 * Parmakla yukarı / aşağı taşınır (konum hatırlanır); dokununca uygulama açılır.
 * Gizlenince pencereden tamamen kaldırılır (görünmez hali bile dokunmaları yakalıyordu).
 */
class Serit(private val context: Context, private val ayarlar: Ayarlar) {
    companion object {
        fun izinVar(c: Context) = Ust.izinVar(c)
    }

    private val ana = Handler(Looper.getMainLooper())
    private val wm = context.getSystemService(WindowManager::class.java)
    private var kutu: LinearLayout? = null
    private var ekli = false
    private var sonSatir: Canli.Satir? = null
    private lateinit var ceviriYazi: TextView
    private lateinit var orijinalYazi: TextView
    private lateinit var param: WindowManager.LayoutParams

    private fun dp(v: Int) = Ust.dp(context, v)

    @SuppressLint("ClickableViewAccessibility")
    private fun kur(): LinearLayout {
        val tema = ayarlar.tema
        val k = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.argb(238, Color.red(tema.serit), Color.green(tema.serit), Color.blue(tema.serit)))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), tema.vurgu and 0x66FFFFFF)
            }
        }
        ceviriYazi = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ayarlar.yaziBoyutu.toFloat())
            typeface = Typeface.DEFAULT_BOLD
            text = Metinler.t("durum.dinleniyor")
            maxLines = 3
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
        }
        orijinalYazi = TextView(context).apply {
            setTextColor(Color.rgb(205, 200, 222))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ayarlar.yaziBoyutu * 0.7f)
            maxLines = 2
            visibility = View.GONE
        }
        k.addView(ceviriYazi)
        k.addView(orijinalYazi)
        val ekranY = context.resources.displayMetrics.heightPixels
        // genişlik ekrandan iki kenar payı kadar dar (MATCH_PARENT + kenar payı sağdan taşıyordu)
        param = Ust.param(context.resources.displayMetrics.widthPixels - dp(24)).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = ayarlar.seritKonumY.takeIf { it in 0 until ekranY } ?: (ekranY * 0.72).toInt()
        }
        // sürükle: dikey taşı; kısa dokunuş uygulamayı açar
        var baslaY = 0f; var baslaKonum = 0; var tasindi = false
        k.setOnTouchListener { _, o ->
            when (o.action) {
                MotionEvent.ACTION_DOWN -> { baslaY = o.rawY; baslaKonum = param.y; tasindi = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val fark = (o.rawY - baslaY).toInt()
                    if (abs(fark) > dp(6)) tasindi = true
                    param.y = (baslaKonum + fark).coerceIn(0, ekranY - dp(60))
                    if (ekli) runCatching { wm.updateViewLayout(k, param) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (tasindi) ayarlar.seritKonumY = param.y
                    else context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    true
                }
                else -> false
            }
        }
        return k
    }

    fun goster() = ana.post {
        if (ekli) return@post
        val k = kutu ?: kur().also { kutu = it }
        runCatching { wm.addView(k, param); ekli = true }
        sonSatir?.let { yazNow(it) }
    }

    fun kaldir() = ana.post {
        kutu?.let { if (ekli) runCatching { wm.removeView(it) } }
        ekli = false
    }

    fun yaz(s: Canli.Satir) = ana.post { sonSatir = s; if (kutu != null) yazNow(s) }

    private fun yazNow(s: Canli.Satir) {
        val rtlHedef = Diller.bul(s.hedef).rtl
        ceviriYazi.text = s.ceviri ?: s.orijinal
        ceviriYazi.textDirection = if (s.ceviri != null && rtlHedef) View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_FIRST_STRONG
        val orijinalGoster = ayarlar.orijinalGoster && s.ceviri != null && s.ceviri != s.orijinal
        orijinalYazi.visibility = if (orijinalGoster) View.VISIBLE else View.GONE
        orijinalYazi.text = s.orijinal
    }
}
