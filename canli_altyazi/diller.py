"""Desteklenen diller: hepsi birbirine çevrilebilir.

whisper: Whisper'a verilen dil kodu
google:  Google Çeviri dil kodu
nllb:    çevrimdışı yedek çeviri modelinin (NLLB-200) dil kodu
"""
from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class Dil:
    kod: str
    ad: str
    whisper: str
    google: str
    nllb: str
    rtl: bool = False


DILLER: list[Dil] = [
    Dil("kmr", "Kurmancî (Kürtçe)", "tr", "ku", "kmr_Latn"),  # Kurmancî modeli Whisper'ın "tr" etiketiyle eğitilmiş
    Dil("tr", "Türkçe", "tr", "tr", "tur_Latn"),
    Dil("en", "English (İngilizce)", "en", "en", "eng_Latn"),
    Dil("ar", "العربية (Arapça)", "ar", "ar", "arb_Arab", rtl=True),
]

KOD_ILE = {d.kod: d for d in DILLER}


_NOKTALAMA = ".,;:!?…\"'“”‘’«»()[]-–—،؛؟"


def tekrar_temizle(metin: str, en_uzun_birim: int = 8) -> str:
    """Modellerin döngüye girip aynı kelime/öbeği art arda basmasını temizler.

    Örn. "bi awayekî, bi awayekî, bi awayekî, ..." (×37) → "bi awayekî,".
    Doğal tekrarlar korunur: tek kelime 3 kez ("çok çok çok"), öbek 2 kez ("sağ olun, sağ olun").
    Daha fazlası döngü sayılır ve tek kopyaya indirilir. Karşılaştırma büyük/küçük harf ve
    noktalama farkını yok sayar.
    """
    kelimeler = metin.split()
    if len(kelimeler) < 4:
        return metin
    for n in range(1, en_uzun_birim + 1):
        sinir = 4 if n == 1 else 3  # bu kadar ve fazlası art arda gelirse döngü
        if len(kelimeler) < n * sinir:
            continue
        sade = [k.strip(_NOKTALAMA).casefold() for k in kelimeler]
        cikti: list[str] = []
        i = 0
        while i < len(kelimeler):
            birim = sade[i:i + n]
            tekrar = 1
            if len(birim) == n and any(birim):
                while sade[i + tekrar * n:i + (tekrar + 1) * n] == birim:
                    tekrar += 1
            if tekrar >= sinir:
                cikti.extend(kelimeler[i:i + n])
                i += tekrar * n
            else:
                cikti.append(kelimeler[i])
                i += 1
        kelimeler = cikti
    return " ".join(kelimeler)
