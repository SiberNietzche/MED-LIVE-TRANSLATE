"""Bilgisayar sesi → cümlelere bölme → yazıya dökme → Google çeviri hattı.

Arayüze olayları `olay(dict)` geri çağrısıyla gönderir:
  {"tur": "durum", "metin": ..., "hazir": bool}
  {"tur": "hata", "metin": ...}
  {"tur": "son", "id", "kaynak", "orijinal", "ceviri", "dil", "rtl", "hedef_rtl"}
"""
from __future__ import annotations

import collections
import threading
import time
import traceback
from dataclasses import dataclass
from typing import Callable

import numpy as np

from . import kurmanci
from .diller import KOD_ILE
from .ses import BilgisayarSesi

GECIKME_UYARISI = 4  # birleştirmeden sonra hâlâ bu kadar cümle bekliyorsa işlemci yetişemiyor demektir
BIRLESIK_EN_UZUN = 26 * 16000  # birleştirilen sesin en fazla uzunluğu (Whisper penceresi 30 sn)
# Bekleyen ses bundan uzunsa en eskisi atılır: zayıf işlemcide gecikme ve bellek sınırsız büyümesin
KUYRUK_EN_UZUN = 60 * 16000
ARA_SESSIZLIK = int(0.3 * 16000)


@dataclass
class Ayarlar:
    konusma_dili: str = "kmr"
    hedef_dil: str = "tr"
    kurmanci_kaliteli: bool = False
    kurmanci_omni: bool = False  # deneysel Omnilingual modeli (kuruluysa; kaliteliden önce gelir)
    cevrimdisi: bool = False   # çeviri yalnızca bu bilgisayarda (internete metin gitmez)

    def dogrula(self) -> "Ayarlar":
        if self.konusma_dili not in KOD_ILE:
            self.konusma_dili = "kmr"
        if self.hedef_dil not in KOD_ILE:
            self.hedef_dil = "tr"
        self.kurmanci_kaliteli = bool(self.kurmanci_kaliteli)
        self.kurmanci_omni = bool(self.kurmanci_omni)
        self.cevrimdisi = bool(self.cevrimdisi)
        return self


class Motor:
    def __init__(self, olay: Callable[[dict], None]):
        from .ceviri import Cevirmen
        from .tanima import Tanima

        self.olay = olay
        self.ayarlar = Ayarlar()
        self.tanima = Tanima()
        self.cevirmen = Cevirmen()
        self._dur = threading.Event()
        self._duraklat = threading.Event()
        self._kosul = threading.Condition()
        self._kuyruk: collections.deque = collections.deque()
        self._kuyruk_ornek = 0  # kuyruktaki toplam ses (örnek)
        self._atilan = 0  # sınır aşıldığı için atlanan, henüz bildirilmemiş bölüm sayısı
        self._bolumleyici = None
        self._yukleme_kilidi = threading.Lock()
        self.seviye = 0.0
        self.calisiyor = False
        self.hazir = False
        self._ceviri_uyarildi = False
        from concurrent.futures import ThreadPoolExecutor
        self._cevirici = ThreadPoolExecutor(max_workers=1, thread_name_prefix="ceviri")

    def _durum(self, metin: str) -> None:
        self.olay({"tur": "durum", "metin": metin, "hazir": self.hazir})

    # ---------- model ----------
    def onceden_yukle(self, ayarlar: Ayarlar) -> None:
        """Seçili dilin modelini arka planda hazırlar (Başlat beklemesin)."""
        self.ayarlar = ayarlar.dogrula()
        threading.Thread(target=self._yukle_guvenli, daemon=True, name="yukleme").start()

    def _yukle_guvenli(self) -> None:
        try:
            self._yukle()
        except Exception as e:
            traceback.print_exc()
            self.olay({"tur": "hata", "metin": f"Model yüklenemedi: {e}"})

    def _yukle(self) -> None:
        with self._yukleme_kilidi:
            dil, kaliteli = self.ayarlar.konusma_dili, self.ayarlar.kurmanci_kaliteli
            omni = self.ayarlar.kurmanci_omni
            from .tanima import omni_var
            self.hazir = False
            ek = (" (deneysel)" if omni and omni_var() else " (kaliteli)" if kaliteli else "") if dil == "kmr" else ""
            self._durum(f"Hazırlanıyor: {KOD_ILE[dil].ad} konuşma modeli{ek}…")
            self.tanima.hazirla(dil, kaliteli, omni)
            self.hazir = True
            self.olay({"tur": "cihaz", "deger": self.tanima.cihaz})
            self._bolum_ayari()
            self._durum("Dinleniyor" if self.calisiyor else "Hazır — Başlat'a basabilirsiniz.")

    # ---------- yaşam döngüsü ----------
    def baslat(self, ayarlar: Ayarlar) -> None:
        if self.calisiyor:
            return
        self.ayarlar = ayarlar.dogrula()
        # Her çalıştırmanın kendi "dur" olayı: Durdur → hemen Başlat yapılınca önceki çalıştırmanın
        # (hâlâ model yükleyen / cümle işleyen) iş parçacıkları yeni oturuma karışmaz, kendi olayıyla biter.
        dur = threading.Event()
        self._dur = dur
        self._duraklat.clear()
        self.calisiyor = True
        threading.Thread(target=self._hazirla_ve_calis, args=(dur,), daemon=True, name="motor").start()

    def durdur(self) -> None:
        self._dur.set()
        with self._kosul:
            self._kuyruk.clear()
            self._kuyruk_ornek = 0
            self._kosul.notify_all()
        self.calisiyor = False
        self.seviye = 0.0
        self._durum("Durduruldu")

    def duraklat_degistir(self) -> bool:
        if self._duraklat.is_set():
            self._duraklat.clear()
        else:
            self._duraklat.set()
        return self._duraklat.is_set()

    def ayarlari_guncelle(self, ayarlar: Ayarlar) -> None:
        """Çalışırken dil değişikliği: gerekirse diğer model yüklenir."""
        eski = (self.ayarlar.konusma_dili, self.ayarlar.kurmanci_kaliteli, self.ayarlar.kurmanci_omni)
        self.ayarlar = ayarlar.dogrula()
        self._bolum_ayari()
        if (ayarlar.konusma_dili, ayarlar.kurmanci_kaliteli, ayarlar.kurmanci_omni) != eski:
            threading.Thread(target=self._yukle_guvenli, daemon=True).start()

    def _bolum_ayari(self) -> None:
        """Kurmancî modelleri kısa parçalarda zayıf: cümle sonu için daha uzun duraklama beklenir.
        Bölüm uzayınca kısa duraklama da yeter ve en uzun sınırda en sessiz andan bölünür
        (sürekli konuşma ölçümü, büyük model: WER 48,6 -> 46,3, çeviri chrF 60,3 -> 62,5).
        Büyük model işlemcide çalışıyorsa bölüm başına ~10 sn sürer: daha az, daha uzun bölüm."""
        b = self._bolumleyici
        if b is None:
            return
        kmr = self.ayarlar.konusma_dili == "kmr"
        from .tanima import buyuk_var
        from .tanima import omni_var
        omni = self.ayarlar.kurmanci_omni and omni_var()
        yavas = (kmr and not omni and self.ayarlar.kurmanci_kaliteli and buyuk_var()
                 and getattr(self.tanima, "cihaz", "cpu") == "cpu")
        b.sessizlik_ornek = int((0.8 if kmr else 0.5) * 16000)
        b.kisa_sessizlik_ornek = int(0.3 * 16000) if kmr else b.sessizlik_ornek
        b.en_uzun_ornek = int((20.0 if yavas else 12.0 if kmr else 8.0) * 16000)

    def _hazirla_ve_calis(self, dur: threading.Event) -> None:
        try:
            self._yukle()  # önceden yüklendiyse anında döner
            if dur.is_set():
                return
            from .bolumleyici import Bolumleyici

            self._bolumleyici = Bolumleyici(self._bolum_geldi)
            self._bolum_ayari()
            BilgisayarSesi(lambda x: self._parca_geldi(x, dur), self._ses_hatasi, dur,
                           geri_geldi=self._ses_geri_geldi).start()
            threading.Thread(target=self._bayat_denetimi, args=(dur,), daemon=True).start()
            self._durum("Dinleniyor")
            self._isle_dongusu(dur)
        except Exception as e:
            traceback.print_exc()
            if dur is self._dur:
                self.calisiyor = False
                # "durdu": arayüz Başlat düğmesini geri alsın ("Durdur"da takılı kalmasın)
                self.olay({"tur": "hata", "metin": f"Başlatılamadı — {type(e).__name__}: {e}", "durdu": True})

    def _ses_hatasi(self, mesaj: str) -> None:
        self.seviye = 0.0  # ses ölçer son değerde donmasın
        self.olay({"tur": "hata", "metin": mesaj})

    def _ses_geri_geldi(self) -> None:
        if self.calisiyor:
            self._durum("Dinleniyor")

    # ---------- ses akışı ----------
    def _parca_geldi(self, ornekler: np.ndarray, dur: threading.Event) -> None:
        if dur.is_set():  # önceki oturumun ses iş parçacığı yeni bölümleyiciyi beslemesin
            return
        self.seviye = float(np.sqrt(np.mean(ornekler ** 2)))
        if self._duraklat.is_set():
            return
        self._bolumleyici.besle(ornekler)

    def _bayat_denetimi(self, dur: threading.Event) -> None:
        while not dur.is_set():
            if self._bolumleyici is not None:
                self._bolumleyici.bayatsa_bitir()
            time.sleep(0.25)

    def _bolum_geldi(self, bid: int, ses: np.ndarray) -> None:
        with self._kosul:
            self._kuyruk.append((bid, ses))
            self._kuyruk_ornek += len(ses)
            while self._kuyruk_ornek > KUYRUK_EN_UZUN and len(self._kuyruk) > 1:
                _, eski = self._kuyruk.popleft()
                self._kuyruk_ornek -= len(eski)
                self._atilan += 1
            self._kosul.notify()

    # ---------- işleme ----------
    def _isle_dongusu(self, dur: threading.Event) -> None:
        uyarildi = False
        while not dur.is_set():
            with self._kosul:
                while not self._kuyruk and not dur.is_set():
                    self._kosul.wait(0.5)
                if dur.is_set():
                    return
                bid, ses = self._kuyruk.popleft()
                self._kuyruk_ornek -= len(ses)
                # Whisper her seferinde 30 sn'lik pencere işler: süre ne olursa olsun maliyet aynı.
                # İşlemci geride kaldıysa bekleyen cümleler tek pencerede birleştirilir; böylece
                # yavaş bilgisayarda altyazı gecikir ama hiç geride kalmaz.
                parcalar = [ses]
                uzunluk = len(ses)
                while self._kuyruk and uzunluk + len(self._kuyruk[0][1]) + ARA_SESSIZLIK <= BIRLESIK_EN_UZUN:
                    _, sonraki = self._kuyruk.popleft()
                    self._kuyruk_ornek -= len(sonraki)
                    parcalar += [np.zeros(ARA_SESSIZLIK, dtype=np.float32), sonraki]
                    uzunluk += ARA_SESSIZLIK + len(sonraki)
                if len(parcalar) > 1:
                    ses = np.concatenate(parcalar)
                birikmis = len(self._kuyruk)
                atilan, self._atilan = self._atilan, 0
            if atilan:
                uyarildi = True
                self._durum(f"Dinleniyor — işlemci yetişemiyor, {atilan} cümle atlandı")
            elif birikmis >= GECIKME_UYARISI:
                uyarildi = True
                self._durum(f"Dinleniyor — işlemci yetişemiyor, {birikmis} cümle bekliyor")
            elif uyarildi and birikmis == 0:
                uyarildi = False
                self._durum("Dinleniyor")  # gecikme uyarısı ekranda takılı kalmasın
            try:
                self._isle(bid, ses, dur)
            except Exception as e:
                traceback.print_exc()
                self.olay({"tur": "hata", "metin": f"İşleme hatası: {e}"})

    def _isle(self, bid: int, ses: np.ndarray, dur: threading.Event) -> None:
        dil, hedef = self.ayarlar.konusma_dili, self.ayarlar.hedef_dil
        # sessiz gelen sesi tanımadan önce normalleştir
        tepe = float(np.abs(ses).max()) if ses.size else 0.0
        if 0.0 < tepe < 0.5:
            ses = ses * min(10.0, 0.7 / tepe)
        orijinal = self.tanima.coz(ses, dil, self.ayarlar.kurmanci_kaliteli, self.ayarlar.kurmanci_omni)
        if orijinal and dil == "kmr":
            orijinal = kurmanci.duzelt(orijinal)  # sözlükte olmayan kelimeleri düzelt
        if not orijinal:
            return
        # çeviri ayrı iş parçacığında (sıra korunur): ağ beklerken sıradaki cümle tanınmaya başlar
        if dur.is_set():
            return
        self._cevirici.submit(self._cevir_ve_gonder, bid, orijinal, dil, hedef, dur)

    def _cevir_ve_gonder(self, bid: int, orijinal: str, dil: str, hedef: str, dur: threading.Event) -> None:
        try:
            self._cevir_ve_gonder_(bid, orijinal, dil, hedef, dur)
        except Exception as e:  # iş parçacığı havuzunda hata sessizce kaybolmasın
            traceback.print_exc()
            self.olay({"tur": "hata", "metin": f"Çeviri hatası: {e}"})

    def _cevir_ve_gonder_(self, bid: int, orijinal: str, dil: str, hedef: str, dur: threading.Event) -> None:
        if dur.is_set():  # durdurulmuş ya da önceki oturumdan kalan cümle yeni oturuma düşmesin
            return
        ceviri = ""
        if dil != hedef:
            cevrimdisi = self.ayarlar.cevrimdisi
            sonuc = self.cevirmen.cevir(orijinal, dil, hedef, yalniz_yerel=cevrimdisi)
            yerel = sonuc is not None and self.cevirmen.son_kaynak == "yerel"
            if cevrimdisi:
                if sonuc is None and not self._ceviri_uyarildi:
                    self._ceviri_uyarildi = True
                    self.olay({"tur": "hata", "metin": "Çevrimdışı çeviri modeli bulunamadı — "
                                                       "orijinal metin gösteriliyor."})
            elif sonuc is None or yerel:
                if not self._ceviri_uyarildi:
                    self._ceviri_uyarildi = True
                    if yerel:
                        self._durum("Dinleniyor · Google'a ulaşılamıyor, çevrimdışı çeviri kullanılıyor")
                    else:
                        self.olay({"tur": "hata", "metin": "Google Çeviri'ye ulaşılamıyor (internet?) — "
                                                           "şimdilik orijinal metin gösteriliyor."})
            elif self._ceviri_uyarildi:
                self._ceviri_uyarildi = False
                self._durum("Dinleniyor")
            if sonuc is not None:
                ceviri = sonuc

        self.olay({
            "tur": "son", "id": bid, "kaynak": "bilgisayar", "orijinal": orijinal, "ceviri": ceviri,
            "dil": dil, "rtl": KOD_ILE[dil].rtl, "hedef_rtl": KOD_ILE[hedef].rtl,
        })
