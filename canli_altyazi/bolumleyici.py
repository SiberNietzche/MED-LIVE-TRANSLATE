"""Silero VAD (ONNX, faster-whisper ile gelir; PyTorch gerekmez) ile sürekli sesi cümlelere böler.

Konuşma bitince (ya da `en_uzun` saniyeye ulaşınca) bölümü `yayinla(id, ses)` ile verir.
"""
from __future__ import annotations

import collections
import itertools
import os
import threading
import time
from typing import Callable

import numpy as np

from .ses import ORNEKLEME

CERCEVE = 512  # Silero VAD 16 kHz için 512 örnek (32 ms) ister
BAGLAM = 64
_sayac = itertools.count(1)


class SileroVAD:
    """faster-whisper'ın silero_vad_v6.onnx modelini çerçeve çerçeve, durum koruyarak çalıştırır."""

    def __init__(self):
        import onnxruntime
        from faster_whisper.utils import get_assets_path

        ayar = onnxruntime.SessionOptions()
        ayar.inter_op_num_threads = 1
        ayar.intra_op_num_threads = 1
        ayar.log_severity_level = 4
        self.oturum = onnxruntime.InferenceSession(
            os.path.join(get_assets_path(), "silero_vad_v6.onnx"),
            providers=["CPUExecutionProvider"], sess_options=ayar)
        self.sifirla()

    def sifirla(self) -> None:
        self.h = np.zeros((1, 1, 128), dtype=np.float32)
        self.c = np.zeros((1, 1, 128), dtype=np.float32)
        self.baglam = np.zeros(BAGLAM, dtype=np.float32)

    def __call__(self, cerceve: np.ndarray) -> float:
        girdi = np.concatenate([self.baglam, cerceve])[None, :].astype(np.float32)
        cikti, self.h, self.c = self.oturum.run(None, {"input": girdi, "h": self.h, "c": self.c})
        self.baglam = cerceve[-BAGLAM:]
        return float(np.asarray(cikti).reshape(-1)[0])


class Bolumleyici:
    def __init__(self, yayinla: Callable[[int, np.ndarray], None],
                 baslama_esigi: float = 0.5, bitis_esigi: float = 0.35,
                 sessizlik: float = 0.5, en_uzun: float = 8.0, en_kisa: float = 0.35,
                 on_tampon: float = 0.3):
        self.vad = SileroVAD()
        self.yayinla = yayinla
        self.baslama_esigi = baslama_esigi
        self.bitis_esigi = bitis_esigi
        self.sessizlik_ornek = int(sessizlik * ORNEKLEME)
        self.kisa_sessizlik_ornek = self.sessizlik_ornek  # bölüm yarıyı geçince yeten duraklama
        self.en_uzun_ornek = int(en_uzun * ORNEKLEME)
        self.en_kisa_ornek = int(en_kisa * ORNEKLEME)
        self.on_tampon = collections.deque(maxlen=max(1, int(on_tampon * ORNEKLEME / CERCEVE)))

        self._kalan = np.zeros(0, dtype=np.float32)
        self._aktif = False
        self._tampon: list[np.ndarray] = []
        self._olas: list[float] = []  # tampondaki her çerçevenin konuşma olasılığı
        self._sessiz = 0
        self._konusma = 0
        self._id = 0
        self._son_besleme = time.monotonic()
        self._kilit = threading.Lock()

    def besle(self, ornekler: np.ndarray) -> None:
        with self._kilit:
            self._son_besleme = time.monotonic()
            veri = np.concatenate([self._kalan, ornekler]) if self._kalan.size else ornekler
            n = len(veri) // CERCEVE * CERCEVE
            for i in range(0, n, CERCEVE):
                cerceve = np.ascontiguousarray(veri[i:i + CERCEVE], dtype=np.float32)
                self._adim(cerceve, self.vad(cerceve))
            self._kalan = veri[n:].copy()

    def bayatsa_bitir(self, bekleme: float = 0.8) -> None:
        """Ses akışı tamamen durduysa (ör. video duraklatıldı) yarım kalan bölümü bitirir."""
        with self._kilit:
            if self._aktif and time.monotonic() - self._son_besleme > bekleme:
                self._bitir()

    def _adim(self, cerceve: np.ndarray, olasilik: float) -> None:
        if not self._aktif:
            self.on_tampon.append(cerceve)
            if olasilik >= self.baslama_esigi:
                self._aktif = True
                self._id = next(_sayac)
                self._tampon = list(self.on_tampon)
                self._olas = [0.0] * (len(self._tampon) - 1) + [olasilik]
                self.on_tampon.clear()
                self._sessiz = 0
                self._konusma = CERCEVE
            return

        self._tampon.append(cerceve)
        self._olas.append(olasilik)
        if olasilik < self.bitis_esigi:
            self._sessiz += CERCEVE
        else:
            self._sessiz = 0
            self._konusma += CERCEVE

        uzunluk = len(self._tampon) * CERCEVE
        # Bölüm uzadıkça daha kısa bir duraklama da yeter: sürekli konuşmada cümle arasından bölünsün.
        esik = self.sessizlik_ornek if uzunluk < self.en_uzun_ornek // 2 else self.kisa_sessizlik_ornek
        if self._sessiz >= esik:
            self._bitir()
        elif uzunluk >= self.en_uzun_ornek:
            self._zorla_bol()

    def _zorla_bol(self) -> None:
        """En uzun süreye ulaşıldı: kelime ortasından kesmemek için son yarıdaki en sessiz
        andan böler; arta kalanı bir sonraki bölümün başı yapar."""
        n = len(self._tampon)
        olas = np.convolve(np.asarray(self._olas[-n:], dtype=np.float32), np.ones(3) / 3, "same")
        k = n // 2 + int(np.argmin(olas[n // 2:n - 1])) + 1  # k. çerçeveden itibaren sonraki bölüm
        kalan, kalan_olas = self._tampon[k:], self._olas[k:]
        self._tampon, self._sessiz = self._tampon[:k], 0
        self._bitir(devam=True)
        self._tampon, self._olas = kalan, kalan_olas
        self._konusma = sum(CERCEVE for o in kalan_olas if o >= self.bitis_esigi)

    def _bitir(self, devam: bool = False) -> None:
        if self._konusma >= self.en_kisa_ornek and self._tampon:
            ses = np.concatenate(self._tampon)
            # sondaki uzun sessizliği kırp (biraz pay bırak)
            kirp = max(0, self._sessiz - int(0.2 * ORNEKLEME))
            if kirp:
                ses = ses[:-kirp]
            self.yayinla(self._id, ses)
        self._tampon = []
        self._olas = []
        self._sessiz = 0
        self._konusma = 0
        if devam:
            self._id = next(_sayac)
        else:
            self._aktif = False
            self.vad.sifirla()
