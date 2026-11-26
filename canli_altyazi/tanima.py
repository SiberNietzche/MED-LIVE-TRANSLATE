"""Konuşmayı yazıya dökme (faster-whisper, yalnızca işlemci, PyTorch gerekmez).

İki küçük model, aynı anda yalnızca biri bellekte:
  - Kurmancî: samil24/whisper-small-kurmanji-v10, kurulumda int8 CTranslate2'ye çevrilir (~250 MB)
  - Türkçe / İngilizce / Arapça: Whisper large-v3-turbo, int8 (~800 MB)
"""
from __future__ import annotations

import atexit
import os
import re
import sys
import threading
import unicodedata

import numpy as np

from .diller import KOD_ILE, tekrar_temizle

KOK = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODELLER = os.path.join(KOK, "modeller")
GENEL_MODEL = os.path.join(MODELLER, "whisper-turbo-int8")
KURMANCI_MODEL = os.path.join(MODELLER, "whisper-small-kurmanji-ct2")
# isteğe bağlı kaliteli Kurmancî: samil24/whisper-large-kurmanji-v1 (daha doğru, işlemcide ~4 kat yavaş)
KURMANCI_BUYUK = os.path.join(MODELLER, "whisper-large-kurmanji-ct2")
# isteğe bağlı deneysel Kurmancî: Meta Omnilingual ASR 1B CTC v2 (Apache 2.0), sherpa-onnx ile işlemcide.
# CTC modeli Whisper gibi kelime kelime üretmez: uydurma cümle / tekrar döngüsü olmaz. Noktalama yok.
OMNI = os.path.join(MODELLER, "omnilingual-1b-v2")

# Whisper'ın sessizlik/müzikte sık uydurduğu cümleler
_HALUSINASYON = re.compile(
    r"(altyaz[ıi]\s*m\.?\s*k\.?|abone ol|izledi[gğ]iniz i[çc]in te[şs]ekk|"
    r"thanks? (you )?for watching|subscribe|amara\.org|"
    r"ترجمة نانسي|اشترك في القناة|^\W*$|"
    r"^\W*(you|thank you|thanks|bye|okay|hmm+|teşekkürler|شكرا)\W*$)",
    re.IGNORECASE,
)


def buyuk_var() -> bool:
    return os.path.isfile(os.path.join(KURMANCI_BUYUK, "model.bin"))


def _latin_disi(kelime: str) -> int:
    """Kelimedeki Latin alfabesi dışı harf sayısı (Arap, Uygur, Kiril…)."""
    return sum(1 for c in kelime if c.isalpha() and not unicodedata.name(c, "").startswith("LATIN"))


def omni_ayikla(metin: str) -> tuple[str, bool]:
    """Omnilingual dili zorlanamayan çok dilli bir model: kısa sözleri ("merheba") Arap/Uygur harfleriyle
    yazabiliyor. Latin dışı kelimeler atılır; metnin çoğu öyleyse (yedek = True) parça Kürtçe Küçük ile çözülür."""
    kelimeler = metin.split()
    yabanci = [k for k in kelimeler if _latin_disi(k)]
    if not yabanci:
        return metin, False
    temiz = [k for k in kelimeler if not _latin_disi(k)]
    harf_t = sum(1 for k in temiz for c in k if c.isalpha())
    harf_y = sum(1 for k in yabanci for c in k if c.isalpha())
    return " ".join(temiz), harf_y >= harf_t


def omni_var() -> bool:
    return all(os.path.isfile(os.path.join(OMNI, d)) for d in ("model.int8.onnx", "tokens.txt"))


def _omni_mu(dil: str, omni: bool) -> bool:
    return dil == "kmr" and omni and omni_var()


def model_klasoru(dil: str, kaliteli: bool = False) -> str:
    if dil != "kmr":
        return GENEL_MODEL
    return KURMANCI_BUYUK if kaliteli and buyuk_var() else KURMANCI_MODEL


def model_var(dil: str, kaliteli: bool = False) -> bool:
    return os.path.isfile(os.path.join(model_klasoru(dil, kaliteli), "model.bin"))


def _cublas_klasoru() -> str:
    """İsteğe bağlı paketle gelen cuBLAS'ın yeri (nvidia-cublas-cu12 tekerleği)."""
    import sysconfig
    taban = os.path.join(sysconfig.get_paths()["purelib"], "nvidia", "cublas")
    return os.path.join(taban, "bin") if sys.platform == "win32" else os.path.join(taban, "lib")


_CUBLAS = _cublas_klasoru()
# Linux'ta önce Lt kitaplığı yüklenmeli (cublas ona bağlı)
_CUBLAS_DOSYALARI = ["cublas64_12.dll"] if sys.platform == "win32" else ["libcublasLt.so.12", "libcublas.so.12"]


def gpu_karti_var() -> bool:
    """NVIDIA sürücüsü ve CUDA destekli kart var mı (kitaplık kurulu olmasa da)."""
    try:
        import ctranslate2
        return ctranslate2.get_cuda_device_count() > 0
    except Exception:
        return False


_cublas_eklendi = False


def gpu_hazir() -> bool:
    """Kart + isteğe bağlı paketle gelen cuBLAS kitaplığı: model ekran kartında çalışabilir.
    Kitaplık program açıkken kurulmuş olabilir: bulunduğu an DLL arama yoluna eklenir."""
    global _cublas_eklendi
    if not all(os.path.isfile(os.path.join(_CUBLAS, d)) for d in _CUBLAS_DOSYALARI):
        return False
    if not _cublas_eklendi:
        if sys.platform == "win32":
            os.add_dll_directory(_CUBLAS)
            os.environ["PATH"] = _CUBLAS + os.pathsep + os.environ.get("PATH", "")
        else:
            # ctranslate2 kitaplığı adla arar: önceden genel ad alanına yüklenince bulur
            import ctypes
            try:
                for d in _CUBLAS_DOSYALARI:
                    ctypes.CDLL(os.path.join(_CUBLAS, d), mode=ctypes.RTLD_GLOBAL)
            except OSError:
                return False
        _cublas_eklendi = True
    return gpu_karti_var()


def _is_parcacigi() -> int:
    """İşlemci çekirdeklerinin yarısı (en az 2, en çok 8): bilgisayarın geri kalanı yavaşlamasın."""
    return max(2, min(8, (os.cpu_count() or 4) // 2))


class Tanima:
    """Öncelik: 1) NVIDIA + cuBLAS (CTranslate2, kartta)  2) Vulkan ile her marka ekran kartı
    (whisper.cpp, ayrı süreçte)  3) işlemci. Kartta her sorun olduğunda işlemciye dönülür."""

    def __init__(self):
        self._kilit = threading.Lock()
        self._model = None
        self._klasor: str | None = None
        self.cihaz = "cpu"
        self._kart = None                  # ekran_karti.EkranKarti
        self._kart_bozuk: set[str] = set()  # bu oturumda kartta çalışmayan modeller
        self._omni = None                  # sherpa_onnx.OfflineRecognizer (Kürtçe Orta)
        self._yedek = None                 # Kürtçe Orta'nın Latin dışı yazdığı parçalar için Kürtçe Küçük (işlemci)

    def _omni_hazirla(self) -> None:
        with self._kilit:
            if self._omni is not None:
                return
            # bellekte tek model: Whisper'ı (ve kart sunucusunu) bırak
            self._model, self._klasor, self.cihaz = None, None, "cpu"
            if self._kart is not None:
                self._kart.kapat()
            import importlib
            importlib.invalidate_caches()  # paket program açıkken kurulmuş olabilir
            import sherpa_onnx
            self._omni = sherpa_onnx.OfflineRecognizer.from_omnilingual_asr_ctc(
                model=os.path.join(OMNI, "model.int8.onnx"), tokens=os.path.join(OMNI, "tokens.txt"),
                num_threads=_is_parcacigi())
            self._klasor = OMNI

    def _omni_coz(self, ses: np.ndarray) -> str:
        with self._kilit:
            if self._omni is None:
                raise RuntimeError("konuşma modeli hazır değil")
            akis = self._omni.create_stream()
            akis.accept_waveform(16000, ses.astype(np.float32, copy=False))
            self._omni.decode_stream(akis)
            metin = akis.result.text.strip()
            metin, yedek = omni_ayikla(metin)
            if yedek:
                # dil Kurmancîye sabit küçük Whisper ile yeniden çöz (yalnızca bu kısa parçalarda)
                if self._yedek is None:
                    from faster_whisper import WhisperModel
                    self._yedek = WhisperModel(KURMANCI_MODEL, device="cpu", compute_type="int8",
                                               cpu_threads=_is_parcacigi(), local_files_only=True)
                metin = self._whisper_coz(self._yedek, ses, "kmr")
                metin, _ = omni_ayikla(metin)
        metin = tekrar_temizle(metin)
        return metin[:1].upper() + metin[1:]  # model küçük harf yazar; cümle başı büyük

    def _kart_dene(self, dil: str, kaliteli: bool) -> bool:
        from . import ekran_karti
        yol = ekran_karti.model_yolu(dil, kaliteli)
        if not yol or yol in self._kart_bozuk or not ekran_karti.kullanilabilir():
            return False
        if self._kart is None:
            self._kart = ekran_karti.EkranKarti()
            atexit.register(self._kart.kapat)
        try:
            self._kart.baslat(yol, _is_parcacigi())
            print(f"ekran kartı (Vulkan) kullanılıyor: {self._kart.kart['ad']}", file=sys.stderr, flush=True)
            return True
        except Exception as e:
            print(f"ekran kartı (Vulkan) kullanılamadı, işlemciye geçiliyor: {e}", file=sys.stderr, flush=True)
            self._kart_bozuk.add(yol)
            return False

    def _kart_yeni_kuruldu(self, dil: str, kaliteli: bool) -> bool:
        """İşlemcide çalışırken hızlandırma paketi kurulduysa (ve henüz denenmediyse) model yeniden seçilir."""
        if self.cihaz != "cpu":
            return False
        from . import ekran_karti
        yol = ekran_karti.model_yolu(dil, kaliteli)
        return bool(yol) and yol not in self._kart_bozuk and ekran_karti.kullanilabilir()

    def hazirla(self, dil: str, kaliteli: bool = False, omni: bool = False) -> None:
        if _omni_mu(dil, omni):
            self._omni_hazirla()
            return
        klasor = model_klasoru(dil, kaliteli)
        with self._kilit:
            if self._klasor == klasor and not self._kart_yeni_kuruldu(dil, kaliteli):
                return
            self._omni = self._yedek = None
            if not model_var(dil, kaliteli):
                raise FileNotFoundError(
                    f"Model bulunamadı: {klasor}. Programı kapatıp açın; bileşen kurulumu tamamlanır.")
            from faster_whisper import WhisperModel

            # önce eskisini bırak (bellekte tek model). _klasor da boşaltılır: yükleme başarısız
            # olursa sonraki çağrı "zaten yüklü" sanıp boş modelle çalışmaz, yeniden dener.
            self._model, self._klasor, self.cihaz = None, None, "cpu"
            if not gpu_hazir() and self._kart_dene(dil, kaliteli):
                self._klasor, self.cihaz = klasor, "vulkan"
                return
            from . import ekran_karti
            denenen = ekran_karti.model_yolu(dil, kaliteli)
            if denenen:
                self._kart_bozuk.add(denenen)  # bu oturumda yeniden denenmez (her parçada yükleme olmasın)
            if self._kart is not None:
                self._kart.kapat()  # Kurmancî gibi kartta olmayan modele geçildi: belleği bırak
            if gpu_hazir():
                try:
                    m = WhisperModel(klasor, device="cuda", compute_type="int8_float16", local_files_only=True)
                    # kitaplık eksikliği ilk çözümlemede ortaya çıkar: kısa bir deneme yap
                    list(m.transcribe(np.zeros(16000, np.float32), language="en", beam_size=1,
                                      without_timestamps=True)[0])
                    self._model, self.cihaz = m, "cuda"
                except Exception as e:
                    print(f"ekran kartı kullanılamadı, işlemciye geçiliyor: {e}", file=sys.stderr, flush=True)
                    self._model = None
            if self._model is None:
                self._model = WhisperModel(klasor, device="cpu", compute_type="int8",
                                           cpu_threads=_is_parcacigi(), local_files_only=True)
            self._klasor = klasor

    def _kartta_coz(self, ses: np.ndarray, dil: str, kaliteli: bool) -> str | None:
        """Kartta çözer; kart hata verirse None döner ve bu oturumda işlemciye geçilir."""
        with self._kilit:
            if self.cihaz != "vulkan":
                return None
            try:
                parcalar = []
                for b in self._kart.coz(ses, KOD_ILE[dil].whisper):
                    if b.get("no_speech_prob", 0.0) > 0.6 and b.get("avg_logprob", 0.0) < -1.0:
                        continue
                    metin = (b.get("text") or "").strip()
                    if metin and not _HALUSINASYON.search(metin):
                        parcalar.append(metin)
                return tekrar_temizle(" ".join(parcalar).strip())
            except Exception as e:
                print(f"ekran kartı hata verdi, işlemciye geçiliyor: {e}", file=sys.stderr, flush=True)
                from . import ekran_karti
                self._kart_bozuk.add(ekran_karti.model_yolu(dil, kaliteli) or "")
                self._kart.kapat()
                self._klasor, self.cihaz = None, "cpu"
                return None

    def coz(self, ses: np.ndarray, dil: str, kaliteli: bool = False, omni: bool = False) -> str:
        if _omni_mu(dil, omni):
            self.hazirla(dil, kaliteli, omni)
            return self._omni_coz(ses)
        for _ in range(3):
            self.hazirla(dil, kaliteli)
            if self.cihaz != "vulkan":
                break
            sonuc = self._kartta_coz(ses, dil, kaliteli)
            if sonuc is not None:
                return sonuc
            # kart hata verdi: döngü başında işlemci modeli yüklenir, bu parça kaybolmaz
        with self._kilit:
            if self._model is None:
                raise RuntimeError("konuşma modeli hazır değil")
            return self._whisper_coz(self._model, ses, dil)

    @staticmethod
    def _whisper_coz(model, ses: np.ndarray, dil: str) -> str:
        bolumler, _ = model.transcribe(
            ses,
            language=KOD_ILE[dil].whisper,
            beam_size=2,
            condition_on_previous_text=False,
            without_timestamps=True,
            vad_filter=False,
            temperature=0.0,  # sıcaklık yedeği yok: kötü parçada iki kat süre harcamasın
            no_repeat_ngram_size=4,
        )
        parcalar = []
        for b in bolumler:
            if b.no_speech_prob > 0.6 and b.avg_logprob < -1.0:
                continue
            metin = b.text.strip()
            if metin and not _HALUSINASYON.search(metin):
                parcalar.append(metin)
        # ekran kartı (whisper.cpp) yolunda tekrar engeli yok; işlemcide de birimler arası döngü kalabilir
        return tekrar_temizle(" ".join(parcalar).strip())
