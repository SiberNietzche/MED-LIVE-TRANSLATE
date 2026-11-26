package com.canlialtyazi.app

import android.content.Context
import android.graphics.Color
import java.io.File
import java.util.Locale

/** whisper.cpp (JNI). Bir model tutamacı yalnızca tek bir iş parçacığından kullanılmalı. */
object Whisper {
    init { System.loadLibrary("mlt_whisper") }
    /** İşlemci sürümlerini yükler (uygulamanın kitaplık klasöründen; en hızlı uyumlu olan seçilir). */
    external fun arkaUclariYukle(klasor: String)
    external fun yukle(yol: String): Long
    /** vad: Silero konuşma algılayıcı modelinin yolu ("" ise kullanılmaz).
     *  pencere: kodlayıcı penceresi (audio_ctx; 0 = tam 30 sn), bkz. [pencere]. */
    external fun coz(tutamac: Long, ses: FloatArray, dil: String, isParcacigi: Int, vad: String, pencere: Int): String

    /** Parçanın süresine göre kodlayıcı penceresi: süre + pay (saniyede 50 birim, 32'nin katı, en çok 1500). */
    fun pencere(ornekSayisi: Int, paySn: Double): Int =
        minOf(1500, kotlin.math.ceil((ornekSayisi / ORNEKLEME.toDouble() + paySn) * 50 / 32).toInt() * 32)
    external fun birak(tutamac: Long)
    external fun sistemBilgisi(): String

    /** Konuşma algılayıcı modeli APK'nın içinde gelir; whisper.cpp dosya yolu istediği için bir kez diske çıkarılır. */
    fun vadYolu(c: Context): String {
        val ad = "ggml-silero-v5.1.2.bin"
        val f = File(c.filesDir, ad)
        if (!f.exists() || f.length() == 0L) runCatching {
            c.assets.open(ad).use { g -> f.outputStream().use { g.copyTo(it) } }
        }.onFailure { return "" }
        return f.path
    }

    /** Hızlı (büyük) çekirdek sayısı: en yüksek frekansın %80'ine çıkabilen çekirdekler. Küçük çekirdekler
     *  iş parçacığı olarak eklenince hızlananlar onları beklediği için toplam yavaşlıyor. */
    fun isParcacigi(): Int {
        val frekanslar = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { i ->
            runCatching { File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull()
        }
        val hizli = if (frekanslar.isEmpty()) 4 else frekanslar.count { it >= frekanslar.max() * 0.8 }
        return hizli.coerceIn(2, 6)
    }
}

/** Desteklenen diller: hepsi birbirine çevrilir (masaüstündeki canli_altyazi/diller.py ile aynı). */
/** nllb: çevrimdışı çeviri modelinin (NLLB-200) dil kodu */
data class Dil(val kod: String, val whisper: String, val google: String, val nllb: String, val rtl: Boolean = false)

object Diller {
    // Kurmancî modeli Whisper'ın "tr" etiketiyle eğitilmiş
    val HEPSI = listOf(
        Dil("kmr", "tr", "ku", "kmr_Latn"),
        Dil("tr", "tr", "tr", "tur_Latn"),
        Dil("en", "en", "en", "eng_Latn"),
        Dil("ar", "ar", "ar", "arb_Arab", rtl = true),
    )
    fun bul(kod: String) = HEPSI.firstOrNull { it.kod == kod } ?: HEPSI[0]
    /** Kısa gösterim (kontrol çubuğu): KMR / TR / EN / AR */
    fun kisa(kod: String) = if (kod == "kmr") "KMR" else kod.uppercase()
    fun sonraki(kod: String) = HEPSI[(HEPSI.indexOfFirst { it.kod == kod } + 1) % HEPSI.size].kod
}

/** Ses kaynakları (kullanıcı isteği: medya sesi + telefon / kablolu / Bluetooth kulaklık mikrofonu). */
enum class Kaynak { MEDYA, TELEFON_MIK, KABLOLU_MIK, BLUETOOTH_MIK }

/** Tema: masaüstündeki 5 temanın aynı renkleri (masaustu/src/ortak.ts → TEMALAR). */
data class Tema(
    val kod: String, val acik: Boolean,
    val arka: Int, val panel: Int, val kenar: Int, val metin: Int, val soluk: Int,
    val vurgu: Int, val vurgu2: Int, val tehlike: Int, val serit: Int,
) {
    /** Vurgu renginin üstündeki yazı: açık renkli vurguda koyu, koyu vurguda beyaz. */
    val vurguYazi: Int
        get() {
            val p = (0.299 * Color.red(vurgu) + 0.587 * Color.green(vurgu) + 0.114 * Color.blue(vurgu)) / 255
            return if (p > 0.6) Color.rgb(20, 16, 10) else Color.WHITE
        }
}

object Temalar {
    private fun c(s: String) = Color.parseColor(s)
    val HEPSI = listOf(
        Tema("nebula", false, c("#07060F"), c("#181430"), c("#33A78BFA"), c("#EFEAFF"), c("#A39CD0"),
            c("#8B5CF6"), c("#22D3EE"), c("#FB7185"), Color.rgb(14, 11, 30)),
        Tema("aurora", false, c("#03121A"), c("#0A2630"), c("#382DD4BF"), c("#E6FBF6"), c("#8EC5BD"),
            c("#10B981"), c("#38BDF8"), c("#FB7185"), Color.rgb(4, 22, 30)),
        Tema("kor", false, c("#110706"), c("#30140E"), c("#38FB923C"), c("#FFF1EA"), c("#D4A493"),
            c("#F97316"), c("#F43F5E"), c("#F43F5E"), Color.rgb(28, 10, 8)),
        Tema("altin", false, c("#09090B"), c("#201E1A"), c("#38EAB308"), c("#FAFAF9"), c("#B8B3A6"),
            c("#EAB308"), c("#E7E5E4"), c("#EF4444"), Color.rgb(18, 17, 15)),
        Tema("buzul", true, c("#E9F0FA"), c("#FFFFFF"), c("#2E2563EB"), c("#0F172A"), c("#51607A"),
            c("#2563EB"), c("#06B6D4"), c("#E11D48"), Color.rgb(15, 23, 42)),
    )
    fun bul(kod: String) = HEPSI.firstOrNull { it.kod == kod } ?: HEPSI[3]
}

/** Ayarlar (telefonda kalıcı). */
class Ayarlar(context: Context) {
    private val p = context.getSharedPreferences("ayarlar", Context.MODE_PRIVATE)
    var konusma: String
        get() = p.getString("konusma", "en")!!
        set(v) = p.edit().putString("konusma", v).apply()
    var hedef: String
        get() = p.getString("hedef", "tr")!!
        set(v) = p.edit().putString("hedef", v).apply()
    var kaynak: Kaynak
        get() = runCatching { Kaynak.valueOf(p.getString("kaynak", "MEDYA")!!) }.getOrDefault(Kaynak.MEDYA)
        set(v) = p.edit().putString("kaynak", v.name).apply()
    var arayuzDili: String
        get() = p.getString("arayuz", null) ?: varsayilanArayuz()
        set(v) = p.edit().putString("arayuz", v).apply()
    var tema: Tema
        get() = Temalar.bul(p.getString("tema", "altin")!!)
        set(v) = p.edit().putString("tema", v.kod).apply()
    var yaziBoyutu: Int
        get() = p.getInt("yazi", 20)
        set(v) = p.edit().putInt("yazi", v).apply()
    var orijinalGoster: Boolean
        get() = p.getBoolean("orijinal", true)
        set(v) = p.edit().putBoolean("orijinal", v).apply()
    /** Çevrimdışı çeviri: hiçbir metin internete gönderilmez (model indirilmiş olmalı). */
    var cevrimdisi: Boolean
        get() = p.getBoolean("cevrimdisi", false)
        set(v) = p.edit().putBoolean("cevrimdisi", v).apply()
    /** Kürtçe Orta (Omnilingual) seçili mi; model indirilmemişse Kürtçe Küçük kullanılır. */
    var kurmanciOrta: Boolean
        get() = p.getBoolean("kurmanci_orta", false)
        set(v) = p.edit().putBoolean("kurmanci_orta", v).apply()
    var seritAcik: Boolean
        get() = p.getBoolean("serit_acik", true)
        set(v) = p.edit().putBoolean("serit_acik", v).apply()
    var seritKonumY: Int
        get() = p.getInt("serit_y", -1)
        set(v) = p.edit().putInt("serit_y", v).apply()
    var kilavuzGoruldu: Boolean
        get() = p.getBoolean("kilavuz_goruldu", false)
        set(v) = p.edit().putBoolean("kilavuz_goruldu", v).apply()
    /** Telefonun hızına göre kendiliğinden seçilen kademe: 1 = normal, 2 = hafif (yetişemeyen telefonlar). */
    var hizKademesi: Int
        get() = p.getInt("hiz_kademesi", 1)
        set(v) = p.edit().putInt("hiz_kademesi", v).apply()
    var cubukX: Int
        get() = p.getInt("cubuk_x", 0)
        set(v) = p.edit().putInt("cubuk_x", v).apply()
    var cubukY: Int
        get() = p.getInt("cubuk_y", -1)
        set(v) = p.edit().putInt("cubuk_y", v).apply()
    /** GitHub'dan yeni sürüm kendiliğinden denetlensin (6 saatte bir). */
    var guncellemeOtomatik: Boolean
        get() = p.getBoolean("guncelleme_oto", true)
        set(v) = p.edit().putBoolean("guncelleme_oto", v).apply()
    var sonDenetim: Long
        get() = p.getLong("guncelleme_son", 0)
        set(v) = p.edit().putLong("guncelleme_son", v).apply()
    /** Son denetimde bulunan yeni sürüm ("" = yok): ana ekrandaki şerit için. */
    var sonBulunan: String
        get() = p.getString("guncelleme_bulunan", "")!!
        set(v) = p.edit().putString("guncelleme_bulunan", v).apply()
    var bildirilenSurum: String
        get() = p.getString("guncelleme_bildirilen", "")!!
        set(v) = p.edit().putString("guncelleme_bildirilen", v).apply()

    private fun varsayilanArayuz(): String = when (Locale.getDefault().language) {
        "tr" -> "tr"
        "ku", "kmr" -> "kmr"
        else -> "en"
    }
}
