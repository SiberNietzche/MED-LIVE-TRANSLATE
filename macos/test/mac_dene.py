"""macOS otomatik deneme (GitHub Actions'taki gerçek Mac'te, kurulu programın Python'uyla çalışır).

Kullanım: python mac_dene.py <bolum> <wav>
  motor  : konuşmayı yazıya dökme (işlemci) + çeviri (Google ve çevrimdışı)
  kart   : ekran kartı (Metal) ile yazıya dökme — whisper-server + programın kendi kodu
  uctan  : uçtan uca: motor sunucusu bilgisayar sesini dinler, aynı anda wav çalınır (afplay)
Her bölüm sonunda "SONUC <bolum> BASARILI|BASARISIZ ..." satırı yazar.
"""
import json
import os
import subprocess
import sys
import threading
import time
import wave

import numpy as np

KOK = os.path.expanduser("~/Library/Application Support/med-live-translate")
sys.path.insert(0, KOK)
os.chdir(KOK)


def wav_oku(yol: str) -> np.ndarray:
    with wave.open(yol) as w:
        assert w.getframerate() == 16000 and w.getnchannels() == 1, "16 kHz mono wav bekleniyor"
        return np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float32) / 32768


def sonuc(bolum: str, tamam: bool, ayrinti: str) -> None:
    print(f"SONUC {bolum} {'BASARILI' if tamam else 'BASARISIZ'} {ayrinti}", flush=True)


def motor(wav: str) -> None:
    from canli_altyazi.ceviri import Cevirmen
    from canli_altyazi.tanima import Tanima
    ses = wav_oku(wav)
    t = Tanima()
    t0 = time.time()
    t.hazirla("en")
    yukleme = time.time() - t0
    t0 = time.time()
    metin = t.coz(ses, "en")
    sure = time.time() - t0
    print(f"yazıya dökme ({t.cihaz}, model {yukleme:.1f} sn, çözme {sure:.1f} sn): {metin!r}", flush=True)
    c = Cevirmen()
    google = c.cevir(metin, "en", "tr")
    print(f"çeviri ({c.son_kaynak}): {google!r}", flush=True)
    yerel = c.cevir(metin, "en", "tr", yalniz_yerel=True)
    print(f"çevrimdışı çeviri: {yerel!r}", flush=True)
    kmr = c.cevir(metin, "en", "kmr", yalniz_yerel=True)
    print(f"çevrimdışı Kurmancî: {kmr!r}", flush=True)
    tamam = "meeting" in metin.lower() and bool(yerel)
    sonuc("motor", tamam, f"cozme={sure:.1f}sn")


def kart(wav: str) -> None:
    from canli_altyazi import ekran_karti
    print("analiz:", json.dumps(ekran_karti.analiz(), ensure_ascii=False), flush=True)
    print("kullanılabilir:", ekran_karti.kullanilabilir(), "model:", ekran_karti.model_yolu("en"), flush=True)
    k = ekran_karti.EkranKarti()
    try:
        k.baslat(ekran_karti.GENEL_GGML, 4)
        ses = wav_oku(wav)
        t0 = time.time()
        parcalar = k.coz(ses, "en")
        sure = time.time() - t0
        metin = " ".join(p.get("text", "") for p in parcalar).strip()
        print(f"ekran kartı ({k.kart['ad']}) {sure:.2f} sn: {metin!r}", flush=True)
        sonuc("kart", "meeting" in metin.lower(), f"sure={sure:.2f}sn")
    except Exception as e:
        print(f"ekran kartı kullanılamadı: {e}", flush=True)
        sonuc("kart", False, str(e)[:200])
    finally:
        k.kapat()
        try:
            print(open(ekran_karti.GUNLUK, encoding="utf-8").read()[-3000:], flush=True)
        except OSError:
            pass


def uctan(wav: str) -> None:
    p = subprocess.Popen([os.path.join(KOK, "python/bin/python3"), "-u", "-m", "canli_altyazi.sunucu"], cwd=KOK,
                         stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                         stderr=open(os.path.join(KOK, "test_motor_gunluk.txt"), "w"),
                         env=dict(os.environ, PYTHONIOENCODING="utf-8", HF_HUB_OFFLINE="1"))
    olaylar: list[dict] = []

    def oku():
        for s in p.stdout:
            try:
                o = json.loads(s)
            except ValueError:
                continue
            if o.get("tur") != "seviye":
                olaylar.append(o)
                print("olay:", json.dumps(o, ensure_ascii=False)[:300], flush=True)
            elif o.get("bilgisayar", -100) > -60:
                olaylar.append({"tur": "ses_geldi"})

    threading.Thread(target=oku, daemon=True).start()

    def gonder(d):
        p.stdin.write((json.dumps(d) + "\n").encode())
        p.stdin.flush()

    gonder({"komut": "baslat", "ayarlar": {"konusma_dili": "en", "hedef_dil": "tr", "cevrimdisi": False}})
    t0 = time.time()
    while time.time() - t0 < 180 and not any("Dinleniyor" in str(o.get("metin")) for o in olaylar):
        time.sleep(0.5)
    time.sleep(3)
    for _ in range(2):  # iki kez çal: ilk çalış yakalayıcı ısınırken kaçabilir
        subprocess.run(["afplay", wav])
        time.sleep(1)
    t1 = time.time()
    while time.time() - t1 < 60 and not any(o.get("tur") == "son" for o in olaylar):
        time.sleep(0.5)
    time.sleep(5)
    gonder({"komut": "cikis"})
    try:
        p.wait(15)
    except subprocess.TimeoutExpired:
        p.kill()
    son = [o for o in olaylar if o.get("tur") == "son"]
    ses_geldi = any(o.get("tur") == "ses_geldi" for o in olaylar)
    print(open(os.path.join(KOK, "test_motor_gunluk.txt"), encoding="utf-8", errors="replace").read()[-3000:])
    sonuc("uctan", bool(son), f"cumle={len(son)} ses_seviyesi_geldi={ses_geldi}")


if __name__ == "__main__":
    {"motor": motor, "kart": kart, "uctan": uctan}[sys.argv[1]](sys.argv[2])
