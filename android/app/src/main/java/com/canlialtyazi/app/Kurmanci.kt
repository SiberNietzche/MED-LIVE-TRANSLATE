package com.canlialtyazi.app

import android.content.Context
import android.util.Log

/**
 * Kurmancî yazım düzeltme (masaüstündeki canli_altyazi/kurmanci.py ile aynı kurallar): konuşma modelinin
 * ürettiği, sözlükte olmayan kelimeleri düzeltir.
 *
 * Kelime listesi (assets/kmr_sozluk.txt): Kanî Labs Kurdish Lexicon (github.com/kanilabs/zimankit),
 * CC BY-SA 4.0; yazı derleminde en az 2 kez geçen ~86 bin kelime biçimi ve sıklıkları.
 *  1. Ayrık yazılmış kelimeyi birleştir ("Dana zînên" -> "Danazînên"): birleşik hâli sözlükte, parçalardan biri yoksa.
 *  2. Sözlükte olmayan kelimeyi (en az 3 harf) tek harf farkla ulaşılan en sık kelimeyle değiştir.
 */
object Kurmanci {
    private const val HARFLER = "abcçdeêfghiîjklmnopqrsştuûvwxyz"
    private val PARCA = Regex("""[\p{L}\p{N}_]+|[^\p{L}\p{N}_\s]+|\s+""")
    @Volatile private var sozluk: HashMap<String, Int>? = null

    /** Listeyi önceden yükler (~0,2 sn; dinleme başlarken çağrılır). */
    fun hazirla(c: Context): HashMap<String, Int> {
        sozluk?.let { return it }
        synchronized(this) {
            sozluk?.let { return it }
            val s = HashMap<String, Int>(120_000)
            runCatching {
                c.assets.open("kmr_sozluk.txt").bufferedReader(Charsets.UTF_8).useLines { satirlar ->
                    for (satir in satirlar) {
                        if (satir.startsWith("#")) continue
                        val t = satir.indexOf('\t')
                        if (t > 0) satir.substring(t + 1).toIntOrNull()?.let { s[satir.substring(0, t)] = it }
                    }
                }
            }.onFailure { Log.e("Kurmanci", "kelime listesi okunamadı", it) }
            sozluk = s
            return s
        }
    }

    /** Kurmancî küçük harf (Türkçedeki İ/ı dönüşümü olmadan). */
    fun kucuk(s: String): String {
        val b = StringBuilder(s.length)
        for (ch in s) b.append(
            when (ch) {
                'I' -> 'i'; 'Î' -> 'î'; 'Ê' -> 'ê'; 'Û' -> 'û'; 'Ş' -> 'ş'; 'Ç' -> 'ç'
                else -> ch.lowercaseChar()
            }
        )
        return b.toString()
    }

    private fun harfMi(w: String) = w.isNotEmpty() && w.all { it.isLetter() }

    private fun enYakin(w: String, s: Map<String, Int>): String? {
        var en: String? = null
        var enF = 0
        fun bak(a: String) { val f = s[a] ?: return; if (f > enF) { en = a; enF = f } }
        for (i in 0..w.length) {
            val sol = w.substring(0, i); val sag = w.substring(i)
            if (sag.isNotEmpty()) {
                bak(sol + sag.substring(1))
                for (c in HARFLER) if (c != sag[0]) bak(sol + c + sag.substring(1))
                if (sag.length > 1) bak(sol + sag[1] + sag[0] + sag.substring(2))
            }
            for (c in HARFLER) bak(sol + c + sag)
        }
        return en
    }

    fun duzelt(c: Context, metin: String): String = duzelt(hazirla(c), metin)

    fun duzelt(s: Map<String, Int>, metin: String): String {
        if (s.isEmpty() || metin.isEmpty()) return metin
        val t = PARCA.findAll(metin).map { it.value }.toMutableList()
        // 1) birleştirme
        var i = 0
        while (i + 2 < t.size) {
            val a = t[i]; val b = t[i + 2]
            if (t[i + 1] == " " && harfMi(a) && harfMi(b)) {
                val ka = kucuk(a); val kb = kucuk(b)
                if ((ka !in s || kb !in s) && (ka + kb) in s) {
                    t[i] = a + kb; t.removeAt(i + 1); t.removeAt(i + 1)
                    continue
                }
            }
            i++
        }
        // 2) tek harf düzeltme
        for (j in t.indices) {
            val w = t[j]
            if (w.length < 3 || !harfMi(w)) continue
            val k = kucuk(w)
            if (k in s) continue
            val y = enYakin(k, s) ?: continue
            t[j] = if (w[0].isUpperCase()) y.substring(0, 1).uppercase(java.util.Locale.ROOT) + y.substring(1) else y
        }
        return t.joinToString("")
    }
}
