package com.canlialtyazi.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.util.Date

class MainActivity : AppCompatActivity(), Canli.Dinleyici {
    private lateinit var ayarlar: Ayarlar
    private lateinit var tema: Tema
    private val ana = Handler(Looper.getMainLooper())

    // görünümler
    private lateinit var kok: LinearLayout
    // hoparlörlü görüşme: erişilebilirlik hizmeti durumu + aç düğmesi (yalnız mikrofon kaynaklarında)
    private lateinit var gorusmeKutu: LinearLayout
    private lateinit var gorusmeDurum: TextView
    private lateinit var kurulumKart: LinearLayout
    private lateinit var kurulumYazi: TextView
    private lateinit var kurulumAyrinti: TextView
    private lateinit var kurulumCubuk: ProgressBar
    private lateinit var kurulumTekrar: Button
    private lateinit var anaBolum: LinearLayout
    private lateinit var konusmaSec: Spinner
    private lateinit var hedefSec: Spinner
    private lateinit var kaynakGrup: RadioGroup
    private lateinit var seritKutu: CheckBox
    private lateinit var baslatDugme: Button
    private lateinit var durumYazi: TextView
    private lateinit var seviyeCubuk: ProgressBar
    private lateinit var sonCeviri: TextView
    private lateinit var sonOrijinal: TextView
    private lateinit var gecmisListe: LinearLayout
    private lateinit var ayarlarBolum: LinearLayout

    @Volatile private var indirmeIptal = false
    private var cubukIstendi = false
    private var calisiyor = false

    private val ustIzni = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        seritKutu.isChecked = Ust.izinVar(this)
        if (cubukIstendi && Ust.izinVar(this)) cubuguAc()
        cubukIstendi = false
    }

    private val aygitDinleyici = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(a: Array<out AudioDeviceInfo>?) = kaynaklariYenile()
        override fun onAudioDevicesRemoved(a: Array<out AudioDeviceInfo>?) = kaynaklariYenile()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ayarlar = Ayarlar(this)
        tema = ayarlar.tema
        Metinler.dil = ayarlar.arayuzDili
        ciz()
        // güncelleme: arka plan denetimi (6 saatte bir) + açılışta, son denetimden 6 saat geçtiyse
        Guncelleme.zamanla(this)
        if (ayarlar.guncellemeOtomatik && System.currentTimeMillis() - ayarlar.sonDenetim >= Guncelleme.ALTI_SAAT) {
            val uyg = applicationContext
            Thread {
                runCatching { Guncelleme.denetle(uyg) }.onSuccess { s ->
                    Guncelleme.bildir(uyg, s)
                    ana.post { if (!isFinishing) guncellemeSeridi() }
                }
            }.start()
        }
    }

    private lateinit var guncellemeSerit: LinearLayout
    private lateinit var guncellemeYazi: TextView

    /** Son denetimde bulunan sürüm hâlâ yüklü olandan yeniyse şerit görünür. */
    private fun guncellemeSeridi() {
        val s = ayarlar.sonBulunan
        val goster = s.isNotBlank() && Guncelleme.dahaYeni(s, Guncelleme.simdikiSurum(this))
        guncellemeSerit.visibility = if (goster) View.VISIBLE else View.GONE
        if (goster) guncellemeYazi.text = "⬇  " + Metinler.t("gu.serit", s)
    }

    override fun onStart() {
        super.onStart()
        Canli.ekle(this)
        // uygulama arka plandayken (ör. Zoom açıkken) gelen altyazılar da görünsün
        if (::gecmisListe.isInitialized) {
            gecmisCiz()
            Canli.gecmis.lastOrNull()?.let { s ->
                sonCeviri.text = s.ceviri ?: s.orijinal
                sonOrijinal.text = if (s.ceviri != null && s.ceviri != s.orijinal) s.orijinal else ""
            }
        }
        getSystemService(AudioManager::class.java).registerAudioDeviceCallback(aygitDinleyici, ana)
        kaynaklariYenile()
        seritKutu.isChecked = Ust.izinVar(this)
        guncellemeSeridi()
    }

    override fun onStop() {
        Canli.cikar(this)
        getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(aygitDinleyici)
        super.onStop()
    }

    override fun onResume() { super.onResume(); Canli.uygulamaOnde = true }
    override fun onPause() { Canli.uygulamaOnde = false; super.onPause() }
    override fun onDestroy() { indirmeIptal = true; super.onDestroy() }

    // ------------------------------------------------------------------ yardımcılar
    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun kart(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply { setColor(tema.panel); cornerRadius = dp(16).toFloat(); setStroke(dp(1), tema.kenar) }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(12) }
    }

    private fun yazi(metin: String, boy: Float = 15f, soluk: Boolean = false, kalin: Boolean = false) = TextView(this).apply {
        text = metin
        setTextSize(TypedValue.COMPLEX_UNIT_SP, boy)
        setTextColor(if (soluk) tema.soluk else tema.metin)
        if (kalin) typeface = Typeface.DEFAULT_BOLD
    }

    private fun etiket(metin: String) = yazi(Metinler.buyuk(metin), 11f, soluk = true, kalin = true).apply {
        letterSpacing = 0.08f; setPadding(0, dp(4), 0, dp(6))
    }

    /** birincil: vurgu renkli dolu; cizgili: vurgu renkli çerçeve (Paylaş/Temizle gibi); diğer: sade. */
    private fun dugme(metin: String, birincil: Boolean = false, cizgili: Boolean = false, tikla: () -> Unit) = Button(this).apply {
        text = metin; isAllCaps = false
        setTextColor(if (birincil) tema.vurguYazi else tema.metin)
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            when {
                birincil -> setColor(tema.vurgu)
                cizgili -> { setColor(tema.vurgu and 0x22FFFFFF); setStroke(dp(1), tema.vurgu) }
                else -> setColor(tema.kenar or 0x22000000)
            }
        }
        setOnClickListener { tikla() }
    }

    private fun kutucuk(metin: String) = CheckBox(this).apply {
        text = metin; setTextColor(tema.metin)
        buttonTintList = android.content.res.ColorStateList.valueOf(tema.vurgu)
    }

    private fun dilSecici(secili: String, degisti: (String) -> Unit) = Spinner(this, Spinner.MODE_DROPDOWN).apply {
        val kodlar = Diller.HEPSI.map { it.kod }
        adapter = object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
            kodlar.map { Metinler.dilAdi(it) }) {
            override fun getView(p: Int, v: View?, g: ViewGroup) =
                (super.getView(p, v, g) as TextView).apply { setTextColor(tema.metin) }
            override fun getDropDownView(p: Int, v: View?, g: ViewGroup) =
                (super.getDropDownView(p, v, g) as TextView).apply { setTextColor(tema.metin); setBackgroundColor(tema.panel) }
        }
        setPopupBackgroundDrawable(GradientDrawable().apply { setColor(tema.panel); cornerRadius = dp(10).toFloat() })
        setSelection(kodlar.indexOf(secili).coerceAtLeast(0))
        background = GradientDrawable().apply { setColor(tema.arka); cornerRadius = dp(10).toFloat(); setStroke(dp(1), tema.kenar) }
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, i: Int, id: Long) = degisti(kodlar[i])
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    // ------------------------------------------------------------------ ekran
    private fun ciz() {
        window.decorView.setBackgroundColor(tema.arka)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = tema.acik
            isAppearanceLightNavigationBars = tema.acik
        }
        val kaydir = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(tema.arka) }
        kok = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(28)) }
        kaydir.addView(kok)
        setContentView(kaydir)
        // Android 15 uygulamayı ekranın kenarına kadar çizer: durum ve gezinme çubuğu kadar boşluk bırak
        ViewCompat.setOnApplyWindowInsetsListener(kaydir) { v, i ->
            val c = i.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(c.left, c.top, c.right, c.bottom)
            WindowInsetsCompat.CONSUMED
        }

        // başlık
        val baslik = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        baslik.addView(ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher); layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        })
        val ad = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        ad.addView(yazi("MED LIVE TRANSLATE", 18f, kalin = true))
        ad.addView(yazi(Metinler.t("alt") + " · v" + packageManager.getPackageInfo(packageName, 0).versionName, 12f, soluk = true))
        baslik.addView(ad, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        baslik.addView(dugme("ⓘ") { startActivity(Intent(this, HakkindaEtkinligi::class.java)) }
            .apply { contentDescription = Metinler.t("hakkinda"); minWidth = 0; minimumWidth = 0; setPadding(0, 0, 0, 0)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f) },
            LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(6) })
        baslik.addView(dugme("?") { startActivity(Intent(this, KilavuzEtkinligi::class.java)) }
            .apply { contentDescription = Metinler.t("kilavuz"); minWidth = 0; minimumWidth = 0; setPadding(0, 0, 0, 0)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); typeface = Typeface.DEFAULT_BOLD },
            LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(6) })
        baslik.addView(dugme("⚙") { ayarlarBolum.visibility = if (ayarlarBolum.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
            .apply { contentDescription = Metinler.t("ayarlar"); minWidth = 0; minimumWidth = 0; setPadding(0, 0, 0, 0) },
            LinearLayout.LayoutParams(dp(48), dp(48)))
        kok.addView(baslik)

        // yeni sürüm bulununca görünen şerit (dokununca Hakkında → Güncelle)
        guncellemeSerit = kart().apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE
            background = GradientDrawable().apply { setColor(tema.panel); cornerRadius = dp(14).toFloat(); setStroke(dp(2), 0xFFFBBF24.toInt()) }
            setOnClickListener { startActivity(Intent(this@MainActivity, HakkindaEtkinligi::class.java)) }
        }
        guncellemeYazi = yazi("", 14f, kalin = true)
        guncellemeSerit.addView(guncellemeYazi, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        guncellemeSerit.addView(dugme(Metinler.t("gu.serit.ac"), birincil = true) {
            startActivity(Intent(this, HakkindaEtkinligi::class.java))
        })
        kok.addView(guncellemeSerit)

        ayarlarCiz()
        kurulumCiz()
        anaCiz()
        if (Modeller.hepsiHazir(this)) anaBolum.visibility = View.VISIBLE else indirmeyiBaslat()
        // kılavuz ilk açılışta bir kez kendiliğinden açılır (modeller inerken okunabilir)
        if (!ayarlar.kilavuzGoruldu) {
            ayarlar.kilavuzGoruldu = true
            startActivity(Intent(this, KilavuzEtkinligi::class.java))
        }
    }

    private fun ayarlarCiz() {
        ayarlarBolum = kart().apply { visibility = View.GONE }
        ayarlarBolum.addView(etiket(Metinler.t("arayuz")))
        val diller = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((kod, ad) in listOf("tr" to "Türkçe", "en" to "English", "kmr" to "Kurmancî")) {
            diller.addView(dugme(ad, birincil = kod == ayarlar.arayuzDili) {
                ayarlar.arayuzDili = kod; Metinler.dil = kod; recreate()
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        }
        ayarlarBolum.addView(diller)

        // tema: masaüstündeki 5 tema
        ayarlarBolum.addView(etiket(Metinler.t("tema")).apply { setPadding(0, dp(14), 0, dp(6)) })
        val temalar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (t in Temalar.HEPSI) {
            val secili = t.kod == tema.kod
            val kutu = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(dp(2), dp(8), dp(2), dp(8))
                background = GradientDrawable().apply {
                    setColor(t.arka); cornerRadius = dp(12).toFloat()
                    setStroke(dp(if (secili) 3 else 1), if (secili) tema.metin else t.kenar or 0x44000000)
                }
                setOnClickListener { ayarlar.tema = t; recreate() }
            }
            kutu.addView(View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    colors = intArrayOf(t.vurgu, t.vurgu2); orientation = GradientDrawable.Orientation.TL_BR
                }
                layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
            })
            kutu.addView(TextView(this).apply {
                text = Metinler.t("tema.${t.kod}"); setTextColor(t.metin); gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f); maxLines = 1; setPadding(0, dp(4), 0, 0)
            })
            temalar.addView(kutu, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(5) })
        }
        ayarlarBolum.addView(temalar)

        ayarlarBolum.addView(etiket(Metinler.t("yazi")).apply { setPadding(0, dp(14), 0, dp(6)) })
        val boyYazi = yazi("${ayarlar.yaziBoyutu} sp", 13f, soluk = true)
        ayarlarBolum.addView(SeekBar(this).apply {
            max = 22; progress = ayarlar.yaziBoyutu - 14
            progressTintList = android.content.res.ColorStateList.valueOf(tema.vurgu)
            thumbTintList = android.content.res.ColorStateList.valueOf(tema.vurgu)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, k: Boolean) { ayarlar.yaziBoyutu = p + 14; boyYazi.text = "${p + 14} sp" }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })
        ayarlarBolum.addView(boyYazi)
        ayarlarBolum.addView(kutucuk(Metinler.t("orijinal")).apply {
            isChecked = ayarlar.orijinalGoster
            setOnCheckedChangeListener { _, b -> ayarlar.orijinalGoster = b }
        })
        kmrCiz()
        cevrimdisiCiz()
        kok.addView(ayarlarBolum)
    }

    // ------------------------------------------------------------------ Kürtçe modeli (Küçük / Orta)
    private lateinit var kmrKucuk: RadioButton
    private lateinit var kmrOrta: RadioButton
    private lateinit var kmrDurum: TextView
    private lateinit var kmrCubuk: ProgressBar
    private lateinit var kmrDugme: Button
    @Volatile private var kmrIndiriyor = false
    @Volatile private var kmrIptal = false

    private fun kmrCiz() {
        ayarlarBolum.addView(etiket(Metinler.t("kmr.baslik")).apply { setPadding(0, dp(14), 0, dp(6)) })
        val grup = RadioGroup(this)
        kmrKucuk = RadioButton(this).apply { text = Metinler.t("kmr.kucuk"); setTextColor(tema.metin); buttonTintList = android.content.res.ColorStateList.valueOf(tema.vurgu) }
        kmrOrta = RadioButton(this).apply { setTextColor(tema.metin); buttonTintList = android.content.res.ColorStateList.valueOf(tema.vurgu) }
        grup.addView(kmrKucuk); grup.addView(kmrOrta)
        kmrKucuk.setOnCheckedChangeListener { _, b -> if (b) ayarlar.kurmanciOrta = false }
        kmrOrta.setOnCheckedChangeListener { _, b -> if (b) ayarlar.kurmanciOrta = true }
        ayarlarBolum.addView(grup)
        kmrDurum = yazi("", 13f).apply { setPadding(0, dp(8), 0, dp(4)) }
        ayarlarBolum.addView(kmrDurum)
        kmrCubuk = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progressTintList = android.content.res.ColorStateList.valueOf(tema.vurgu); visibility = View.GONE
        }
        ayarlarBolum.addView(kmrCubuk)
        kmrDugme = dugme("", cizgili = true) { kmrDugmesi() }
        ayarlarBolum.addView(kmrDugme, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(6) })
        kmrYenile()
    }

    private fun kmrYenile() {
        val hazir = Modeller.omniHazir(this)
        val mb = (Modeller.OMNI_ARSIV.boyut / 1_000_000).toInt()
        kmrOrta.text = Metinler.t("kmr.orta") + if (hazir) "" else "  " + Metinler.t("kmr.orta.yok")
        kmrOrta.isEnabled = hazir; kmrOrta.alpha = if (hazir) 1f else 0.5f
        if (ayarlar.kurmanciOrta && hazir) kmrOrta.isChecked = true else kmrKucuk.isChecked = true
        if (kmrIndiriyor) { kmrDugme.text = Metinler.t("cevrimdisi.iptal"); return }
        kmrCubuk.visibility = View.GONE
        kmrDurum.text = if (hazir) Metinler.t("kmr.hazir") else ""
        kmrDurum.visibility = if (kmrDurum.text.isNotEmpty()) View.VISIBLE else View.GONE
        kmrDugme.text = if (hazir) Metinler.t("cevrimdisi.sil") else Metinler.t("kmr.indir", mb)
    }

    private fun kmrDugmesi() {
        if (kmrIndiriyor) { kmrIptal = true; return }
        if (Modeller.omniHazir(this)) {
            if (Canli.calisiyor) { kmrDurum.visibility = View.VISIBLE; kmrDurum.text = Metinler.t("cevrimdisi.once_durdur"); return }
            Omni.kapat(); Modeller.omniSil(this); ayarlar.kurmanciOrta = false
            kmrYenile(); return
        }
        kmrIndiriyor = true; kmrIptal = false
        kmrCubuk.visibility = View.VISIBLE; kmrDurum.visibility = View.VISIBLE
        kmrYenile()
        Thread {
            var hata: String? = null
            try {
                Modeller.indir(this, { _, inen, toplam, hiz, dog, koptu ->
                    ana.post {
                        kmrCubuk.progress = if (toplam > 0) (inen * 1000 / toplam).toInt() else 0
                        kmrDurum.text = "%" + (if (toplam > 0) inen * 100 / toplam else 0) + " · " +
                            when { dog -> Metinler.t("kurulum.dogrulaniyor"); koptu -> Metinler.t("kurulum.koptu")
                                else -> Metinler.t("kurulum.indiriliyor", Metinler.t("model.omni")) } + "\n" +
                            Metinler.t("kurulum.ayrinti", (inen / 1_000_000).toInt(), (toplam / 1_000_000).toInt(), hiz / 1e6)
                    }
                }, { kmrIptal }, listOf(Modeller.OMNI_ARSIV))
                if (!kmrIptal) {
                    var son = 0L
                    Modeller.omniAc(this) { n ->
                        if (n - son > 20_000_000) { son = n; ana.post { kmrDurum.text = Metinler.t("kmr.aciliyor", (n / 1_000_000).toInt()) } }
                    }
                    ayarlar.kurmanciOrta = true
                }
            } catch (_: InterruptedException) {
            } catch (e: Exception) { hata = e.message ?: e.javaClass.simpleName }
            ana.post {
                kmrIndiriyor = false
                kmrYenile()
                if (hata != null) { kmrDurum.visibility = View.VISIBLE; kmrDurum.text = Metinler.t("kurulum.hata", hata!!) }
            }
        }.start()
    }

    // ------------------------------------------------------------------ çevrimdışı çeviri
    private lateinit var cdDurum: TextView
    private lateinit var cdCubuk: ProgressBar
    private lateinit var cdDugme: Button
    private lateinit var cdYalniz: CheckBox
    @Volatile private var cdIndiriyor = false
    @Volatile private var cdIptal = false

    private fun cevrimdisiCiz() {
        val mb = (Modeller.CEVIRI_BOYUT / 1_000_000).toInt()
        ayarlarBolum.addView(etiket(Metinler.t("cevrimdisi.baslik")).apply { setPadding(0, dp(14), 0, dp(6)) })
        ayarlarBolum.addView(yazi(Metinler.t("cevrimdisi.aciklama", mb), 12f, soluk = true))
        cdDurum = yazi("", 13f).apply { setPadding(0, dp(8), 0, dp(4)) }
        ayarlarBolum.addView(cdDurum)
        cdCubuk = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progressTintList = android.content.res.ColorStateList.valueOf(tema.vurgu); visibility = View.GONE
        }
        ayarlarBolum.addView(cdCubuk)
        cdDugme = dugme("", cizgili = true) { cevrimdisiDugme() }
        ayarlarBolum.addView(cdDugme, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(6) })
        cdYalniz = kutucuk(Metinler.t("cevrimdisi.yalniz")).apply {
            isChecked = ayarlar.cevrimdisi
            setOnCheckedChangeListener { _, b -> ayarlar.cevrimdisi = b }
        }
        ayarlarBolum.addView(cdYalniz)
        cevrimdisiYenile()
    }

    private fun cevrimdisiYenile() {
        val hazir = Nllb.hazir(this)
        val mb = (Modeller.CEVIRI_BOYUT / 1_000_000).toInt()
        cdYalniz.isEnabled = hazir
        cdYalniz.alpha = if (hazir) 1f else 0.5f
        if (cdIndiriyor) { cdDugme.text = Metinler.t("cevrimdisi.iptal"); return }
        cdCubuk.visibility = View.GONE
        cdDurum.text = if (hazir) Metinler.t("cevrimdisi.hazir") + "\n" + Metinler.t("cevrimdisi.yedek") else ""
        cdDurum.visibility = if (hazir || cdDurum.text.isNotEmpty()) View.VISIBLE else View.GONE
        cdDugme.text = if (hazir) Metinler.t("cevrimdisi.sil") else Metinler.t("cevrimdisi.indir", mb)
    }

    private fun cevrimdisiDugme() {
        if (cdIndiriyor) { cdIptal = true; return }
        if (Nllb.hazir(this)) {
            // dinleme sürerken model kullanılıyor olabilir: önce durdurulmalı
            if (Canli.calisiyor) { cdDurum.text = Metinler.t("cevrimdisi.once_durdur"); return }
            Nllb.kapat(); Modeller.ceviriSil(this); ayarlar.cevrimdisi = false; cdYalniz.isChecked = false
            cevrimdisiYenile(); return
        }
        cdIndiriyor = true; cdIptal = false
        cdCubuk.visibility = View.VISIBLE; cdDurum.visibility = View.VISIBLE
        cevrimdisiYenile()
        Thread {
            var hata: String? = null
            try {
                Modeller.indir(this, { _, inen, toplam, hiz, dog, koptu ->
                    ana.post {
                        cdCubuk.progress = if (toplam > 0) (inen * 1000 / toplam).toInt() else 0
                        cdDurum.text = "%" + (if (toplam > 0) inen * 100 / toplam else 0) + " · " +
                            when { dog -> Metinler.t("kurulum.dogrulaniyor"); koptu -> Metinler.t("kurulum.koptu")
                                else -> Metinler.t("kurulum.indiriliyor", Metinler.t("model.nllb")) } + "\n" +
                            Metinler.t("kurulum.ayrinti", (inen / 1_000_000).toInt(), (toplam / 1_000_000).toInt(), hiz / 1e6)
                    }
                }, { cdIptal }, Modeller.CEVIRI)
            } catch (_: InterruptedException) {
            } catch (e: Exception) { hata = e.message ?: e.javaClass.simpleName }
            ana.post {
                cdIndiriyor = false
                cevrimdisiYenile()
                if (hata != null) { cdDurum.visibility = View.VISIBLE; cdDurum.text = Metinler.t("kurulum.hata", hata!!) }
            }
        }.start()
    }

    private fun kurulumCiz() {
        kurulumKart = kart().apply { visibility = View.GONE }
        kurulumKart.addView(yazi(Metinler.t("kurulum.baslik"), 18f, kalin = true))
        kurulumKart.addView(yazi(Metinler.t("kurulum.aciklama"), 13f, soluk = true).apply { setPadding(0, dp(6), 0, dp(12)) })
        kurulumCubuk = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progressTintList = android.content.res.ColorStateList.valueOf(tema.vurgu)
        }
        kurulumKart.addView(kurulumCubuk)
        kurulumYazi = yazi("", 15f, kalin = true).apply { setPadding(0, dp(10), 0, 0) }
        kurulumAyrinti = yazi("", 13f, soluk = true)
        kurulumKart.addView(kurulumYazi); kurulumKart.addView(kurulumAyrinti)
        kurulumTekrar = dugme(Metinler.t("kurulum.tekrar"), birincil = true) { indirmeyiBaslat() }.apply { visibility = View.GONE }
        kurulumKart.addView(kurulumTekrar)
        kok.addView(kurulumKart)
    }

    private fun indirmeyiBaslat() {
        kurulumKart.visibility = View.VISIBLE
        kurulumTekrar.visibility = View.GONE
        indirmeIptal = false
        Thread {
            try {
                Modeller.indir(this, { m, inen, toplam, hiz, dog, koptu ->
                    ana.post {
                        kurulumCubuk.progress = if (toplam > 0) (inen * 1000 / toplam).toInt() else 0
                        kurulumYazi.text = "%" + (if (toplam > 0) inen * 100 / toplam else 0) + " · " +
                            when { dog -> Metinler.t("kurulum.dogrulaniyor"); koptu -> Metinler.t("kurulum.koptu")
                                else -> Metinler.t("kurulum.indiriliyor", Metinler.t(m.etiket)) }
                        kurulumAyrinti.text = Metinler.t("kurulum.ayrinti", (inen / 1_000_000).toInt(), (toplam / 1_000_000).toInt(), hiz / 1e6)
                    }
                }, { indirmeIptal })
                ana.post { kurulumKart.visibility = View.GONE; anaBolum.visibility = View.VISIBLE }
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                ana.post {
                    kurulumYazi.text = Metinler.t("kurulum.hata", e.message ?: e.javaClass.simpleName)
                    kurulumTekrar.visibility = View.VISIBLE
                }
            }
        }.start()
    }

    private fun anaCiz() {
        anaBolum = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }

        // diller
        val dilKart = kart()
        dilKart.addView(etiket(Metinler.t("konusulan")))
        konusmaSec = dilSecici(ayarlar.konusma) { if (it != ayarlar.konusma) { ayarlar.konusma = it; dilDegisti() } }
        dilKart.addView(konusmaSec, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        dilKart.addView(dugme("⇅") {
            val k = ayarlar.konusma; ayarlar.konusma = ayarlar.hedef; ayarlar.hedef = k
            konusmaSec.setSelection(Diller.HEPSI.indexOfFirst { it.kod == ayarlar.konusma })
            hedefSec.setSelection(Diller.HEPSI.indexOfFirst { it.kod == ayarlar.hedef })
            dilDegisti()
        }.apply { layoutParams = LinearLayout.LayoutParams(dp(56), dp(40)).apply { gravity = Gravity.CENTER; topMargin = dp(6) } })
        dilKart.addView(etiket(Metinler.t("hedef")))
        hedefSec = dilSecici(ayarlar.hedef) { if (it != ayarlar.hedef) { ayarlar.hedef = it; dilDegisti() } }
        dilKart.addView(hedefSec, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        anaBolum.addView(dilKart)

        // ses kaynağı
        val kaynakKart = kart()
        kaynakKart.addView(etiket(Metinler.t("kaynak")))
        kaynakGrup = RadioGroup(this)
        for (k in Kaynak.entries) {
            kaynakGrup.addView(RadioButton(this).apply {
                id = View.generateViewId(); tag = k
                setTextColor(tema.metin); setPadding(dp(4), dp(8), 0, dp(8))
                buttonTintList = android.content.res.ColorStateList.valueOf(tema.vurgu)
                isChecked = k == ayarlar.kaynak
                setOnCheckedChangeListener { _, b -> if (b) { ayarlar.kaynak = k; gorusmeYenile() } }
            })
        }
        kaynakKart.addView(kaynakGrup)
        kaynakKart.addView(yazi(Metinler.t("not.gorusme"), 12f, soluk = true).apply { setPadding(0, dp(8), 0, 0) })
        gorusmeKutu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setStroke(dp(1), tema.vurgu); setColor(tema.vurgu and 0x18FFFFFF) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        }
        gorusmeKutu.addView(yazi(Metinler.t("gorusme.baslik"), 14f, kalin = true))
        gorusmeDurum = yazi("", 13f).apply { setPadding(0, dp(4), 0, dp(4)) }
        gorusmeKutu.addView(gorusmeDurum)
        gorusmeKutu.addView(yazi(Metinler.t("gorusme.aciklama"), 12f, soluk = true))
        gorusmeKutu.addView(dugme(Metinler.t("gorusme.ac"), cizgili = true) {
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(8) } })
        gorusmeKutu.addView(yazi(Metinler.t("gorusme.kisitli"), 12f, soluk = true).apply { setPadding(0, dp(8), 0, 0) })
        gorusmeKutu.addView(dugme(Metinler.t("gorusme.uygulama_bilgisi")) {
            runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        }.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(6) } })
        kaynakKart.addView(gorusmeKutu)
        gorusmeYenile()
        seritKutu = kutucuk(Metinler.t("serit")).apply {
            setOnClickListener {
                // izin yoksa ister; izni geri almak sistem ayarlarından yapılır
                isChecked = Ust.izinVar(this@MainActivity)
                if (!Ust.izinVar(this@MainActivity)) Toast.makeText(this@MainActivity, Metinler.t("serit.izin"), Toast.LENGTH_LONG).show()
                ustIzni.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        kaynakKart.addView(seritKutu)
        anaBolum.addView(kaynakKart)

        // başlat
        baslatDugme = dugme(Metinler.t("baslat"), birincil = true) {
            if (calisiyor) AltyaziServisi.gonder(this, AltyaziServisi.DURDUR)
            else startActivity(Intent(this, BaslatEtkinligi::class.java))
        }.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)).apply { topMargin = dp(16) }
        }
        anaBolum.addView(baslatDugme)
        durumYazi = yazi(Metinler.t("durum.hazir"), 13f, soluk = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(4)) }
        anaBolum.addView(durumYazi)
        seviyeCubuk = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 60; progressTintList = android.content.res.ColorStateList.valueOf(tema.vurgu2)
        }
        anaBolum.addView(seviyeCubuk)

        // kontrol çubuğu (toplantı sırasında diğer uygulamaların üstünde)
        val cubukKart = kart()
        cubukKart.addView(dugme("⠿  " + Metinler.t("cubuk.ac"), cizgili = true) { cubuguAc() })
        cubukKart.addView(yazi(Metinler.t("cubuk.aciklama"), 12f, soluk = true).apply { setPadding(0, dp(8), 0, 0) })
        anaBolum.addView(cubukKart)

        // son altyazı
        val sonKart = kart()
        sonKart.addView(etiket(Metinler.t("son")))
        sonCeviri = yazi("—", 20f, kalin = true)
        sonOrijinal = yazi("", 14f, soluk = true)
        sonKart.addView(sonCeviri); sonKart.addView(sonOrijinal)
        anaBolum.addView(sonKart)

        // geçmiş
        val gecmisKart = kart()
        gecmisKart.addView(etiket(Metinler.t("gecmis")))
        val dugmeler = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(4)) }
        dugmeler.addView(dugme("⬇  " + Metinler.t("indir"), cizgili = true) { indir() },
            LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(8) })
        dugmeler.addView(dugme("🗑  " + Metinler.t("temizle"), cizgili = true) { temizle() },
            LinearLayout.LayoutParams(0, dp(46), 1f))
        gecmisKart.addView(dugmeler)
        gecmisListe = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        gecmisKart.addView(gecmisListe)
        anaBolum.addView(gecmisKart)

        kok.addView(anaBolum)
        gecmisCiz()
    }

    /** Dinleme sürerken dil değişirse servis yeni dillere geçer (ses izni yeniden istenmez). */
    private fun dilDegisti() { if (calisiyor) AltyaziServisi.gonder(this, AltyaziServisi.DIL) }

    private fun cubuguAc() {
        if (!Ust.izinVar(this)) {
            cubukIstendi = true
            Toast.makeText(this, Metinler.t("serit.izin"), Toast.LENGTH_LONG).show()
            ustIzni.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        androidx.core.content.ContextCompat.startForegroundService(this,
            Intent(this, AltyaziServisi::class.java).setAction(AltyaziServisi.CUBUK_AC))
        // çubuk diğer uygulamaların üstünde görünür: kullanıcıyı ana ekrana / toplantıya bırak
        moveTaskToBack(true)
    }

    /** Görüşme kutusu: yalnız mikrofon kaynaklarında görünür; erişilebilirlik hizmeti açık mı gösterir. */
    private fun gorusmeYenile() {
        if (!::gorusmeKutu.isInitialized) return
        gorusmeKutu.visibility = if (ayarlar.kaynak == Kaynak.MEDYA) View.GONE else View.VISIBLE
        val acik = AltyaziErisilebilirlik.acikMi(this)
        gorusmeDurum.text = Metinler.t(if (acik) "gorusme.acik" else "gorusme.kapali")
        gorusmeDurum.setTextColor(if (acik) tema.vurgu else tema.metin)
    }

    private fun kaynaklariYenile() {
        gorusmeYenile()
        if (!::kaynakGrup.isInitialized) return
        for (i in 0 until kaynakGrup.childCount) {
            val r = kaynakGrup.getChildAt(i) as RadioButton
            val k = r.tag as Kaynak
            val bagli = Aygitlar.bagliMi(this, k)
            r.text = Metinler.t("kaynak.${k.name}") + (if (bagli) "" else "  (${Metinler.t("bagli_degil")})") +
                "\n" + Metinler.t("kaynak.${k.name}.a")
            r.alpha = if (bagli) 1f else 0.5f
        }
    }

    private fun satirGorunumu(s: Canli.Satir): View {
        val k = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, dp(4)) }
        k.addView(yazi(DateFormat.getTimeFormat(this).format(Date(s.zaman)) + " · " + Metinler.dilAdi(s.dil), 11f, soluk = true))
        k.addView(yazi(s.ceviri ?: s.orijinal, 16f, kalin = true).apply {
            if (Diller.bul(if (s.ceviri != null) s.hedef else s.dil).rtl) textDirection = View.TEXT_DIRECTION_RTL
        })
        if (s.ceviri != null && s.ceviri != s.orijinal) k.addView(yazi(s.orijinal, 13f, soluk = true))
        return k
    }

    private fun gecmisCiz() {
        gecmisListe.removeAllViews()
        if (Canli.gecmis.isEmpty()) { gecmisListe.addView(yazi(Metinler.t("gecmis.bos"), 13f, soluk = true)); return }
        for (s in Canli.gecmis.takeLast(200).asReversed()) gecmisListe.addView(satirGorunumu(s))
    }

    /** Konuşma geçmişini telefonun İndirilenler klasörüne metin dosyası olarak kaydeder (izin gerekmez). */
    private fun indir() {
        if (Canli.gecmis.isEmpty()) { Toast.makeText(this, Metinler.t("indir.bos"), Toast.LENGTH_SHORT).show(); return }
        val saat = DateFormat.getTimeFormat(this)
        val gun = java.text.SimpleDateFormat("yyyy-MM-dd HH-mm", java.util.Locale.US).format(Date())
        val ad = "MED LIVE TRANSLATE $gun.txt"
        val ilk = Canli.gecmis.first()
        val metin = buildString {
            append('﻿')  // Windows Not Defteri de Türkçe / Kürtçe harfleri doğru göstersin
            append(Metinler.t("indir.baslik")).append("\r\n")
            append(java.text.DateFormat.getDateInstance().format(Date(ilk.zaman))).append(" · ")
            append(Metinler.dilAdi(ilk.dil)).append(" → ").append(Metinler.dilAdi(ilk.hedef)).append("\r\n\r\n")
            for (s in Canli.gecmis) {
                append(saat.format(Date(s.zaman))).append("\r\n")
                append(s.ceviri ?: s.orijinal).append("\r\n")
                if (s.ceviri != null && s.ceviri != s.orijinal) append(s.orijinal).append("\r\n")
                append("\r\n")
            }
        }
        try {
            val degerler = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, ad)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, degerler)
                ?: throw IllegalStateException("MediaStore")
            contentResolver.openOutputStream(uri)!!.use { it.write(metin.toByteArray(Charsets.UTF_8)) }
            degerler.clear(); degerler.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
            contentResolver.update(uri, degerler, null, null)
            Toast.makeText(this, Metinler.t("indirildi", ad), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, Metinler.t("indir.hata", e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
        }
    }

    private fun temizle() {
        if (Canli.gecmis.isEmpty()) { Toast.makeText(this, Metinler.t("indir.bos"), Toast.LENGTH_SHORT).show(); return }
        Canli.gecmis.clear()
        gecmisCiz()
        sonCeviri.text = "—"; sonOrijinal.text = ""
        Toast.makeText(this, Metinler.t("temizlendi"), Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------ servisten gelenler
    override fun durum(metin: String, calisiyor: Boolean) {
        if (!::baslatDugme.isInitialized) return
        this.calisiyor = calisiyor
        durumYazi.text = metin.ifEmpty { Metinler.t("durum.hazir") }
        baslatDugme.text = Metinler.t(if (calisiyor) "durdur" else "baslat")
        (baslatDugme.background as GradientDrawable).setColor(if (calisiyor) Color.rgb(200, 55, 60) else tema.vurgu)
        baslatDugme.setTextColor(if (calisiyor) Color.WHITE else tema.vurguYazi)
        if (!calisiyor) seviyeCubuk.progress = 0
        // dil dinlerken de değişebilir; ses kaynağı yalnızca dururken
        for (i in 0 until kaynakGrup.childCount) kaynakGrup.getChildAt(i).isEnabled = !calisiyor
        // kontrol çubuğundan dil değiştiyse seçiciler güncellensin
        konusmaSec.setSelection(Diller.HEPSI.indexOfFirst { it.kod == ayarlar.konusma })
        hedefSec.setSelection(Diller.HEPSI.indexOfFirst { it.kod == ayarlar.hedef })
    }

    override fun altyazi(s: Canli.Satir) {
        sonCeviri.text = s.ceviri ?: s.orijinal
        sonOrijinal.text = if (s.ceviri != null && s.ceviri != s.orijinal) s.orijinal else ""
        if (gecmisListe.childCount == 1 && gecmisListe.getChildAt(0) is TextView) gecmisListe.removeAllViews()
        gecmisListe.addView(satirGorunumu(s), 0)
        if (gecmisListe.childCount > 200) gecmisListe.removeViewAt(gecmisListe.childCount - 1)
    }

    override fun seviye(db: Double) {
        seviyeCubuk.progress = (db + 60).toInt().coerceIn(0, 60)
    }

    @Suppress("unused") private fun bosluk() = FrameLayout(this)
}
