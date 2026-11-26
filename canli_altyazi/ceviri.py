"""Çeviri: Google Çeviri (çevrimiçi, model indirmez).

Anahtarsız ücretsiz uç nokta kullanılır. Resmi Google Cloud Translation API anahtarı varsa
(ayda 500.000 karakter ücretsiz) program klasöründeki `google_api_anahtari.txt` dosyasına ya da
GOOGLE_CEVIRI_ANAHTARI ortam değişkenine yazılır; o zaman resmi API kullanılır.
Google'a ulaşılamazsa (internet yok, geçici kısıtlama) çeviri bilgisayardaki çevrimdışı
NLLB-200 600M modeliyle yapılır (modeller/nllb-600M-ct2; Google'dan biraz daha zayıf ama altyazı
çevrilmeden kalmaz). O da yoksa None döner: altyazıda orijinal metin gösterilir, program durmaz.
"""
from __future__ import annotations

import collections
import os
import re
import sys
import threading
import time

from .diller import KOD_ILE, tekrar_temizle

KOK = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def _google_anahtari() -> str | None:
    anahtar = os.environ.get("GOOGLE_CEVIRI_ANAHTARI", "").strip()
    if anahtar:
        return anahtar
    try:
        with open(os.path.join(KOK, "google_api_anahtari.txt"), encoding="utf-8") as f:
            return f.read().strip() or None
    except OSError:
        return None


NLLB = os.path.join(KOK, "modeller", "nllb-600M-ct2")
# cümle sonu: . ! ? … ؟ ve ardından boşluk (kısaltmalarda yanlış bölmek zararsız: parçalar yine çevrilir)
_CUMLE_SONU = re.compile(r"(?<=[.!?…؟])\s+")


def yerel_var() -> bool:
    return all(os.path.isfile(os.path.join(NLLB, f)) for f in ("model.bin", "tokenizer.json"))


class YerelCevirmen:
    """Çevrimdışı yedek: NLLB-200 (CTranslate2 int8, işlemcide). İlk gerektiğinde yüklenir."""

    def __init__(self):
        self._model = None
        self._tok = None
        self._kilit = threading.Lock()

    def _yukle(self) -> None:
        import ctranslate2
        from tokenizers import Tokenizer

        self._tok = Tokenizer.from_file(os.path.join(NLLB, "tokenizer.json"))
        # konuşma tanımaya işlemci bırakılsın: çekirdeklerin dörtte biri
        self._model = ctranslate2.Translator(NLLB, device="cpu", compute_type="int8", inter_threads=1,
                                             intra_threads=max(2, (os.cpu_count() or 4) // 4))

    # Konuşma metni sık tekrar içerir ("bir şekilde, bir şekilde..."); NLLB bunu görünce döngüye girip
    # aynı öbeği onlarca kez basabiliyordu. Önlem: cümle cümle çeviri, aynı 4'lü belirteç dizisinin
    # tekrarı yasak ve çıktı uzunluğu girdiyle orantılı (en fazla ~2 katı).
    TEKRAR_YASAK = 4
    UZUNLUK_ORANI = 2.0

    def cevir(self, metin: str, kaynak: str, hedef: str) -> str | None:
        if not yerel_var():
            return None
        with self._kilit:
            if self._model is None:
                self._yukle()
            cumleler = [c for c in _CUMLE_SONU.split(metin.strip()) if c.strip()]
            girdiler, uzunluklar = [], []
            for c in cumleler:
                belirtecler = self._tok.encode(c, add_special_tokens=False).tokens
                girdiler.append([kaynak] + belirtecler + ["</s>"])
                uzunluklar.append(len(belirtecler))
            if not girdiler:
                return None
            sonuc = self._model.translate_batch(
                girdiler, target_prefix=[[hedef]] * len(girdiler), beam_size=2,
                no_repeat_ngram_size=self.TEKRAR_YASAK,
                max_decoding_length=min(256, int(max(uzunluklar) * self.UZUNLUK_ORANI) + 10))
            parcalar = []
            for s in sonuc:
                belirtecler = s.hypotheses[0][1:]  # baştaki hedef dil etiketi atlanır
                parca = self._tok.decode([self._tok.token_to_id(t) for t in belirtecler]).strip()
                if parca:
                    parcalar.append(parca)
            return " ".join(parcalar) or None


class Cevirmen:
    ARALIK = 0.3          # iki istek arası en az süre (sn): servisi zorlamamak için
    ZAMAN_ASIMI = (3, 5)  # bağlanma, okuma (sn)
    EN_UZUN = 1500        # karakter

    def __init__(self):
        import requests

        self.oturum = requests.Session()
        self.oturum.headers["User-Agent"] = "Mozilla/5.0"
        self.anahtar = _google_anahtari()
        self._kilit = threading.Lock()
        self._son_istek = 0.0
        self._kapali_kadar = 0.0   # art arda hatada kısa süre denenmez
        self._bekleme = 0.0
        self._onbellek: collections.OrderedDict[tuple, str] = collections.OrderedDict()
        self.son_hata: str | None = None
        self.yerel = YerelCevirmen()
        self.son_kaynak = "google"   # son çeviriyi yapan: "google" ya da "yerel"

    def cevir(self, metin: str, kaynak: str, hedef: str, yalniz_yerel: bool = False) -> str | None:
        """yalniz_yerel: çevrimdışı mod — hiçbir metin internete gönderilmez, NLLB kullanılır."""
        if not metin or kaynak == hedef:
            return metin
        anahtar = (metin, kaynak, hedef)
        if anahtar in self._onbellek:
            self._onbellek.move_to_end(anahtar)
            return self._onbellek[anahtar]
        sonuc = None if yalniz_yerel else self._google(metin, kaynak, hedef)
        if sonuc is None:
            try:
                sonuc = self.yerel.cevir(metin[: self.EN_UZUN], KOD_ILE[kaynak].nllb, KOD_ILE[hedef].nllb)
            except Exception as e:
                print(f"[çeviri] çevrimdışı çeviri başarısız: {type(e).__name__}: {e}", file=sys.stderr, flush=True)
                sonuc = None
            if sonuc is None:
                return None
            self.son_kaynak = "yerel"
            sonuc = tekrar_temizle(sonuc)
            return sonuc  # önbelleğe alınmaz: Google dönünce aynı cümle onunla çevrilsin
        self.son_kaynak = "google"
        sonuc = tekrar_temizle(sonuc)
        self._onbellek[anahtar] = sonuc
        if len(self._onbellek) > 500:
            self._onbellek.popitem(last=False)
        return sonuc

    def _google(self, metin: str, kaynak: str, hedef: str) -> str | None:
        with self._kilit:
            simdi = time.monotonic()
            if simdi < self._kapali_kadar:
                return None
            bekle = self.ARALIK - (simdi - self._son_istek)
            if bekle > 0:
                time.sleep(bekle)
            self._son_istek = time.monotonic()
            try:
                sonuc = self._iste(metin[: self.EN_UZUN], KOD_ILE[kaynak].google, KOD_ILE[hedef].google)
            except Exception as e:
                # 10 sn'den başlayıp 5 dk'ya kadar artan ara: geçici kopmada altyazı hemen geri gelir
                self._bekleme = min(300.0, max(10.0, self._bekleme * 2))
                self._kapali_kadar = time.monotonic() + self._bekleme
                # gizlilik: hata metni istek adresini (içinde çevrilen cümle var) içerebilir; günlüğe yazılmaz
                kod = getattr(getattr(e, "response", None), "status_code", None)
                self.son_hata = f"{type(e).__name__}" + (f" (HTTP {kod})" if kod else "")
                print(f"[çeviri] Google kullanılamadı ({self.son_hata}); "
                      f"{int(self._bekleme)} sn sonra yeniden denenecek", file=sys.stderr, flush=True)
                return None
            self._bekleme = 0.0
            self.son_hata = None
        return sonuc

    def _iste(self, metin: str, kaynak: str, hedef: str) -> str:
        if self.anahtar:  # resmi Cloud Translation API (v2)
            y = self.oturum.post("https://translation.googleapis.com/language/translate/v2",
                                 data={"q": metin, "source": kaynak, "target": hedef,
                                       "format": "text", "key": self.anahtar},
                                 timeout=self.ZAMAN_ASIMI)
            y.raise_for_status()
            sonuc = y.json()["data"]["translations"][0]["translatedText"]
        else:  # anahtarsız ücretsiz uç nokta
            y = self.oturum.get("https://translate.googleapis.com/translate_a/single",
                                params={"client": "gtx", "sl": kaynak, "tl": hedef, "dt": "t",
                                        "q": metin},
                                timeout=self.ZAMAN_ASIMI)
            y.raise_for_status()
            sonuc = "".join(p[0] for p in y.json()[0] if p and p[0])
        sonuc = sonuc.strip()
        if not sonuc:
            raise ValueError("boş yanıt")
        return sonuc
