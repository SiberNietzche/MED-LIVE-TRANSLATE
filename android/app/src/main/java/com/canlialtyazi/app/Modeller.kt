package com.canlialtyazi.app

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Kurmancî modelinin yüklendiği HF commit'i (adres bu sürüme sabitlenir). */
const val KMR_COMMIT = "41cbfc37ff8f820c083b365afe09807c2eb64535"

/** Kürtçe Orta: Omnilingual ASR 1B CTC v2 int8 (masaüstüyle aynı; 300M sürümü FLEURS'ta Kürtçe Küçük'ten iyi değil). */
const val OMNI_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
    "sherpa-onnx-omnilingual-asr-1600-languages-1B-ctc-v2-int8-2026-02-05.tar.bz2"
const val OMNI_BOYUT = 787296506L
const val OMNI_SHA256 = "f4deae6e6cbf4ca785b89eaa3836156581208bf977ea2e6d7ae84d7efcfc3a40"

/** İndirilecek konuşma modelleri (sürüme sabitlenmiş adres + boyut + sha256). */
data class Model(val ad: String, val etiket: String, val url: String, val boyut: Long, val sha256: String)

object Modeller {
    // Türkçe / İngilizce / Arapça: whisper.cpp çok dilli small (q5_1)
    val GENEL = Model(
        "ggml-small-q5_1.bin", "model.genel",
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-small-q5_1.bin",
        190085487L, "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb",
    )
    // Kurmancî: samil24/whisper-small-kurmanji-v10 → whisper.cpp q5_1
    val KURMANCI = Model(
        "ggml-whisper-small-kurmanji-q5_1.bin", "model.kmr",
        "https://huggingface.co/Seyfipolat/med-live-translate-modeller/resolve/" + KMR_COMMIT + "/ggml/ggml-whisper-small-kurmanji-q5_1.bin",
        190085504L, "d475ea52d2304cad9e614cd14580b287461a2c750f3d91a251707086928ead56",
    )
    val HEPSI = listOf(GENEL, KURMANCI)

    // Çevrimdışı çeviri (isteğe bağlı): masaüstündeki NLLB-200 600M CTranslate2 int8 modeli
    // (aynı HF commit'i) + SentencePiece belirteçleyici (facebook/nllb-200-distilled-600M).
    private const val NLLB_COMMIT = "3bd25f74a7974fc57d3fa2c4a99634f6d15307fa"
    private const val NLLB_ADRES = "https://huggingface.co/Seyfipolat/med-live-translate-modeller/resolve/$NLLB_COMMIT/nllb-600M-ct2/"
    val NLLB_MODEL = Model("nllb/model.bin", "model.nllb", NLLB_ADRES + "model.bin",
        619704329L, "831d36c506c59ff7d312ba0a08c4d199eadbc4de68a99d7c4f61736d8e2f13cc")
    val NLLB_SOZLUK = Model("nllb/shared_vocabulary.json", "model.nllb", NLLB_ADRES + "shared_vocabulary.json",
        6177383L, "768aa4170693765cb4c62fc485ea4fd954cd3e5a44bc645d11d83f41f2393776")
    val NLLB_AYAR = Model("nllb/config.json", "model.nllb", NLLB_ADRES + "config.json",
        233L, "72901fbd8abd89fb5cf4a388f26fc681f5c4c58a1e1a88b30b879f107270e7ee")
    val NLLB_SPM = Model("nllb/sentencepiece.bpe.model", "model.nllb",
        "https://huggingface.co/facebook/nllb-200-distilled-600M/resolve/f8d333a098d19b4fd9a8b18f94170487ad3f821d/sentencepiece.bpe.model",
        4852054L, "14bb8dfb35c0ffdea7bc01e56cea38b9e3d5efcdcb9c251d6b40538e1aab555a")
    val CEVIRI = listOf(NLLB_AYAR, NLLB_SOZLUK, NLLB_SPM, NLLB_MODEL)
    val CEVIRI_BOYUT = CEVIRI.sumOf { it.boyut }
    fun nllbKlasoru(c: Context) = File(klasor(c), "nllb")
    /** Çevrimdışı çeviri modelini siler (yer açmak için). */
    fun ceviriSil(c: Context) { nllbKlasoru(c).deleteRecursively() }

    // Kürtçe Orta (isteğe bağlı): Meta Omnilingual ASR CTC v2 int8 (Apache 2.0), sherpa-onnx sürüm sayfasından tar.bz2;
    // indirildikten sonra omni/ klasörüne açılır, arşiv silinir.
    val OMNI_ARSIV = Model("omni_arsiv.tar.bz2", "model.omni", OMNI_URL, OMNI_BOYUT, OMNI_SHA256)
    fun omniKlasoru(c: Context) = File(klasor(c), "omni")
    fun omniHazir(c: Context) = File(omniKlasoru(c), "tamam").isFile
    fun omniSil(c: Context) { omniKlasoru(c).deleteRecursively(); dosya(c, OMNI_ARSIV).delete(); isaret(c, OMNI_ARSIV).delete() }

    /** İnen arşivden yalnızca model.int8.onnx ve tokens.txt açılır (önce geçici klasöre). */
    fun omniAc(c: Context, ilerleme: (Long) -> Unit) {
        val arsiv = dosya(c, OMNI_ARSIV)
        val gecici = File(klasor(c), "omni.acilis").apply { deleteRecursively(); mkdirs() }
        var acilan = 0L
        org.apache.commons.compress.archivers.tar.TarArchiveInputStream(
            org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(arsiv.inputStream().buffered(1 shl 20))
        ).use { tar ->
            while (true) {
                val g = tar.nextEntry ?: break
                val ad = g.name.substringAfterLast('/')
                if (g.isDirectory || ad !in setOf("model.int8.onnx", "tokens.txt", "LICENSE")) continue
                FileOutputStream(File(gecici, ad)).use { cikti ->
                    val tampon = ByteArray(1 shl 20)
                    while (true) {
                        val n = tar.read(tampon); if (n < 0) break
                        cikti.write(tampon, 0, n); acilan += n; ilerleme(acilan)
                    }
                }
            }
        }
        check(File(gecici, "model.int8.onnx").isFile && File(gecici, "tokens.txt").isFile) { "arşivde model yok" }
        File(gecici, "tamam").writeText(OMNI_SHA256)
        omniKlasoru(c).deleteRecursively()
        check(gecici.renameTo(omniKlasoru(c))) { "model klasörü taşınamadı" }
        arsiv.delete(); isaret(c, OMNI_ARSIV).delete()
    }

    fun klasor(c: Context) = File(c.filesDir, "modeller").apply { mkdirs() }
    fun dosya(c: Context, m: Model) = File(klasor(c), m.ad)
    /** Doğrulanmış dosyanın sha256'sı burada tutulur: boyut aynı olsa bile model değiştiyse yeniden indirilir. */
    private fun isaret(c: Context, m: Model) = File(klasor(c), m.ad + ".ok")
    fun hazir(c: Context, m: Model) = dosya(c, m).length() == m.boyut &&
        runCatching { isaret(c, m).readText().trim() }.getOrNull() == m.sha256
    fun hepsiHazir(c: Context) = HEPSI.all { hazir(c, it) }
    fun dilIcin(dil: String) = if (dil == "kmr") KURMANCI else GENEL

    /** İlerleme: (model, inen bayt [bu model dahil toplam], toplam bayt, hız bayt/sn) */
    fun interface Ilerleme { fun bildir(m: Model, inen: Long, toplam: Long, hiz: Double, dogrulaniyor: Boolean, koptu: Boolean) }

    /** Eksik modelleri indirir; kopan bağlantıda kaldığı yerden sürer. Hata olursa istisna atar. */
    fun indir(c: Context, ilerleme: Ilerleme, iptal: () -> Boolean, liste: List<Model> = HEPSI) {
        val eksik = liste.filter { !hazir(c, it) }
        val toplam = eksik.sumOf { it.boyut }
        var oncekiler = 0L
        for (m in eksik) {
            indirTek(c, m, iptal) { inen, hiz, dog, koptu -> ilerleme.bildir(m, oncekiler + inen, toplam, hiz, dog, koptu) }
            oncekiler += m.boyut
        }
    }

    private fun indirTek(c: Context, m: Model, iptal: () -> Boolean, bildir: (Long, Double, Boolean, Boolean) -> Unit) {
        val hedef = dosya(c, m)
        hedef.parentFile?.mkdirs()
        val parca = File(hedef.path + ".part")
        // tam boyutta ama işaretsiz (eski sürüm) dosya: baştan indirmek yerine önce doğrulanır
        if (hedef.length() == m.boyut && !parca.exists()) hedef.renameTo(parca)
        var deneme = 0
        while (true) {
            if (iptal()) throw InterruptedException()
            var mevcut = parca.length()
            if (mevcut > m.boyut) { parca.delete(); mevcut = 0 }
            if (mevcut == m.boyut) {
                bildir(mevcut, 0.0, true, false)
                if (sha256(parca) != m.sha256) {
                    parca.delete()
                    if (++deneme > 3) throw IllegalStateException("${m.ad}: doğrulama başarısız")
                    continue
                }
                hedef.delete()
                if (!parca.renameTo(hedef)) throw IllegalStateException("${m.ad}: kaydedilemedi")
                isaret(c, m).writeText(m.sha256)
                return
            }
            try {
                val bag = (URL(m.url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000
                    readTimeout = 60000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "MED-LIVE-TRANSLATE-android")
                    if (mevcut > 0) setRequestProperty("Range", "bytes=$mevcut-")
                }
                val ekle = mevcut > 0 && bag.responseCode == 206
                if (bag.responseCode !in listOf(200, 206)) throw IllegalStateException("HTTP ${bag.responseCode}")
                if (!ekle) mevcut = 0
                RandomAccessFile(parca, "rw").use { f ->
                    if (!ekle) f.setLength(0)
                    f.seek(mevcut)
                    val tampon = ByteArray(256 * 1024)
                    var son = System.nanoTime(); var sonBayt = mevcut; var hiz = 0.0
                    bag.inputStream.use { g ->
                        while (true) {
                            if (iptal()) throw InterruptedException()
                            val n = g.read(tampon)
                            if (n < 0) break
                            f.write(tampon, 0, n)
                            mevcut += n
                            val simdi = System.nanoTime()
                            if (simdi - son > 300_000_000) {
                                hiz = (mevcut - sonBayt) / ((simdi - son) / 1e9)
                                son = simdi; sonBayt = mevcut
                                bildir(mevcut, hiz, false, false)
                            }
                        }
                    }
                }
                bag.disconnect()
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                if (++deneme >= 30) throw e
                bildir(parca.length(), 0.0, false, true)
                Thread.sleep(minOf(15000L, deneme * 2000L))
            }
        }
    }

    private fun sha256(f: File): String {
        val h = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { g ->
            val t = ByteArray(1 shl 20)
            while (true) { val n = g.read(t); if (n < 0) break; h.update(t, 0, n) }
        }
        return h.digest().joinToString("") { "%02x".format(it) }
    }

    @Suppress("unused") private fun kopyala(k: File, h: File) = k.inputStream().use { g -> FileOutputStream(h).use { g.copyTo(it) } }
}
