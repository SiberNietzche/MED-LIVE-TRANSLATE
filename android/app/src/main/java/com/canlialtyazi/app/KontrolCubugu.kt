package com.canlialtyazi.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Kontrol çubuğu (masaüstündeki gibi): toplantı sırasında Zoom, Meet vb. uygulamaların üstünde durur.
 *   ⠿ taşı · Başlat/Durdur · konuşulan dil ⇄ altyazı dili · Duraklat · altyazı şeridi · uygulama · kapat
 * Dil düğmesine her dokunuş sıradaki dile geçer; dinleme sürerken de değişir (ses izni yeniden istenmez).
 * Uygulamanın kendi ekranı öndeyken gizlenir.
 */
class KontrolCubugu(private val context: Context, private val ayarlar: Ayarlar) : Canli.Dinleyici {
    private val ana = Handler(Looper.getMainLooper())
    private val wm = context.getSystemService(WindowManager::class.java)
    private var kok: LinearLayout? = null
    private var ekli = false
    private lateinit var param: WindowManager.LayoutParams
    private lateinit var baslat: TextView
    private lateinit var konusma: TextView
    private lateinit var hedef: TextView
    private lateinit var duraklat: TextView
    private lateinit var serit: TextView
    private var calisiyor = false
    private var duraklatildi = false

    private fun dp(v: Int) = Ust.dp(context, v)
    // dar ekranlı telefonlarda (≈ 420 dp altı) çubuk ekrana sığsın: küçük düğmeler, Başlat yalnızca simge
    private val dar = context.resources.configuration.screenWidthDp < 420

    private fun dugme(metin: String, aciklama: String, tikla: () -> Unit) = TextView(context).apply {
        text = metin
        contentDescription = aciklama
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (dar) 13f else 15f)
        typeface = Typeface.DEFAULT_BOLD
        minWidth = dp(if (dar) 32 else 40)
        setPadding(dp(if (dar) 5 else 8), 0, dp(if (dar) 5 else 8), 0)
        background = GradientDrawable().apply { setColor(Color.argb(40, 255, 255, 255)); cornerRadius = dp(10).toFloat() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(if (dar) 36 else 40)).apply { marginEnd = dp(if (dar) 3 else 4) }
        setOnClickListener { tikla() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun kur(): LinearLayout {
        val tema = ayarlar.tema
        val k = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.argb(235, Color.red(tema.serit), Color.green(tema.serit), Color.blue(tema.serit)))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), tema.vurgu and 0x88FFFFFF.toInt())
            }
        }
        val tutamak = TextView(context).apply {
            text = "⠿"; setTextColor(Color.argb(200, 255, 255, 255)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER; setPadding(dp(4), 0, dp(6), 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40))
        }
        baslat = dugme("▶", Metinler.t("baslat")) {
            if (calisiyor) AltyaziServisi.gonder(context, AltyaziServisi.DURDUR)
            else context.startActivity(Intent(context, BaslatEtkinligi::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        konusma = dugme("", Metinler.t("konusulan")) {
            ayarlar.konusma = Diller.sonraki(ayarlar.konusma); dilDegisti()
        }
        val degistir = dugme("⇄", "⇄") {
            val k2 = ayarlar.konusma; ayarlar.konusma = ayarlar.hedef; ayarlar.hedef = k2; dilDegisti()
        }
        hedef = dugme("", Metinler.t("hedef")) {
            ayarlar.hedef = Diller.sonraki(ayarlar.hedef); dilDegisti()
        }
        duraklat = dugme("❚❚", Metinler.t("duraklat")) {
            if (calisiyor) AltyaziServisi.gonder(context, AltyaziServisi.DURAKLAT)
        }
        serit = dugme("CC", Metinler.t("serit")) {
            AltyaziServisi.gonder(context, AltyaziServisi.SERIT)
            ana.postDelayed({ yenile() }, 150)
        }
        val uygulama = dugme("⌂", Metinler.t("uygulama")) {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        val kapat = dugme("✕", Metinler.t("cubuk.kapat")) { AltyaziServisi.gonder(context, AltyaziServisi.CUBUK_KAPAT) }
        for (v in listOf(tutamak, baslat, konusma, degistir, hedef, duraklat, serit, uygulama, kapat)) k.addView(v)

        val ekranG = context.resources.displayMetrics.widthPixels
        val ekranY = context.resources.displayMetrics.heightPixels
        param = Ust.param(WindowManager.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ayarlar.cubukX.coerceIn(0, ekranG)
            y = ayarlar.cubukY.takeIf { it in 0 until ekranY } ?: dp(90)
        }
        // tutamaktan sürükle (her yöne); bırakınca konum hatırlanır
        var bx = 0f; var by = 0f; var px = 0; var py = 0
        tutamak.setOnTouchListener { _, o ->
            when (o.action) {
                MotionEvent.ACTION_DOWN -> { bx = o.rawX; by = o.rawY; px = param.x; py = param.y; true }
                MotionEvent.ACTION_MOVE -> {
                    param.x = (px + (o.rawX - bx).toInt()).coerceIn(0, maxOf(0, ekranG - k.width))
                    param.y = (py + (o.rawY - by).toInt()).coerceIn(0, ekranY - dp(56))
                    if (ekli) runCatching { wm.updateViewLayout(k, param) }
                    true
                }
                MotionEvent.ACTION_UP -> { ayarlar.cubukX = param.x; ayarlar.cubukY = param.y; true }
                else -> false
            }
        }
        return k
    }

    private fun dilDegisti() {
        yenile()
        AltyaziServisi.gonder(context, AltyaziServisi.DIL)
    }

    fun goster() = ana.post {
        if (ekli) return@post
        val k = kok ?: kur().also { kok = it }
        runCatching { wm.addView(k, param); ekli = true; Canli.ekle(this) }
        yenile()
    }

    fun kaldir() = ana.post {
        if (ekli) { kok?.let { runCatching { wm.removeView(it) } }; Canli.cikar(this) }
        ekli = false
    }

    /** Düğmeleri ayarlara ve dinleme durumuna göre günceller. */
    fun yenile() = ana.post {
        if (kok == null) return@post
        val tema = ayarlar.tema
        // emoji olmayan düz semboller (⏸ bazı telefonlarda renkli emojiye dönüşüyordu)
        baslat.text = when {
            dar -> if (calisiyor) "■" else "▶"
            calisiyor -> "■ " + Metinler.t("durdur")
            else -> "▶ " + Metinler.t("baslat")
        }
        baslat.contentDescription = Metinler.t(if (calisiyor) "durdur" else "baslat")
        (baslat.background as GradientDrawable).setColor(if (calisiyor) Color.rgb(200, 55, 60) else tema.vurgu)
        baslat.setTextColor(if (calisiyor) Color.WHITE else tema.vurguYazi)
        konusma.text = Diller.kisa(ayarlar.konusma)
        hedef.text = Diller.kisa(ayarlar.hedef)
        duraklat.text = if (duraklatildi) "▶" else "❚❚"
        duraklat.contentDescription = Metinler.t(if (duraklatildi) "devam" else "duraklat")
        duraklat.alpha = if (calisiyor) 1f else 0.4f
        serit.alpha = if (ayarlar.seritAcik) 1f else 0.4f
    }

    override fun durum(metin: String, calisiyor: Boolean) { this.calisiyor = calisiyor; yenile() }
    override fun duraklama(duraklatildi: Boolean) { this.duraklatildi = duraklatildi; yenile() }
}
