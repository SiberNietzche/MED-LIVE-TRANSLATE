"""Ekran kartı hızlandırması: whisper.cpp + Vulkan (AMD, Intel, NVIDIA — sürücüsü olan her kart).

Model ayrı bir süreçte çalışır (whisper/whisper-server.exe, yalnızca 127.0.0.1): sürücü çökse ya da
kart yetersiz kalsa bile ana motor etkilenmez; her sorunda çağıran taraf işlemci motoruna döner.
Kart seçimi: varsa harici kart, yoksa dahili kart. Yazılımsal (sanal / işlemci) aygıtlar kullanılmaz.
Kart açıldıktan sonra kısa bir hız denemesi yapılır: işlemciden belirgin hızlı değilse kullanılmaz.
"""
from __future__ import annotations

import collections
import ctypes
import io
import json
import os
import re
import socket
import subprocess
import sys
import threading
import time
import wave

import numpy as np

KOK = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WINDOWS = sys.platform == "win32"
# Apple Silicon: whisper.cpp kartı Metal ile kullanır (Vulkan yok). Intel Mac'te kart kullanılmaz.
MAC_ARM = sys.platform == "darwin" and os.uname().machine == "arm64"
SUNUCU = os.path.join(KOK, "whisper", "whisper-server.exe" if WINDOWS else "whisper-server")
GGML = os.path.join(KOK, "modeller", "ggml")
GENEL_GGML = os.path.join(GGML, "ggml-large-v3-turbo-q5_0.bin")
KMR_BUYUK_GGML = os.path.join(GGML, "ggml-whisper-large-kurmanji-q5_0.bin")  # kaliteli Kurmancî
GUNLUK = os.path.join(KOK, "ekran_karti_gunluk.txt")

# boş (sessiz) 2 sn'lik denemede bundan yavaşsa kart işe yaramaz (turbo işlemcide ~5 sn sürer)
HIZ_SINIRI_SN = 2.5
_YAZILIMSAL = re.compile(r"llvmpipe|swiftshader|basic render|lavapipe", re.IGNORECASE)
# Windows'ta alt süreç konsol penceresi açmasın; diğer sistemlerde bayrak yok
_SUREC_BAYRAK = {"creationflags": 0x08000000} if WINDOWS else {}  # CREATE_NO_WINDOW


def _vulkan_kitapligi() -> str | None:
    """Sistemdeki Vulkan yükleyicisi (sürücü kurulu değilse None)."""
    if WINDOWS:
        sistem = os.environ.get("SystemRoot", r"C:\Windows")
        yol = os.path.join(sistem, "System32", "vulkan-1.dll")
        return yol if os.path.isfile(yol) else None
    import ctypes.util
    for ad in ("libvulkan.so.1", ctypes.util.find_library("vulkan")):
        if not ad:
            continue
        try:
            ctypes.CDLL(ad)
            return ad
        except OSError:
            continue
    return None


def surucu_var() -> bool:
    if MAC_ARM:
        return True  # Metal her Apple Silicon Mac'te var
    if sys.platform == "darwin":
        return False
    return _vulkan_kitapligi() is not None


def _sysctl(ad: str) -> str:
    try:
        return subprocess.run(["sysctl", "-n", ad], capture_output=True, text=True, timeout=5).stdout.strip()
    except Exception:
        return ""


def _avx2_var() -> bool:
    """Sunucu AVX2 ile derlendi (2013 sonrası işlemciler); yoksa çalıştırılmaz, işlemci motoru kullanılır."""
    if MAC_ARM:
        return True  # ARM sürümü AVX2 gerektirmez
    try:
        if WINDOWS:
            return bool(ctypes.windll.kernel32.IsProcessorFeaturePresent(40))  # PF_AVX2_INSTRUCTIONS_AVAILABLE
        with open("/proc/cpuinfo", encoding="utf-8", errors="replace") as f:
            return " avx2" in f.read()
    except Exception:
        return False


def kullanilabilir() -> bool:
    """Program dosyası + Vulkan sürücüsü var mı (kartın gerçekten iş görüp görmediği ayrıca denenir)."""
    return os.environ.get("MLT_EKRAN_KARTI") != "0" and (sys.platform in ("win32", "linux") or MAC_ARM) \
        and surucu_var() and os.path.isfile(SUNUCU) and _avx2_var()


def model_yolu(dil: str, kaliteli: bool = False) -> str | None:
    """Bu dil için kartta çalışacak model (yoksa None: işlemci motoru kullanılır)."""
    if dil == "kmr":
        # hızlı (küçük) Kurmancî işlemcide zaten ~2 sn: yalnızca kaliteli model kartta çalışır
        yol = KMR_BUYUK_GGML if kaliteli else None
    else:
        yol = GENEL_GGML
    return yol if yol and os.path.isfile(yol) else None


# ---------------------------------------------------------------- kart listesi (Vulkan, ctypes)

def _kartlari_oku() -> list[dict]:
    """Vulkan yükleyicisinden fiziksel aygıtları okur. Ayrı bir süreçte çağrılır (sürücü çökerse motor etkilenmesin)."""
    vk = ctypes.WinDLL("vulkan-1.dll") if WINDOWS else ctypes.CDLL(_vulkan_kitapligi() or "libvulkan.so.1")

    class AppInfo(ctypes.Structure):
        _fields_ = [("sType", ctypes.c_int), ("pNext", ctypes.c_void_p), ("pApplicationName", ctypes.c_char_p),
                    ("applicationVersion", ctypes.c_uint32), ("pEngineName", ctypes.c_char_p),
                    ("engineVersion", ctypes.c_uint32), ("apiVersion", ctypes.c_uint32)]

    class CreateInfo(ctypes.Structure):
        _fields_ = [("sType", ctypes.c_int), ("pNext", ctypes.c_void_p), ("flags", ctypes.c_uint32),
                    ("pApplicationInfo", ctypes.POINTER(AppInfo)), ("enabledLayerCount", ctypes.c_uint32),
                    ("ppEnabledLayerNames", ctypes.c_void_p), ("enabledExtensionCount", ctypes.c_uint32),
                    ("ppEnabledExtensionNames", ctypes.c_void_p)]

    uyg = AppInfo(0, None, b"MED LIVE TRANSLATE", 1, b"yok", 1, (1 << 22) | (2 << 12))  # Vulkan 1.2
    bilgi = CreateInfo(1, None, 0, ctypes.pointer(uyg), 0, None, 0, None)
    ornek = ctypes.c_void_p()
    if vk.vkCreateInstance(ctypes.byref(bilgi), None, ctypes.byref(ornek)) != 0:
        return []
    try:
        say = ctypes.c_uint32(0)
        vk.vkEnumeratePhysicalDevices(ornek, ctypes.byref(say), None)
        aygitlar = (ctypes.c_void_p * say.value)()
        vk.vkEnumeratePhysicalDevices(ornek, ctypes.byref(say), aygitlar)
        sonuc = []
        for i in range(say.value):
            tampon = ctypes.create_string_buffer(4096)  # VkPhysicalDeviceProperties (~830 bayt)
            vk.vkGetPhysicalDeviceProperties(ctypes.c_void_p(aygitlar[i]), tampon)
            ham = tampon.raw
            tur = int.from_bytes(ham[16:20], "little")  # 1 dahili, 2 harici, 3 sanal, 4 işlemci
            ad = ham[20:276].split(b"\0", 1)[0].decode("utf-8", "replace")
            # VkPhysicalDeviceMemoryProperties: kartın kendi belleği (DEVICE_LOCAL yığınlarının en büyüğü)
            bel = ctypes.create_string_buffer(1024)
            vk.vkGetPhysicalDeviceMemoryProperties(ctypes.c_void_p(aygitlar[i]), bel)
            b = bel.raw
            yigin = int.from_bytes(b[260:264], "little")
            bellek = max([int.from_bytes(b[264 + 16 * j:272 + 16 * j], "little")
                          for j in range(min(yigin, 16))
                          if int.from_bytes(b[272 + 16 * j:276 + 16 * j], "little") & 1] or [0])
            sonuc.append({"sira": i, "ad": ad, "tur": tur, "ureti": int.from_bytes(ham[8:12], "little"),
                          "bellek_gb": round(bellek / 2**30, 1)})
        return sonuc
    finally:
        vk.vkDestroyInstance(ornek, None)


_kart_onbellek: dict | None = None
_tum_kartlar: list[dict] = []
_kart_kilit = threading.Lock()


def kart_sec() -> dict | None:
    """Kullanılacak kart: önce harici, yoksa dahili. Sonuç önbelleğe alınır."""
    global _kart_onbellek
    with _kart_kilit:
        if _kart_onbellek is not None:
            return _kart_onbellek or None
        secilen = {}
        if MAC_ARM:
            # tek, dahili ama güçlü kart; bellek ortak (RAM)
            ad = _sysctl("machdep.cpu.brand_string") or "Apple Silicon"
            secilen = {"sira": 0, "ad": f"{ad} GPU", "tur": 1, "ureti": 0, "bellek_gb": _ram_gb(), "apple": True}
            _tum_kartlar[:] = [secilen]
        elif surucu_var():
            try:
                p = subprocess.run([sys.executable, "-I", "-c",
                                    "import sys; sys.path.insert(0, sys.argv[1]); "
                                    "from canli_altyazi.ekran_karti import _kartlari_oku; import json; "
                                    "print(json.dumps(_kartlari_oku()))", KOK],
                                   capture_output=True, text=True, timeout=20, **_SUREC_BAYRAK)
                kartlar = json.loads(p.stdout.strip().splitlines()[-1]) if p.returncode == 0 else []
            except Exception as e:
                print(f"ekran kartı listesi okunamadı: {e}", file=sys.stderr, flush=True)
                kartlar = []
            uygun = [k for k in kartlar if k["tur"] in (1, 2) and not _YAZILIMSAL.search(k["ad"])]
            _tum_kartlar[:] = uygun
            uygun.sort(key=lambda k: (k["tur"] != 2, k["sira"]))
            zorla = os.environ.get("MLT_KART_SIRA")  # deneme için: yalnızca bu kart varmış gibi davran
            if zorla and zorla.isdigit():
                uygun = [k for k in uygun if k["sira"] == int(zorla)]
                _tum_kartlar[:] = uygun
            secilen = uygun[0] if uygun else {}
        _kart_onbellek = secilen
        return secilen or None


# ---------------------------------------------------------------- bilgisayar analizi

SONUC = os.path.join(KOK, "ekran_karti_sonuc.json")  # kartın gerçek hız denemesinin sonucu
# güçlü dahili kartlar: AMD Radeon 680M–890M / 8050S–8060S, Intel Arc; orta: Intel Iris Xe, Radeon 610M–660M
_GUCLU_DAHILI = re.compile(r"radeon.*\b(6[89]0m|7[4-9]0m|8[0-9]0m|80[5-6]0s)\b|\barc\b", re.IGNORECASE)
_ORTA_DAHILI = re.compile(r"iris.*\bxe\b|radeon.*\b6[0-6]0m\b", re.IGNORECASE)


def kart_seviyesi(k: dict) -> str:
    """'guclu' | 'orta' | 'zayif' — kartın adına ve belleğine göre tahmin (kesin sonuç hız denemesiyle)."""
    if k.get("apple"):
        return "guclu"
    if k["tur"] == 2:  # harici kart
        b = k.get("bellek_gb", 0)
        return "guclu" if b >= 3.5 else "orta" if b >= 1.8 else "zayif"
    if _GUCLU_DAHILI.search(k["ad"]):
        return "guclu"
    if _ORTA_DAHILI.search(k["ad"]):
        return "orta"
    return "zayif"  # Intel HD / UHD, eski AMD Vega vb.: işlemciden hızlı olmaz


def _sonuc_oku() -> dict:
    try:
        with open(SONUC, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def _sonuc_yaz(kart: str, uygun: bool, sure: float | None, neden: str = "") -> None:
    try:
        with open(SONUC, "w", encoding="utf-8") as f:
            json.dump({"kart": kart, "uygun": uygun, "sure": sure, "neden": neden}, f, ensure_ascii=False)
    except OSError:
        pass


def _ram_gb() -> float:
    if sys.platform == "darwin":
        try:
            return round(int(_sysctl("hw.memsize")) / 2**30, 1)
        except ValueError:
            return 0.0
    if not WINDOWS:
        try:
            return round(os.sysconf("SC_PAGE_SIZE") * os.sysconf("SC_PHYS_PAGES") / 2**30, 1)
        except (ValueError, OSError, AttributeError):
            return 0.0

    class Durum(ctypes.Structure):
        _fields_ = [("dwLength", ctypes.c_uint32), ("dwMemoryLoad", ctypes.c_uint32),
                    ("ullTotalPhys", ctypes.c_uint64), ("ullAvailPhys", ctypes.c_uint64),
                    ("ullTotalPageFile", ctypes.c_uint64), ("ullAvailPageFile", ctypes.c_uint64),
                    ("ullTotalVirtual", ctypes.c_uint64), ("ullAvailVirtual", ctypes.c_uint64),
                    ("ullAvailExtendedVirtual", ctypes.c_uint64)]
    try:
        d = Durum()
        d.dwLength = ctypes.sizeof(d)
        ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(d))
        return round(d.ullTotalPhys / 2**30, 1)
    except Exception:
        return 0.0


def _islemci_adi() -> str:
    if sys.platform == "darwin":
        return _sysctl("machdep.cpu.brand_string")
    if not WINDOWS:
        try:
            with open("/proc/cpuinfo", encoding="utf-8", errors="replace") as f:
                for satir in f:
                    if satir.startswith("model name"):
                        return " ".join(satir.split(":", 1)[1].split())
        except OSError:
            pass
        return ""
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, r"HARDWARE\DESCRIPTION\System\CentralProcessor\0") as a:
            return " ".join(str(winreg.QueryValueEx(a, "ProcessorNameString")[0]).split())
    except Exception:
        return ""


def analiz() -> dict:
    """"Bu bilgisayar için öneriler" ekranı için donanım bilgisi (yalnızca yerel, hiçbir yere gönderilmez)."""
    kart = kart_sec() if kullanilabilir() else None
    sonuc = _sonuc_oku()
    denendi = sonuc if kart and sonuc.get("kart") == kart["ad"] else {}
    return {
        "islemci": _islemci_adi(),
        "cekirdek": os.cpu_count() or 0,
        "ram_gb": _ram_gb(),
        "avx2": _avx2_var(),
        "vulkan": surucu_var(),
        "kartlar": [{"ad": k["ad"], "harici": k["tur"] == 2, "bellek_gb": k.get("bellek_gb", 0)}
                    for k in _tum_kartlar],
        "kart": ({"ad": kart["ad"], "harici": kart["tur"] == 2, "bellek_gb": kart.get("bellek_gb", 0),
                  "seviye": kart_seviyesi(kart)} if kart else None),
        # kart gerçekten denendiyse sonucu (True: hızlı, False: yavaş/çalışmadı); denenmediyse None
        "kart_denendi": denendi.get("uygun") if denendi else None,
        "kart_sure": denendi.get("sure") if denendi else None,
    }


# ---------------------------------------------------------------- sunucu

def _bos_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def _wav(ses: np.ndarray) -> bytes:
    pcm = (np.clip(ses, -1.0, 1.0) * 32767).astype("<i2").tobytes()
    b = io.BytesIO()
    with wave.open(b, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(16000)
        w.writeframes(pcm)
    return b.getvalue()


class EkranKarti:
    """Kartta çalışan whisper.cpp sunucusu. Hata durumunda istisna atar; çağıran işlemciye döner."""

    def __init__(self):
        self._surec: subprocess.Popen | None = None
        self._model: str | None = None
        self._port = 0
        self._oturum = None
        self.kart: dict | None = None
        self._satirlar: collections.deque = collections.deque(maxlen=300)

    def calisiyor(self, model: str) -> bool:
        return self._surec is not None and self._surec.poll() is None and self._model == model

    def baslat(self, model: str, is_parcacigi: int = 4) -> None:
        if self.calisiyor(model):
            return
        self.kapat()
        if not kullanilabilir():
            raise RuntimeError("Vulkan sürücüsü ya da program dosyası yok")
        kart = kart_sec()
        if not kart:
            raise RuntimeError("uygun ekran kartı bulunamadı")
        import requests
        self._oturum = requests.Session()
        self._oturum.trust_env = False  # vekil sunucu ayarı yerel bağlantıyı bozmasın
        self._port = _bos_port()
        ortam = dict(os.environ)
        ortam["GGML_VK_VISIBLE_DEVICES"] = str(kart["sira"])
        # Visual C++ çalışma kitaplıkları programın Python klasöründe
        ortam["PATH"] = os.path.join(KOK, "python") + os.pathsep + ortam.get("PATH", "")
        komut = [SUNUCU, "-m", model, "--host", "127.0.0.1", "--port", str(self._port),
                 "-t", str(is_parcacigi), "-nt", "-nf", "-bs", "2", "-nth", "0.6"]
        self._satirlar = collections.deque(maxlen=300)
        self._satirlar.append(f"kart: {kart['ad']} (sıra {kart['sira']}, tür {kart['tur']})")
        self._surec = subprocess.Popen(komut, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                       stderr=subprocess.STDOUT, env=ortam, cwd=os.path.dirname(SUNUCU),
                                       **_SUREC_BAYRAK)
        threading.Thread(target=self._oku, args=(self._surec, self._satirlar), daemon=True).start()
        self._model = model
        self.kart = kart
        try:
            son = time.time() + 120
            while True:
                if self._surec.poll() is not None:
                    raise RuntimeError(f"sunucu kapandı (kod {self._surec.returncode}): {self._gunluk_sonu()}")
                try:
                    if self._oturum.get(f"http://127.0.0.1:{self._port}/health", timeout=2).status_code == 200:
                        break
                except Exception:
                    pass
                if time.time() > son:
                    raise RuntimeError("sunucu 120 sn içinde hazır olmadı")
                time.sleep(0.3)
            gunluk = self._gunluk_sonu(200)
            if not any(k in gunluk for k in ("using Vulkan", "Vulkan0", "using Metal", "MTL0")):
                raise RuntimeError("model ekran kartına yüklenmedi")
            # Metal: simdgroup işlemleri olmayan kart (sanal makine / çok eski kart) dakikalarca sürer
            if re.search(r"simdgroup reduction\s*=\s*false", gunluk):
                raise RuntimeError("ekran kartı gerekli Metal özelliklerini desteklemiyor (simdgroup)")
            # ısınma (ilk çağrı kart programlarını hazırlar) + hız denemesi
            sessiz = np.zeros(32000, np.float32)
            self._istek(sessiz, "en", zaman=180)
            t0 = time.time()
            self._istek(sessiz, "en", zaman=60)
            sure = time.time() - t0
            self._satirlar.append(f"hız denemesi: {sure:.2f} sn")
            if sure > HIZ_SINIRI_SN:
                _sonuc_yaz(kart["ad"], False, round(sure, 2), "yavaş")
                raise RuntimeError(f"ekran kartı yavaş ({sure:.1f} sn), işlemci kullanılacak")
            _sonuc_yaz(kart["ad"], True, round(sure, 2))
        except Exception as e:
            self._satirlar.append(f"KULLANILMADI: {e}")
            self.kapat()
            raise
        finally:
            self._gunluge_yaz()

    @staticmethod
    def _oku(surec: subprocess.Popen, satirlar) -> None:
        """Sunucu çıktısı yalnızca bellekte tutulur (son 300 satır): saatlerce çalışınca dosya şişmesin."""
        for ham in surec.stdout:
            satirlar.append(ham.decode("utf-8", "replace").rstrip())

    def _gunluge_yaz(self) -> None:
        try:
            with open(GUNLUK, "w", encoding="utf-8") as g:
                g.write("\n".join(self._satirlar) + "\n")
        except OSError:
            pass

    def _gunluk_sonu(self, satir: int = 3) -> str:
        time.sleep(0.2)  # okuma iş parçacığı son satırları alsın
        return " | ".join(s for s in list(self._satirlar)[-satir:] if s.strip())

    def _istek(self, ses: np.ndarray, dil: str, zaman: float = 60) -> list[dict]:
        y = self._oturum.post(
            f"http://127.0.0.1:{self._port}/inference",
            files={"file": ("ses.wav", _wav(ses), "audio/wav")},
            data={"language": dil, "response_format": "verbose_json", "temperature": "0.0",
                  "temperature_inc": "0.0", "beam_size": "2", "no_timestamps": "true"},
            timeout=zaman,
        )
        y.raise_for_status()
        cevap = y.json()
        if "error" in cevap:
            raise RuntimeError(str(cevap["error"]))
        return cevap.get("segments") or []

    def coz(self, ses: np.ndarray, dil: str) -> list[dict]:
        """[{'text', 'no_speech_prob', 'avg_logprob'}, ...]"""
        if self._surec is None or self._surec.poll() is not None:
            raise RuntimeError(f"ekran kartı sunucusu kapalı: {self._gunluk_sonu()}")
        return self._istek(ses, dil)

    def kapat(self) -> None:
        s, self._surec, self._model = self._surec, None, None
        if s is not None and s.poll() is None:
            try:
                s.kill()
                s.wait(5)
            except Exception:
                pass


if __name__ == "__main__":
    print(json.dumps({"kartlar": _kartlari_oku() if surucu_var() else [], "secilen": kart_sec()},
                     ensure_ascii=False))
