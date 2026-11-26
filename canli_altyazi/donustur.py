"""Hugging Face Whisper modelini (safetensors) PyTorch / transformers OLMADAN CTranslate2'ye çevirir.

Kullanım:  python -m canli_altyazi.donustur <hf_klasoru> <cikis_klasoru> [int8]

CTranslate2'nin kendi dönüştürücüsü modeli transformers + PyTorch ile yükler (~3 GB ek paket).
Burada ağırlıklar safetensors'tan doğrudan numpy dizisi olarak okunur ve CTranslate2'nin
Whisper yükleyicisine aynı şekle sahip basit nesnelerle verilir.
Çıktıda ilerleme satırları: "ILERLEME <0-100>".
"""
from __future__ import annotations

import gc
import json
import os
import shutil
import sys
from types import SimpleNamespace

import numpy as np


def _ilerleme(yuzde: int) -> None:
    print(f"ILERLEME {yuzde}", flush=True)


def _safetensors_oku(yol: str):
    """safetensors dosyasını bellek eşlemeyle okur: (ad, dizi) üretir.
    Dosyanın tamamı belleğe kopyalanmaz (kütüphanenin numpy okuyucusu bunu yapıyor, 5 GB'lık
    parça için 5 GB ek bellek demek); dönen diziler dosyaya bakan görünümlerdir."""
    with open(yol, "rb") as f:
        n = int.from_bytes(f.read(8), "little")
        baslik = json.loads(f.read(n))
    mm = np.memmap(yol, dtype=np.uint8, mode="r", offset=8 + n)
    for ad, b in baslik.items():
        if ad == "__metadata__":
            continue
        bas, son = b["data_offsets"]
        ham = mm[bas:son]
        if b["dtype"] == "BF16":  # numpy'de bfloat16 yok: üst 16 bit olarak float32'ye aç
            dizi = (ham.view(np.uint16).astype(np.uint32) << 16).view(np.float32)
        else:
            dizi = ham.view({"F32": np.float32, "F16": np.float16, "F64": np.float64,
                             "I64": np.int64, "I32": np.int32}[b["dtype"]])
        yield ad, dizi.reshape(b["shape"])


def _agac(agirliklar: dict[str, np.ndarray]) -> SimpleNamespace:
    """'model.encoder.layers.0.fc1.weight' gibi anahtarlardan iç içe nesne ağacı kurar;
    sayısal adlar liste olur (encoder.layers[0])."""
    kok: dict = {}
    for anahtar, deger in agirliklar.items():
        d = kok
        parcalar = anahtar.split(".")
        for p in parcalar[:-1]:
            d = d.setdefault(p, {})
        d[parcalar[-1]] = deger

    def cevir(d):
        if not isinstance(d, dict):
            return d
        if d and all(k.isdigit() for k in d):
            return [cevir(d[str(i)]) for i in range(len(d))]
        return SimpleNamespace(**{k: cevir(v) for k, v in d.items()})

    return cevir(kok)


def donustur(kaynak: str, cikis: str, nicemleme: str = "int8") -> None:
    import ctranslate2.converters.transformers as ct2_hf
    from ctranslate2.converters.transformers import WhisperLoader
    from ctranslate2.specs import whisper_spec

    # Yükleyici "isinstance(module, transformers.Conv1D)" denetler; transformers kurulu değil.
    # Whisper'da Conv1D (GPT-2 tipi katman) yok: hiçbir nesnenin örneği olmayan boş bir sınıf yeter.
    if not hasattr(ct2_hf, "transformers"):
        ct2_hf.transformers = SimpleNamespace(Conv1D=type("Conv1D", (), {}))

    with open(os.path.join(kaynak, "config.json"), encoding="utf-8") as f:
        ayar = json.load(f)
    _ilerleme(2)

    # ağırlıklar (tek dosya ya da parçalı)
    dosyalar = sorted(f for f in os.listdir(kaynak) if f.endswith(".safetensors"))
    if not dosyalar:
        raise FileNotFoundError("safetensors dosyası yok")
    # Büyük model (~6 GB float32) bellekte float16 tutulur: ortalama bilgisayarın RAM'i yetsin.
    # Ağırlıklar tek tek okunur; dosyanın tamamı bir anda belleğe alınmaz.
    toplam = sum(os.path.getsize(os.path.join(kaynak, ad)) for ad in dosyalar)
    tur = np.float16 if toplam > 2_000_000_000 else np.float32
    agirliklar: dict[str, np.ndarray] = {}
    for i, ad in enumerate(dosyalar, 1):
        for k, v in _safetensors_oku(os.path.join(kaynak, ad)):
            # her zaman bellekte bağımsız bir kopya: dosya eşlemesi kapanabilsin (ham klasör sonra silinir)
            agirliklar[k] = v.astype(tur) if v.dtype != tur else np.array(v)
            del v
        _ilerleme(2 + int(28 * i / len(dosyalar)))
    agirliklar = {k[len("model."):] if k.startswith("model.") and not k.startswith("model.model") else k: v
                  for k, v in agirliklar.items()}

    a = _agac(agirliklar)
    config = SimpleNamespace(**ayar)
    encoder, decoder = a.encoder, a.decoder
    for m in (encoder, decoder):
        m.config = config
    model = SimpleNamespace(
        config=config,
        model=SimpleNamespace(encoder=encoder, decoder=decoder),
        # çıkış katmanı gömme ağırlığına bağlıdır (dosyada ayrıca yok)
        proj_out=getattr(a, "proj_out", None) or SimpleNamespace(weight=decoder.embed_tokens.weight),
    )
    # k_proj'ların sapması yok: yükleyici "bias is not None" denetler
    for katman in list(encoder.layers) + list(decoder.layers):
        for dikkat in ("self_attn", "encoder_attn"):
            d = getattr(katman, dikkat, None)
            if d is not None and not hasattr(d.k_proj, "bias"):
                d.k_proj.bias = None

    yukleyici = WhisperLoader()
    spec = whisper_spec.WhisperSpec(ayar["encoder_layers"], ayar["encoder_attention_heads"],
                                    ayar["decoder_layers"], ayar["decoder_attention_heads"])
    yukleyici.set_encoder(spec.encoder, encoder)
    yukleyici.set_decoder(spec.decoder, decoder)
    yukleyici.set_linear(spec.decoder.projection, model.proj_out)
    # Artık ağırlıkları yalnızca spec tutsun: yükleyicinin birleştirdiği (q,k,v) kopyaların
    # kaynakları ve nicemleme sırasında yerine int8 konan float dizileri bellekten düşsün.
    del agirliklar, a, encoder, decoder, model
    katman = d = None
    gc.collect()
    _ilerleme(40)

    # yapılandırma (generation_config.json)
    uretim = {}
    yol = os.path.join(kaynak, "generation_config.json")
    if os.path.isfile(yol):
        with open(yol, encoding="utf-8") as f:
            uretim = json.load(f)
    c = spec.config
    c.suppress_ids = uretim.get("suppress_tokens", ayar.get("suppress_tokens"))
    c.suppress_ids_begin = uretim.get("begin_suppress_tokens", ayar.get("begin_suppress_tokens"))
    c.lang_ids = sorted(uretim["lang_to_id"].values()) if "lang_to_id" in uretim else None
    c.alignment_heads = uretim.get("alignment_heads") or [
        [l, h] for l in range(ayar["decoder_layers"] // 2, ayar["decoder_layers"])
        for h in range(ayar["decoder_attention_heads"])]

    # sözlük: vocab.json + added_tokens.json, sonra zaman damgası belirteçleri
    with open(os.path.join(kaynak, "vocab.json"), encoding="utf-8") as f:
        sozluk = json.load(f)
    ek = os.path.join(kaynak, "added_tokens.json")
    if os.path.isfile(ek):
        with open(ek, encoding="utf-8") as f:
            sozluk.update(json.load(f))
    belirtecler = [t for t, _ in sorted(sozluk.items(), key=lambda x: x[1])]
    belirtecler.extend("<|%.2f|>" % (i * 0.02) for i in range(ayar["vocab_size"] - len(belirtecler)))
    if c.lang_ids is None:
        dil_disi = {"<|endoftext|>", "<|startoftranscript|>", "<|translate|>", "<|transcribe|>",
                    "<|startoflm|>", "<|startofprev|>", "<|nocaptions|>", "<|notimestamps|>"}
        c.lang_ids = [sozluk[t] for t in sozluk if t.startswith("<|") and t.endswith("|>")
                      and t not in dil_disi and len(t) <= 9 and not t[2].isdigit()]
    spec.register_vocabulary(belirtecler)
    _ilerleme(50)

    spec.validate()
    spec.optimize(quantization=nicemleme)
    _ilerleme(85)

    yarim = cikis + "_yarim"
    shutil.rmtree(yarim, ignore_errors=True)
    os.makedirs(yarim)
    spec.save(yarim)
    for dosya in ("preprocessor_config.json", "tokenizer.json"):
        if os.path.isfile(os.path.join(kaynak, dosya)):
            shutil.copy(os.path.join(kaynak, dosya), os.path.join(yarim, dosya))
    shutil.rmtree(cikis, ignore_errors=True)
    os.replace(yarim, cikis)  # yalnızca tamamlanınca asıl adına taşınır
    _ilerleme(100)


if __name__ == "__main__":
    donustur(sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else "int8")
