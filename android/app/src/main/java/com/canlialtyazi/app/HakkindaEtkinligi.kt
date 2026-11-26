package com.canlialtyazi.app

import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File

/**
 * Hakkında: sürüm, geliştirici, sosyal medya bağlantıları (basınca tarayıcıda / uygulamada açılır)
 * ve GitHub üzerinden güncelleme (bkz. [Guncelleme]).
 */
class HakkindaEtkinligi : AppCompatActivity() {
    private lateinit var tema: Tema
    private lateinit var ayarlar: Ayarlar
    private val ana = Handler(Looper.getMainLooper())
    private lateinit var durum: TextView
    private lateinit var cubuk: ProgressBar
    private lateinit var notlar: TextView
    private lateinit var kurDugme: Button
    private lateinit var denetleDugme: Button
    private var surum: Guncelleme.Surum? = null
    private var inenApk: File? = null
    @Volatile private var calisiyor = false
    @Volatile private var iptal = false

    private class Baglanti(val ad: String, val kullanici: String, val url: String, val simge: String)
    private val baglantilar = listOf(
        Baglanti("Instagram", "@cybergahnet", "https://www.instagram.com/cybergahnet/", "◎"),
        Baglanti("X", "@seyfi_polat21", "https://x.com/seyfi_polat21", "𝕏"),
        Baglanti("X", "@cyberagent2112", "https://x.com/cyberagent2112", "𝕏"),
        Baglanti("YouTube", "@cybergah", "https://www.youtube.com/@cybergah", "▶"),
        Baglanti("TikTok", "@cybergeh", "https://www.tiktok.com/@cybergeh?lang=tr-TR", "♪"),
        Baglanti("cybergah.com", "", "https://cybergah.com/tr/", "🌐"),
    )

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun yazi(metin: CharSequence, boy: Float = 15f, soluk: Boolean = false, kalin: Boolean = false) = TextView(this).apply {
        text = metin; setTextSize(TypedValue.COMPLEX_UNIT_SP, boy)
        setTextColor(if (soluk) tema.soluk else tema.metin); setLineSpacing(0f, 1.15f)
        if (kalin) typeface = Typeface.DEFAULT_BOLD
    }

    private fun etiket(metin: String) = yazi(Metinler.buyuk(metin), 11f, soluk = true, kalin = true).apply {
        letterSpacing = 0.08f; setPadding(0, dp(22), 0, dp(8))
    }

    private fun dugme(metin: String, birincil: Boolean, tikla: () -> Unit) = Button(this).apply {
        text = metin; isAllCaps = false
        setTextColor(if (birincil) tema.vurguYazi else tema.metin)
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            if (birincil) setColor(tema.vurgu) else { setColor(tema.vurgu and 0x22FFFFFF); setStroke(dp(1), tema.vurgu) }
        }
        setOnClickListener { tikla() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ayarlar = Ayarlar(this)
        tema = ayarlar.tema
        Metinler.dil = ayarlar.arayuzDili
        window.decorView.setBackgroundColor(tema.arka)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = tema.acik; isAppearanceLightNavigationBars = tema.acik
        }
        val kok = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(tema.arka) }
        val ust = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(8), dp(4))
        }
        ust.addView(yazi(Metinler.t("hakkinda"), 19f, kalin = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        ust.addView(TextView(this).apply {
            text = "✕"; setTextColor(tema.metin); setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); gravity = Gravity.CENTER
            contentDescription = Metinler.t("kapat")
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        kok.addView(ust)
        kok.addView(View(this).apply { setBackgroundColor(tema.kenar) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))

        val kaydir = ScrollView(this)
        val ic = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(32)) }
        kaydir.addView(ic)
        kok.addView(kaydir, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(kok)
        ViewCompat.setOnApplyWindowInsetsListener(kok) { v, i ->
            val c = i.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(c.left, c.top, c.right, c.bottom)
            WindowInsetsCompat.CONSUMED
        }

        // program
        ic.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) },
            LinearLayout.LayoutParams(dp(72), dp(72)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        ic.addView(yazi("MED LIVE TRANSLATE", 20f, kalin = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) })
        ic.addView(yazi(Metinler.t("hk.surum", Guncelleme.simdikiSurum(this)), 13f, soluk = true).apply { gravity = Gravity.CENTER })
        ic.addView(yazi(Metinler.t("hk.aciklama"), 14f, soluk = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0) })
        ic.addView(yazi(Metinler.t("hk.gelistirici") + "  CYBERGAH", 13f).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) })

        // sosyal medya
        ic.addView(etiket(Metinler.t("hk.takip")))
        for (s in baglantilar.chunked(2)) {
            val satir = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (b in s) satir.addView(baglantiKutusu(b), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(8); bottomMargin = dp(8) })
            ic.addView(satir)
        }

        // güncelleme
        ic.addView(etiket(Metinler.t("gu.baslik")))
        durum = yazi(Metinler.t("gu.bilinmiyor"), 14f)
        ic.addView(durum)
        cubuk = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; visibility = View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(tema.vurgu)
        }
        ic.addView(cubuk, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)).apply { topMargin = dp(8) })
        notlar = yazi("", 13f, soluk = true).apply {
            visibility = View.GONE; setPadding(dp(12), dp(10), dp(12), dp(10)); setTextIsSelectable(true)
            background = GradientDrawable().apply { setColor(tema.panel); cornerRadius = dp(10).toFloat(); setStroke(dp(1), tema.kenar) }
        }
        ic.addView(notlar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        kurDugme = dugme("", true) { kurBasildi() }.apply { visibility = View.GONE }
        ic.addView(kurDugme, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        denetleDugme = dugme(Metinler.t("gu.denetle"), false) { denetle() }
        ic.addView(denetleDugme, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        ic.addView(CheckBox(this).apply {
            text = Metinler.t("gu.oto"); setTextColor(tema.metin); isChecked = ayarlar.guncellemeOtomatik
            buttonTintList = android.content.res.ColorStateList.valueOf(tema.vurgu)
            setOnCheckedChangeListener { _, b -> ayarlar.guncellemeOtomatik = b; Guncelleme.zamanla(this@HakkindaEtkinligi) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        // en altta: iletişim (dokununca X hesabı açılır)
        val x = "X @seyfi_polat21"
        val metin = Metinler.t("hk.iletisim", x)
        ic.addView(yazi(android.text.SpannableString(metin).apply {
            val b = metin.indexOf(x)
            if (b >= 0) {
                setSpan(android.text.style.ForegroundColorSpan(tema.vurgu), b, b + x.length, 0)
                setSpan(android.text.style.StyleSpan(Typeface.BOLD), b, b + x.length, 0)
            }
        }, 14f).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(28), 0, 0)
            setOnClickListener { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://x.com/seyfi_polat21"))) } }
        })

        denetle() // ekran açılınca son sürüm sorulur
    }

    override fun onResume() {
        super.onResume()
        // kurma izni ayarlarından dönüldüyse indirilen dosya kurulur
        val apk = inenApk
        if (apk != null && apk.isFile && Guncelleme.kurmaIzniVar(this)) {
            inenApk = null
            Guncelleme.kur(this, apk)
        }
    }

    override fun onDestroy() { iptal = true; super.onDestroy() }

    private fun baglantiKutusu(b: Baglanti) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(12), dp(10), dp(12))
        background = GradientDrawable().apply { setColor(tema.panel); cornerRadius = dp(12).toFloat(); setStroke(dp(1), tema.kenar) }
        isClickable = true; contentDescription = "${b.ad} ${b.kullanici}"
        addView(TextView(this@HakkindaEtkinligi).apply {
            text = b.simge; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); setTextColor(tema.vurgu)
        }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(8) })
        val ad = LinearLayout(this@HakkindaEtkinligi).apply { orientation = LinearLayout.VERTICAL }
        ad.addView(yazi(b.ad, 14f, kalin = true).apply { maxLines = 1 })
        ad.addView(yazi(b.kullanici.ifEmpty { Metinler.t("hk.site") }, 12f, soluk = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        addView(ad, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        setOnClickListener {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(b.url))) }
                .onFailure { Toast.makeText(this@HakkindaEtkinligi, b.url, Toast.LENGTH_LONG).show() }
        }
    }

    private fun hataMetni(e: Throwable) = if (e is Guncelleme.Hata) Metinler.t("gu.e.${e.kod}") else (e.message ?: e.toString())

    private fun denetle() {
        if (calisiyor) return
        calisiyor = true
        durum.text = Metinler.t("gu.denetleniyor"); durum.setTextColor(tema.metin)
        Thread {
            val sonuc = runCatching { Guncelleme.denetle(this) }
            ana.post {
                calisiyor = false
                if (isFinishing) return@post
                sonuc.onSuccess { s ->
                    surum = s
                    if (s.yeni) {
                        durum.text = Metinler.t("gu.var", s.surum, Guncelleme.simdikiSurum(this))
                        kurDugme.text = if (s.apkUrl != null) Metinler.t("gu.kur", "%.0f".format(s.apkBoyut / 1048576.0)) else Metinler.t("gu.sayfa")
                        kurDugme.visibility = View.VISIBLE
                        notlar.text = s.notlar; notlar.visibility = if (s.notlar.isBlank()) View.GONE else View.VISIBLE
                    } else {
                        durum.text = Metinler.t("gu.guncel", Guncelleme.simdikiSurum(this))
                        kurDugme.visibility = View.GONE; notlar.visibility = View.GONE
                    }
                    durum.setTextColor(tema.metin)
                }.onFailure { e ->
                    durum.text = Metinler.t("gu.hata", hataMetni(e)); durum.setTextColor(0xFFFB7185.toInt())
                }
            }
        }.start()
    }

    private fun kurBasildi() {
        val s = surum ?: return
        if (!s.yeni || calisiyor) return
        if (s.apkUrl == null || s.shaUrl == null) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.sayfa))) }; return
        }
        calisiyor = true; iptal = false
        kurDugme.isEnabled = false; denetleDugme.isEnabled = false
        cubuk.visibility = View.VISIBLE; cubuk.progress = 0
        Thread {
            val sonuc = runCatching {
                Guncelleme.indir(this, s, { iptal }) { inen, toplam ->
                    ana.post {
                        if (toplam > 0) cubuk.progress = (inen * 1000 / toplam).toInt()
                        durum.text = Metinler.t("gu.indiriliyor", "%.1f".format(inen / 1048576.0), "%.1f".format(toplam / 1048576.0))
                    }
                }
            }
            ana.post {
                calisiyor = false
                if (isFinishing) return@post
                kurDugme.isEnabled = true; denetleDugme.isEnabled = true
                sonuc.onSuccess { apk ->
                    cubuk.progress = 1000
                    durum.text = Metinler.t("gu.hazir")
                    if (Guncelleme.kurmaIzniVar(this)) Guncelleme.kur(this, apk)
                    else {
                        inenApk = apk
                        Toast.makeText(this, Metinler.t("gu.izin"), Toast.LENGTH_LONG).show()
                        Guncelleme.kurmaIzniAc(this)
                    }
                }.onFailure { e ->
                    cubuk.visibility = View.GONE
                    if (e !is InterruptedException) {
                        durum.text = Metinler.t("gu.kur.hata", hataMetni(e)); durum.setTextColor(0xFFFB7185.toInt())
                    }
                }
            }
        }.start()
    }
}
