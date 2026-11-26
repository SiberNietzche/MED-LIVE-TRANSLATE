"""Bilgisayar sesini (hoparlöre/kulaklığa giden ses) 16 kHz mono yakalar.

Windows: WASAPI loopback · Linux: PulseAudio/PipeWire monitor (ikisi de soundcard ile)
macOS: işletim sistemi bu sesi doğrudan vermez; ScreenCaptureKit kullanan yardımcı program
(macos/ses_yakala) sesi stdout'a yazar, burada okunur. "Ekran ve Sistem Sesi Kaydı" izni gerekir.
"""
from __future__ import annotations

import os
import subprocess
import sys
import threading
import time
import warnings
from typing import Callable

import numpy as np

MACOS = sys.platform == "darwin"
if not MACOS:
    # soundcard COM'u içe aktarıldığı iş parçacığında (MTA) başlatır. Bu modül ana
    # iş parçacığında içe aktarıldığı için diğer iş parçacıkları aynı MTA'yı kullanır.
    import soundcard as sc

ORNEKLEME = 16000
PARCA = 1600  # 100 ms
KOK = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
YAKALAYICI = os.path.join(KOK, "macos", "ses_yakala")
IZIN_MESAJI = ("Bilgisayar sesi için izin gerekli: Sistem Ayarları → Gizlilik ve Güvenlik → "
               "Ekran ve Sistem Sesi Kaydı → MED LIVE TRANSLATE'i açın, sonra programı yeniden başlatın.")


def _dinleme_aygiti(hoparlor):
    """Hoparlöre giden sesi veren kaynak. Windows'ta kimlik aynıdır (WASAPI loopback);
    Linux'ta (PulseAudio / PipeWire) kaynağın kimliği hoparlörünkünün sonuna ".monitor" eklenmiş halidir."""
    try:
        return sc.get_microphone(id=hoparlor.id, include_loopback=True)
    except Exception:
        hedef = f"{hoparlor.id}.monitor"
        for m in sc.all_microphones(include_loopback=True):
            if m.id == hedef:
                return m
        raise


class BilgisayarSesi(threading.Thread):
    """Varsayılan çıkış aygıtını sürekli dinleyip `parca_geldi(ornekler)` çağırır.
    Varsayılan aygıt değişirse (kulaklık takılması vb.) kendini yeniden açar."""

    def __init__(self, parca_geldi: Callable[[np.ndarray], None], hata: Callable[[str], None],
                 dur: threading.Event, geri_geldi: Callable[[], None] | None = None):
        super().__init__(daemon=True, name="bilgisayar-sesi")
        self.parca_geldi = parca_geldi
        self.hata = hata
        self.geri_geldi = geri_geldi
        self.dur = dur
        self._son_kontrol = 0.0

    def run(self) -> None:
        # bu uyarı sınıfı yalnızca Windows'taki soundcard'da var (Linux'ta PulseAudio modülünde yok)
        uyari = getattr(sc, "SoundcardRuntimeWarning", None)
        if uyari is not None:
            warnings.filterwarnings("ignore", category=uyari)
        arizali = False  # hata bir kez bildirilir (her saniye değil); aygıt dönünce "Dinleniyor"
        while not self.dur.is_set():
            try:
                hoparlor = sc.default_speaker()
                # kimlikle eşle: adla eşleme aynı/benzer adlı iki aygıtta yanlışını seçebiliyordu
                aygit = _dinleme_aygiti(hoparlor)
                with aygit.recorder(samplerate=ORNEKLEME, blocksize=PARCA) as kayit:
                    if arizali:
                        arizali = False
                        if self.geri_geldi:
                            self.geri_geldi()
                    while not self.dur.is_set():
                        veri = kayit.record(numframes=PARCA)
                        if veri.ndim == 2:
                            veri = veri.mean(axis=1)
                        try:
                            self.parca_geldi(veri.astype(np.float32, copy=False))
                        except Exception as e:  # işleme hatası: aygıtı yeniden açmaya gerek yok
                            self.hata(f"Ses işlenemedi: {e}")
                            time.sleep(1.0)
                        if self._aygit_degisti(hoparlor.id):
                            break
            except Exception as e:  # aygıt çıkarıldı, hiç çıkış aygıtı yok vb.
                if not arizali:
                    arizali = True
                    self.hata(f"Bilgisayar sesi alınamadı: {e}")
                time.sleep(1.0)

    def _aygit_degisti(self, ad: str) -> bool:
        simdi = time.monotonic()
        if simdi - self._son_kontrol < 2.0:
            return False
        self._son_kontrol = simdi
        try:
            return sc.default_speaker().id != ad
        except Exception:
            return False


class MacBilgisayarSesi(threading.Thread):
    """macOS: yardımcı programın stdout'undan 16 kHz mono float32 akışını okur.
    Yardımcı kapanırsa (ses aygıtı değişti vb.) kısa bir aradan sonra yeniden başlatılır."""

    def __init__(self, parca_geldi: Callable[[np.ndarray], None], hata: Callable[[str], None],
                 dur: threading.Event, geri_geldi: Callable[[], None] | None = None):
        super().__init__(daemon=True, name="bilgisayar-sesi")
        self.parca_geldi = parca_geldi
        self.hata = hata
        self.geri_geldi = geri_geldi
        self.dur = dur

    def run(self) -> None:
        arizali = False
        bayt = PARCA * 4
        while not self.dur.is_set():
            if not os.path.isfile(YAKALAYICI):
                self.hata("Ses yakalayıcı bulunamadı (macos/ses_yakala). Programı yeniden kurun.")
                return
            surec = subprocess.Popen([YAKALAYICI], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=subprocess.PIPE)
            hata_satirlari: list[str] = []
            threading.Thread(target=lambda: hata_satirlari.extend(
                s.decode("utf-8", "replace").strip() for s in surec.stderr), daemon=True).start()
            try:
                tampon = b""
                while not self.dur.is_set():
                    parca = surec.stdout.read(bayt - len(tampon))
                    if not parca:
                        break  # yardımcı kapandı
                    tampon += parca
                    if len(tampon) < bayt:
                        continue
                    if arizali:
                        arizali = False
                        if self.geri_geldi:
                            self.geri_geldi()
                    veri = np.frombuffer(tampon, dtype="<f4").copy()
                    tampon = b""
                    try:
                        self.parca_geldi(veri)
                    except Exception as e:
                        self.hata(f"Ses işlenemedi: {e}")
                        time.sleep(1.0)
            finally:
                if surec.poll() is None:
                    surec.kill()
                surec.wait(5)
            if self.dur.is_set():
                return
            izin_yok = surec.returncode == 3 or any("IZIN_YOK" in s for s in hata_satirlari)
            if not arizali:
                arizali = True
                neden = next((s for s in hata_satirlari if s.startswith("HATA")), f"kod {surec.returncode}")
                self.hata(IZIN_MESAJI if izin_yok else f"Bilgisayar sesi alınamadı: {neden}")
            # izin yoksa her saniye yeni izin penceresi açılmasın
            self.dur.wait(15.0 if izin_yok else 2.0)


if MACOS:
    BilgisayarSesi = MacBilgisayarSesi  # noqa: F811
