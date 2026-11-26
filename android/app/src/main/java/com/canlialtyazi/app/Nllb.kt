package com.canlialtyazi.app

import android.content.Context
import android.util.Log

/**
 * Çevrimdışı çeviri: NLLB-200 600M (CTranslate2 int8) — masaüstündeki çevrimdışı çeviriyle aynı model.
 * Model isteğe bağlıdır (Ayarlar'dan indirilir, ~625 MB). İlk gerektiğinde yüklenir; bellekte ~0,7 GB tutar,
 * bu yüzden dinleme bitince bırakılır.
 */
object Nllb {
    private const val ETIKET = "Nllb"
    private var kitaplik: Boolean? = null
    private var tutamac = 0L

    private external fun yukle(klasor: String, spm: String, isParcacigi: Int): Long
    private external fun cevir(tutamac: Long, cumleler: Array<ByteArray>, kaynak: String, hedef: String): Array<ByteArray>?
    private external fun birak(tutamac: Long)

    // cümle sonu: . ! ? … ؟ ve ardından boşluk (masaüstüyle aynı)
    private val CUMLE_SONU = Regex("(?<=[.!?…؟])\\s+")
    private const val EN_UZUN = 1500

    fun hazir(c: Context) = Modeller.CEVIRI.all { Modeller.hazir(c, it) }

    private fun kitaplikYukle(): Boolean {
        kitaplik?.let { return it }
        val ok = runCatching { System.loadLibrary("mlt_ceviri") }.onFailure { Log.e(ETIKET, "kitaplık yüklenemedi", it) }.isSuccess
        kitaplik = ok
        return ok
    }

    /** Önceden yükler (çevrimdışı modda ilk cümle beklemesin). */
    @Synchronized
    fun hazirla(c: Context): Boolean {
        if (tutamac != 0L) return true
        if (!hazir(c) || !kitaplikYukle()) return false
        val t0 = System.currentTimeMillis()
        tutamac = yukle(Modeller.nllbKlasoru(c).path, Modeller.dosya(c, Modeller.NLLB_SPM).path, 2)
        Log.i(ETIKET, "yükleme ${System.currentTimeMillis() - t0} ms, başarılı: ${tutamac != 0L}")
        return tutamac != 0L
    }

    /** kaynak/hedef: uygulamanın dil kodları (kmr, tr, en, ar). Çevrilemezse null. */
    @Synchronized
    fun cevir(c: Context, metin: String, kaynak: String, hedef: String): String? {
        if (!hazirla(c)) return null
        val cumleler = metin.trim().take(EN_UZUN).split(CUMLE_SONU).map { it.trim() }.filter { it.isNotEmpty() }
        if (cumleler.isEmpty()) return null
        val t0 = System.currentTimeMillis()
        val sonuc = cevir(tutamac, cumleler.map { it.toByteArray(Charsets.UTF_8) }.toTypedArray(),
            Diller.bul(kaynak).nllb, Diller.bul(hedef).nllb) ?: return null
        Log.i(ETIKET, "çeviri ${System.currentTimeMillis() - t0} ms (${cumleler.size} cümle)")
        return sonuc.map { String(it, Charsets.UTF_8).trim() }.filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { null }
    }

    @Synchronized
    fun kapat() {
        if (tutamac != 0L) { birak(tutamac); tutamac = 0L }
    }
}
