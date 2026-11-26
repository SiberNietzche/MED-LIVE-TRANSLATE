package com.canlialtyazi.app

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineOmnilingualAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import java.io.File

/**
 * Kürtçe Orta: Meta Omnilingual ASR CTC (sherpa-onnx, işlemcide). Masaüstündeki canli_altyazi/tanima.py ile aynı kurallar.
 * CTC modeli Whisper gibi kelime kelime üretmez: uydurma cümle / tekrar döngüsü olmaz; noktalama koymaz.
 * Dili zorlanamayan çok dilli bir model: kısa sözleri ("merheba") Arap/Uygur harfleriyle yazabilir → [ayikla].
 */
object Omni {
    private const val ETIKET = "Omni"
    private var tanici: OfflineRecognizer? = null

    /** Modeli yükler (~2–5 sn); yüklenemezse false. */
    @Synchronized
    fun hazirla(c: Context, isParcacigi: Int): Boolean {
        if (tanici != null) return true
        if (!Modeller.omniHazir(c)) return false
        val k = Modeller.omniKlasoru(c)
        return runCatching {
            val ayar = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    omnilingual = OfflineOmnilingualAsrCtcModelConfig(File(k, "model.int8.onnx").path),
                    tokens = File(k, "tokens.txt").path,
                    numThreads = isParcacigi,
                    provider = "cpu",
                ),
            )
            tanici = OfflineRecognizer(config = ayar)
            true
        }.getOrElse { Log.e(ETIKET, "model yüklenemedi", it); false }
    }

    /** 16 kHz ses → metin (cümle başı büyük harf; model küçük harf yazar). */
    @Synchronized
    fun coz(ses: FloatArray): String {
        val t = tanici ?: return ""
        val akis = t.createStream()
        try {
            akis.acceptWaveform(ses, 16000)
            t.decode(akis)
            return t.getResult(akis).text.trim()
        } finally {
            akis.release()
        }
    }

    @Synchronized
    fun kapat() { tanici?.release(); tanici = null }

    private fun latinDisi(kelime: String) = kelime.count {
        it.isLetter() && Character.UnicodeScript.of(it.code) != Character.UnicodeScript.LATIN
    }

    /** Latin dışı kelimeleri atar; metnin çoğu öyleyse ikinci değer true (parça Kürtçe Küçük ile yeniden çözülmeli). */
    fun ayikla(metin: String): Pair<String, Boolean> {
        val kelimeler = metin.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val yabanci = kelimeler.filter { latinDisi(it) > 0 }
        if (yabanci.isEmpty()) return metin to false
        val temiz = kelimeler.filter { latinDisi(it) == 0 }
        val harfT = temiz.sumOf { k -> k.count { it.isLetter() } }
        val harfY = yabanci.sumOf { k -> k.count { it.isLetter() } }
        return temiz.joinToString(" ") to (harfY >= harfT)
    }

    fun buyukBasla(s: String) = if (s.isEmpty()) s else s.substring(0, 1).uppercase(java.util.Locale.ROOT) + s.substring(1)
}
