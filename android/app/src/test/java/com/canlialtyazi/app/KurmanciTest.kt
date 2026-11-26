package com.canlialtyazi.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Kurmanci.kt, masaüstündeki canli_altyazi/kurmanci.py ile aynı sonucu vermeli. */
class KurmanciTest {
    private val sozluk: Map<String, Int> by lazy {
        File("src/main/assets/kmr_sozluk.txt").readLines(Charsets.UTF_8)
            .filter { !it.startsWith("#") && '\t' in it }
            .associate { it.substringBefore('\t') to it.substringAfter('\t').toInt() }
    }

    @Test
    fun ornekler() {
        // Python: canli_altyazi.kurmanci.duzelt çıktıları
        assertEquals(
            "U.S.A. Cê mênisîstî nameya holmîteye olîmpiyadê ya dewletê yekbûyî destekirike û veke pêdivêke bi vê nevê qabûlîke ku malbata olîmpîk ji bo hemû aqiletê merivekî ewle bîn bîne.",
            Kurmanci.duzelt(sozluk, "U.S.A. Cê mênisîstî nameya holmîteye olîmpiyayê ya dewletê yekûyî destekirike û veke pêdivêke bi vê nevê qabûlîke ku malbota olîmpî ji bo hemû aqiletê merivşekî ewle bîn bîne."),
        )
        assertEquals("Ez û tu", Kurmanci.duzelt(sozluk, "Ez û tu"))
        assertEquals("", Kurmanci.duzelt(sozluk, ""))
    }

    /** İsteğe bağlı geniş karşılaştırma: KMR_KARSILASTIR = "girdi<TAB>python çıktısı" satırlı dosya. */
    @Test
    fun pythonIleAyni() {
        val yol = System.getenv("KMR_KARSILASTIR") ?: return
        var farkli = 0
        val satirlar = File(yol).readLines(Charsets.UTF_8).filter { '\t' in it }
        for (s in satirlar) {
            val (girdi, beklenen) = s.split('\t', limit = 2)
            val cikti = Kurmanci.duzelt(sozluk, girdi)
            if (cikti != beklenen) { farkli++; println("FARK:\n  py: $beklenen\n  kt: $cikti") }
        }
        println("karşılaştırılan: ${satirlar.size}, farklı: $farkli")
        assertEquals(0, farkli)
    }
}
