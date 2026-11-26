"""Arayüzsüz motor sunucusu: Tauri masaüstü uygulaması bu süreci başlatır.

Protokol: satır başına bir JSON (UTF-8).
  stdin  (komutlar): {"komut": "bilgi"|"yukle"|"baslat"|"durdur"|"duraklat"|"ayarlar"|"cikis", "ayarlar": {...}}
  stdout (olaylar) : motor olayları ({"tur": "durum"|"hata"|"son", ...})
                     + {"tur": "seviye", "bilgisayar": dB} (her 100 ms)
                     + {"tur": "bilgi", "diller": [...]}
"""
from __future__ import annotations

import io
import json
import math
import os
import sys
import threading
import time

# stdout yalnızca protokol içindir; kütüphanelerin print'leri stderr'e (motor_gunluk.txt) gitsin
_PROTOKOL = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", line_buffering=True)
sys.stdout = sys.stderr

os.chdir(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from . import ses  # noqa: E402,F401  (soundcard COM'u ana iş parçacığında başlatır)
from .diller import DILLER  # noqa: E402
from .motor import Ayarlar, Motor  # noqa: E402

_yaz_kilidi = threading.Lock()


def gonder(olay: dict) -> None:
    with _yaz_kilidi:
        _PROTOKOL.write(json.dumps(olay, ensure_ascii=False) + "\n")


def ayarlar_olustur(veri: dict | None) -> Ayarlar:
    alanlar = Ayarlar.__dataclass_fields__
    return Ayarlar(**{k: v for k, v in (veri or {}).items() if k in alanlar})


def seviye_dongusu(motor: Motor) -> None:
    while True:
        gonder({"tur": "seviye", "bilgisayar": round(20 * math.log10(motor.seviye + 1e-7), 1)})
        time.sleep(0.1)


def bilgi_gonder() -> None:
    from .tanima import buyuk_var, gpu_hazir, gpu_karti_var, omni_var
    from . import ekran_karti
    from .ceviri import yerel_var
    try:
        analiz = ekran_karti.analiz()
    except Exception as e:  # analiz hiçbir zaman motoru durdurmasın
        print(f"bilgisayar analizi yapılamadı: {e}", file=sys.stderr, flush=True)
        analiz = None
    kart = (analiz or {}).get("kart")
    gonder({"tur": "bilgi", "diller": [{"kod": d.kod, "ad": d.ad, "rtl": d.rtl} for d in DILLER],
            "kurmanci_buyuk": buyuk_var(), "kurmanci_omni": omni_var(), "gpu": gpu_karti_var(), "gpu_hazir": gpu_hazir(),
            # Vulkan ile kullanılabilecek kart (AMD / Intel / NVIDIA) ve hızlandırma modeli kurulu mu
            "ekran_karti": kart["ad"] if kart else None,
            "hizlandirma_var": os.path.isfile(ekran_karti.GENEL_GGML),
            "kmr_hizlandirma_var": os.path.isfile(ekran_karti.KMR_BUYUK_GGML),
            "analiz": analiz, "yerel_ceviri_var": yerel_var()})


def main() -> None:
    motor = Motor(gonder)
    bilgi_gonder()
    threading.Thread(target=seviye_dongusu, args=(motor,), daemon=True).start()

    for satir in sys.stdin.buffer:
        try:
            istek = json.loads(satir.decode("utf-8"))
        except Exception:
            continue
        komut = istek.get("komut")
        try:
            if komut == "bilgi":
                bilgi_gonder()
                if motor.calisiyor:
                    gonder({"tur": "durum", "metin": "Dinleniyor", "hazir": True})
                elif motor.hazir:
                    gonder({"tur": "durum", "metin": "Hazır — Başlat'a basabilirsiniz.", "hazir": True})
            elif komut == "yukle":
                motor.onceden_yukle(ayarlar_olustur(istek.get("ayarlar")))
            elif komut == "baslat":
                motor.baslat(ayarlar_olustur(istek.get("ayarlar")))
            elif komut == "durdur":
                motor.durdur()
            elif komut == "duraklat":
                gonder({"tur": "duraklatildi", "deger": motor.duraklat_degistir()})
            elif komut == "ayarlar":
                motor.ayarlari_guncelle(ayarlar_olustur(istek.get("ayarlar")))
            elif komut == "cikis":
                break
        except Exception as e:
            gonder({"tur": "hata", "metin": f"{type(e).__name__}: {e}"})
    motor.durdur()
    os._exit(0)


if __name__ == "__main__":
    main()
