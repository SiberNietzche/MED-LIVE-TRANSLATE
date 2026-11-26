package com.canlialtyazi.app

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

const val ORNEKLEME = 16000

/**
 * Ses seviyesini ayarlar (yerinde): telefon mikrofonu çoğu zaman kısık kaydeder, Whisper kısık seste
 * yanlış duyuyor. Ortalama seviye ~-20 dBFS'e çekilir; en çok 24 dB yükseltilir, tepe 0,95'i aşmaz.
 */
fun seviyeAyarla(ses: FloatArray) {
    if (ses.isEmpty()) return
    var toplam = 0.0
    var tepe = 0f
    for (x in ses) { toplam += x * x; val a = kotlin.math.abs(x); if (a > tepe) tepe = a }
    val rms = sqrt(toplam / ses.size)
    if (rms < 1e-5 || tepe < 1e-4) return
    val kazanc = minOf(0.1 / rms, 15.85, 0.95 / tepe)
    if (kazanc <= 1.05) return
    val k = kazanc.toFloat()
    for (i in ses.indices) ses[i] *= k
}

/**
 * Sürekli sesi konuşma bölümlerine ayırır (enerji tabanlı, gürültü düzeyine uyum sağlar).
 * Bir bölüm, konuşmadan sonra yeterli sessizlik gelince ya da en uzun süreye ulaşınca kapanır;
 * sınırda en sessiz yerden bölünür, kalan kısım sonraki bölüme geçer (kelime ortasından kesmesin).
 */
class Bolucu(private val kmr: Boolean, private val bolumHazir: (FloatArray) -> Unit) {
    private val cerceve = 480                                   // 30 ms
    // telefonda gecikme için daha kısa bölümler (masaüstünde 0,8 / 0,7 sn ve 12-20 sn)
    private val sessizlikSiniri = if (kmr) 0.65 else 0.55      // sn
    /** En uzun bölüm (sn). Telefon yetişemiyorsa servis kısaltır (hafif kademe): parçalar birikmesin. */
    @Volatile var enUzun = 9.0
    private val enKisa = 0.6                                    // sn
    private var taban = -60.0                                   // gürültü düzeyi (dB)
    private val bekleyen = FloatArray(cerceve)
    private var bekleyenN = 0
    private val bolum = ArrayList<FloatArray>()                 // çerçeveler
    private val enerjiler = ArrayList<Double>()
    private val onceki = ArrayDeque<FloatArray>()               // konuşma başlamadan önceki 300 ms
    private var konusuyor = false
    private var sesliArt = 0
    private var sessizArt = 0
    var seviyeDb = -100.0; private set

    fun ekle(ornekler: FloatArray, n: Int = ornekler.size) {
        var i = 0
        while (i < n) {
            val al = minOf(cerceve - bekleyenN, n - i)
            System.arraycopy(ornekler, i, bekleyen, bekleyenN, al)
            bekleyenN += al; i += al
            if (bekleyenN == cerceve) { cerceveIsle(bekleyen.copyOf()); bekleyenN = 0 }
        }
    }

    private fun cerceveIsle(c: FloatArray) {
        var t = 0.0
        for (x in c) t += x * x
        val db = 20 * log10(sqrt(t / c.size) + 1e-9)
        seviyeDb = db
        // gürültü düzeyi: aşağı hızlı, yukarı yavaş iner/çıkar
        taban = if (db < taban) taban * 0.9 + db * 0.1 else taban * 0.998 + db * 0.002
        val sesli = db > max(taban + 10.0, -50.0)
        if (!konusuyor) {
            onceki.addLast(c); if (onceki.size > 10) onceki.removeFirst()
            sesliArt = if (sesli) sesliArt + 1 else 0
            if (sesliArt >= 3) {
                konusuyor = true; sessizArt = 0
                for (o in onceki) { bolum.add(o); enerjiler.add(-100.0) }
                onceki.clear()
            }
            return
        }
        bolum.add(c); enerjiler.add(db)
        sessizArt = if (sesli) 0 else sessizArt + 1
        val sure = bolum.size * cerceve / ORNEKLEME.toDouble()
        val sessizSure = sessizArt * cerceve / ORNEKLEME.toDouble()
        // yarıyı geçince daha kısa duraklama yeter (uzun cümleleri doğal yerinden böler)
        val gerekli = if (kmr && sure > enUzun / 2) 0.3 else sessizlikSiniri
        when {
            sessizSure >= gerekli -> kapat(bolum.size)
            sure >= enUzun -> {
                // son yarının en sessiz çerçevesinden böl
                val bas = bolum.size / 2
                var enIyi = bolum.size - 1
                for (j in bas until bolum.size) if (enerjiler[j] < enerjiler[enIyi]) enIyi = j
                kapat(enIyi + 1)
            }
        }
    }

    private fun kapat(son: Int) {
        val parcalar = bolum.subList(0, son).toList()
        val kalan = bolum.subList(son, bolum.size).toList()
        val kalanE = enerjiler.subList(son, enerjiler.size).toList()
        bolum.clear(); enerjiler.clear()
        if (kalan.isNotEmpty()) { bolum.addAll(kalan); enerjiler.addAll(kalanE) } else konusuyor = false
        sessizArt = 0; sesliArt = 0
        if (parcalar.size * cerceve / ORNEKLEME.toDouble() < enKisa) return
        val ses = FloatArray(parcalar.size * cerceve)
        parcalar.forEachIndexed { k, p -> System.arraycopy(p, 0, ses, k * cerceve, cerceve) }
        bolumHazir(ses)
    }

    fun sifirla() { bolum.clear(); enerjiler.clear(); onceki.clear(); konusuyor = false; bekleyenN = 0 }
}

/** Whisper'ın sessizlikte / müzikte sık uydurduğu cümleler ve döngü tekrarları (masaüstüyle aynı kurallar). */
object Temizle {
    private val HALUSINASYON = Regex(
        "(altyaz[ıi]\\s*m\\.?\\s*k\\.?|abone ol|izledi[gğ]iniz i[çc]in te[şs]ekk|" +
            "thanks? (you )?for watching|subscribe|amara\\.org|" +
            "ترجمة نانسي|اشترك في القناة|^\\W*$|" +
            "^\\W*(you|thank you|thanks|bye|okay|hmm+|teşekkürler|شكرا)\\W*$)",
        RegexOption.IGNORE_CASE,
    )
    private const val NOKTALAMA = ".,;:!?…\"'“”‘’«»()[]-–—،؛؟"

    fun uydurmaMi(metin: String) = HALUSINASYON.containsMatchIn(metin.trim())

    fun tekrar(metin: String, enUzunBirim: Int = 8): String {
        var kelimeler = metin.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (kelimeler.size < 4) return metin
        for (n in 1..enUzunBirim) {
            val sinir = if (n == 1) 4 else 3
            if (kelimeler.size < n * sinir) continue
            val sade = kelimeler.map { it.trim { c -> c in NOKTALAMA }.lowercase() }
            val cikti = ArrayList<String>()
            var i = 0
            while (i < kelimeler.size) {
                val birim = sade.subList(i, minOf(i + n, sade.size))
                var tekrar = 1
                if (birim.size == n && birim.any { it.isNotEmpty() }) {
                    while ((i + (tekrar + 1) * n) <= sade.size && sade.subList(i + tekrar * n, i + (tekrar + 1) * n) == birim) tekrar++
                }
                if (tekrar >= sinir) { cikti.addAll(kelimeler.subList(i, i + n)); i += tekrar * n }
                else { cikti.add(kelimeler[i]); i++ }
            }
            kelimeler = cikti
        }
        return kelimeler.joinToString(" ")
    }
}

/**
 * Google Çeviri (anahtarsız uç nokta). Hata olursa artan aralıklarla bekler. Google'a ulaşılamazsa ve
 * çevrimdışı model indirilmişse NLLB ile çevrilir (masaüstüyle aynı); o da yoksa altyazı çevirisiz devam eder.
 */
class Cevirmen(private val baglam: android.content.Context) {
    private var sonIstek = 0L
    private var kapaliKadar = 0L
    private var bekleme = 0L
    private val onbellek = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, String>?) = size > 300
    }
    var sonHata: String? = null; private set

    /** Son çeviriyi yapan: "google" ya da "yerel" */
    var sonKaynak = "google"; private set

    /** yalnizYerel: çevrimdışı mod — hiçbir metin internete gönderilmez. */
    fun cevir(metin: String, kaynak: String, hedef: String, yalnizYerel: Boolean = false): String? {
        if (kaynak == hedef || metin.isBlank()) return metin
        val g = if (yalnizYerel) null else google(metin, kaynak, hedef)
        if (g != null) { sonKaynak = "google"; return g }
        // yerel sonuç önbelleğe alınmaz: Google dönünce aynı cümle onunla çevrilsin
        val y = runCatching { Nllb.cevir(baglam, metin, kaynak, hedef) }.getOrNull()?.let { Temizle.tekrar(it) }
        if (y.isNullOrEmpty()) return null
        sonKaynak = "yerel"
        return y
    }

    @Synchronized
    private fun google(metin: String, kaynak: String, hedef: String): String? {
        val anahtar = "$kaynak>$hedef>$metin"
        onbellek[anahtar]?.let { return it }
        val simdi = System.currentTimeMillis()
        if (simdi < kapaliKadar) return null
        val bekle = 300 - (simdi - sonIstek)
        if (bekle > 0) Thread.sleep(bekle)
        sonIstek = System.currentTimeMillis()
        return try {
            val adres = "https://translate.googleapis.com/translate_a/single?client=gtx&dt=t" +
                "&sl=${Diller.bul(kaynak).google}&tl=${Diller.bul(hedef).google}&q=" +
                URLEncoder.encode(metin.take(1500), "UTF-8")
            val bag = (URL(adres).openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000; readTimeout = 5000
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            if (bag.responseCode != 200) throw IllegalStateException("HTTP ${bag.responseCode}")
            val govde = bag.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val dizi = JSONArray(govde).getJSONArray(0)
            val sb = StringBuilder()
            for (k in 0 until dizi.length()) dizi.optJSONArray(k)?.optString(0)?.let { if (it != "null") sb.append(it) }
            val sonuc = Temizle.tekrar(sb.toString().trim())
            if (sonuc.isEmpty()) throw IllegalStateException("boş yanıt")
            bekleme = 0; sonHata = null
            onbellek[anahtar] = sonuc
            sonuc
        } catch (e: Exception) {
            // 10 sn'den 5 dk'ya kadar artan ara (gizlilik: istek adresi / cümle günlüğe yazılmaz)
            bekleme = minOf(300_000L, maxOf(10_000L, bekleme * 2))
            kapaliKadar = System.currentTimeMillis() + bekleme
            sonHata = e.javaClass.simpleName
            null
        }
    }
}
