"""Kurmancî yazım düzeltme: konuşma modelinin ürettiği, sözlükte olmayan kelimeleri düzeltir.

Kelime listesi (kmr_sozluk.txt): Kanî Labs Kurdish Lexicon (github.com/kanilabs/zimankit), CC BY-SA 4.0;
yazı derleminde en az 2 kez geçen ~86 bin kelime biçimi ve sıklıkları.

İki adım (FLEURS Kurmancî kayıtlarında kelime hatasını %53,4'ten %51,2'ye indirdi):
  1. Ayrık yazılmış kelimeyi birleştir: "Dana zînên" -> "Danazînên" (birleşik hâli sözlükte, parçalardan biri yoksa)
  2. Sözlükte olmayan kelimeyi (en az 3 harf) tek harf farkla ulaşılan en sık kelimeyle değiştir
     (bir harf silme / ekleme / değiştirme / yan yana iki harfin yer değiştirmesi). Aday yoksa dokunulmaz.
Android'deki Kurmanci.kt aynı kuralları uygular.
"""
from __future__ import annotations

import os
import re
import threading

_YOL = os.path.join(os.path.dirname(os.path.abspath(__file__)), "kmr_sozluk.txt")
_HARFLER = "abcçdeêfghiîjklmnopqrsştuûvwxyz"
_PARCA = re.compile(r"\w+|[^\w\s]+|\s+")
_KUCUK = str.maketrans({"I": "i", "Î": "î", "Ê": "ê", "Û": "û", "Ş": "ş", "Ç": "ç"})

_sozluk: dict[str, int] | None = None
_kilit = threading.Lock()


def _yukle() -> dict[str, int]:
    global _sozluk
    with _kilit:
        if _sozluk is None:
            s: dict[str, int] = {}
            try:
                with open(_YOL, encoding="utf-8") as f:
                    for satir in f:
                        if satir.startswith("#"):
                            continue
                        k, _, n = satir.rstrip("\n").partition("\t")
                        if k and n.isdigit():
                            s[k] = int(n)
            except OSError:
                pass  # liste yoksa düzeltme yapılmaz
            _sozluk = s
        return _sozluk


def kucuk(s: str) -> str:
    """Kurmancî küçük harf (Türkçedeki İ/ı dönüşümü olmadan)."""
    return s.translate(_KUCUK).lower()


def _bir_harf_uzak(w: str):
    for i in range(len(w) + 1):
        sol, sag = w[:i], w[i:]
        if sag:
            yield sol + sag[1:]
            for c in _HARFLER:
                if c != sag[0]:
                    yield sol + c + sag[1:]
            if len(sag) > 1:
                yield sol + sag[1] + sag[0] + sag[2:]
        for c in _HARFLER:
            yield sol + c + sag


def _en_yakin(w: str, s: dict[str, int]) -> str | None:
    en, en_f = None, 0
    for a in _bir_harf_uzak(w):
        f = s.get(a, 0)
        if f > en_f:
            en, en_f = a, f
    return en


def duzelt(metin: str) -> str:
    s = _yukle()
    if not s or not metin:
        return metin
    t = _PARCA.findall(metin)
    # 1) birleştirme
    i = 0
    while i + 2 < len(t):
        a, ara, b = t[i], t[i + 1], t[i + 2]
        if ara == " " and a.isalpha() and b.isalpha():
            ka, kb = kucuk(a), kucuk(b)
            if (ka not in s or kb not in s) and (ka + kb) in s:
                t[i:i + 3] = [a + kb]
                continue
        i += 1
    # 2) tek harf düzeltme
    for i, w in enumerate(t):
        if len(w) < 3 or not w.isalpha():
            continue
        k = kucuk(w)
        if k in s:
            continue
        y = _en_yakin(k, s)
        if y:
            t[i] = y[:1].upper() + y[1:] if w[:1].isupper() else y
    return "".join(t)
