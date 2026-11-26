package com.canlialtyazi.app

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Html
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Kullanım kılavuzu: Türkçe / English / Kurmancî sekmeli, görselli (görseller programın gerçek
 * ekranlarından alınmıştır: assets/kilavuz/<dil>/<ad>.webp). İçerik: [Kilavuz].
 * Görsellerdeki numaralı işaretler metindeki numaralarla eşleşir.
 */
class KilavuzEtkinligi : AppCompatActivity() {
    private lateinit var tema: Tema
    private lateinit var kaydir: ScrollView
    private lateinit var icerik: LinearLayout
    private lateinit var sekmeler: LinearLayout
    private var dil = "tr"

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ayarlar = Ayarlar(this)
        tema = ayarlar.tema
        dil = savedInstanceState?.getString("dil") ?: ayarlar.arayuzDili
        window.decorView.setBackgroundColor(tema.arka)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = tema.acik; isAppearanceLightNavigationBars = tema.acik
        }

        val kok = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(tema.arka) }
        // üst çubuk: başlık + kapat, altında dil sekmeleri
        val ust = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(8), dp(4))
        }
        val baslik = TextView(this).apply {
            setTextColor(tema.metin); setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f); typeface = Typeface.DEFAULT_BOLD
        }
        ust.addView(baslik, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        ust.addView(TextView(this).apply {
            text = "✕"; setTextColor(tema.metin); setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); gravity = Gravity.CENTER
            contentDescription = "✕"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        kok.addView(ust)
        sekmeler = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), 0, dp(12), dp(8)) }
        kok.addView(sekmeler)
        kok.addView(View(this).apply { setBackgroundColor(tema.kenar) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))

        kaydir = ScrollView(this)
        icerik = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(32)) }
        kaydir.addView(icerik)
        kok.addView(kaydir, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(kok)
        ViewCompat.setOnApplyWindowInsetsListener(kok) { v, i ->
            val c = i.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(c.left, c.top, c.right, c.bottom)
            WindowInsetsCompat.CONSUMED
        }
        ciz(baslik)
    }

    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); outState.putString("dil", dil) }

    private fun ciz(baslik: TextView) {
        val eski = Metinler.dil
        Metinler.dil = dil
        baslik.text = Metinler.t("kilavuz.baslik")
        Metinler.dil = eski
        sekmeler.removeAllViews()
        for ((kod, ad) in listOf("tr" to "Türkçe", "en" to "English", "kmr" to "Kurmancî")) {
            sekmeler.addView(TextView(this).apply {
                text = ad; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                val secili = kod == dil
                setTextColor(if (secili) tema.vurguYazi else tema.metin)
                typeface = if (secili) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    if (secili) setColor(tema.vurgu) else { setColor(tema.panel); setStroke(dp(1), tema.kenar) }
                }
                setOnClickListener { dil = kod; ciz(baslik); kaydir.scrollTo(0, 0) }
            }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(6) })
        }

        icerik.removeAllViews()
        val bolumler = Kilavuz.bolumler(dil)
        // içindekiler
        val ic = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply { setColor(tema.panel); cornerRadius = dp(12).toFloat(); setStroke(dp(1), tema.kenar) }
        }
        ic.addView(yazi(Metinler.buyuk(Kilavuz.icindekiler(dil), dil), 11f, soluk = true, kalin = true).apply { letterSpacing = 0.08f })
        val basliklar = mutableListOf<View>()
        bolumler.forEachIndexed { i, b ->
            ic.addView(yazi("${i + 1}. ${b.baslik}", 14f).apply {
                setTextColor(tema.vurgu2.takeIf { !tema.acik } ?: tema.vurgu); setPadding(0, dp(5), 0, dp(5))
                setOnClickListener { basliklar.getOrNull(i)?.let { h -> kaydir.smoothScrollTo(0, h.top - dp(8)) } }
            })
        }
        icerik.addView(ic, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })

        bolumler.forEachIndexed { i, b ->
            val h = yazi("${i + 1}. ${b.baslik}", 18f, kalin = true).apply {
                setTextColor(tema.metin); setPadding(0, dp(22), 0, dp(6))
            }
            basliklar.add(h)
            icerik.addView(h)
            for (o in b.ogeler) icerik.addView(ogeGorunumu(o))
        }
    }

    private fun yazi(metin: CharSequence, boy: Float = 15f, soluk: Boolean = false, kalin: Boolean = false) = TextView(this).apply {
        text = metin
        setTextSize(TypedValue.COMPLEX_UNIT_SP, boy)
        setTextColor(if (soluk) tema.soluk else tema.metin)
        setLineSpacing(0f, 1.2f)
        if (kalin) typeface = Typeface.DEFAULT_BOLD
        if (dil == "ar") textDirection = View.TEXT_DIRECTION_RTL
    }

    private fun html(s: String) = Html.fromHtml(s, Html.FROM_HTML_MODE_COMPACT)

    private fun rozet(n: Int) = TextView(this).apply {
        text = n.toString(); gravity = Gravity.CENTER; setTextColor(Color.rgb(26, 18, 5))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); typeface = Typeface.DEFAULT_BOLD
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(251, 191, 36)); setStroke(dp(1), Color.WHITE) }
    }

    private fun ogeGorunumu(o: Kilavuz.Oge): View = when (o) {
        is Kilavuz.P -> yazi(html(o.html)).apply { setPadding(0, dp(3), 0, dp(6)) }
        is Kilavuz.Madde -> LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(4), 0, 0, dp(6))
            for (m in o.html) addView(LinearLayout(this@KilavuzEtkinligi).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(2), 0, dp(2))
                addView(yazi("•", 15f).apply { setTextColor(tema.vurgu) }, LinearLayout.LayoutParams(dp(16), ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(yazi(html(m)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
        }
        is Kilavuz.Numara -> LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(6))
            for ((n, m) in o.ogeler) addView(LinearLayout(this@KilavuzEtkinligi).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(4))
                addView(rozet(n), LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(10); topMargin = dp(1) })
                addView(yazi(html(m)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
        }
        is Kilavuz.Not -> yazi(html(o.html), 14f).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply { setColor(tema.panel); cornerRadius = dp(10).toFloat(); setStroke(dp(1), tema.vurgu and 0x66FFFFFF) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(6); bottomMargin = dp(8) }
        }
        is Kilavuz.Resim -> LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(6), 0, dp(10))
            val bmp = listOf(dil, "tr").firstNotNullOfOrNull { d ->
                runCatching { assets.open("kilavuz/$d/${o.ad}.webp").use { BitmapFactory.decodeStream(it) } }.getOrNull()
            }
            if (bmp != null) addView(ImageView(this@KilavuzEtkinligi).apply {
                setImageBitmap(bmp); adjustViewBounds = true; contentDescription = o.aciklama
                background = GradientDrawable().apply { setStroke(dp(1), tema.kenar); cornerRadius = dp(8).toFloat() }
                setPadding(dp(1), dp(1), dp(1), dp(1))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(yazi(o.aciklama, 12f, soluk = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) })
        }
    }
}
