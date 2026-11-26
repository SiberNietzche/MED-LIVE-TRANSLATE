# Üçüncü taraf bileşenler ve lisanslar / Third-party components and licenses

MED LIVE TRANSLATE'in kendi kaynak kodu MIT lisanslıdır (`LICENSE`). Program aşağıdaki bileşenleri, modelleri ve
verileri kullanır; her biri kendi lisansına tabidir.
The app's own source code is MIT-licensed. The components below keep their own licenses.

## Konuşma tanıma ve çeviri modelleri / Models
Modeller programla birlikte gelmez; ilk açılışta (ya da isteğe bağlı olarak) indirilir.
Models are not bundled; they are downloaded on first start or on demand.

| Model | Kaynak / Source | Lisans / License |
|---|---|---|
| Whisper large-v3-turbo (CTranslate2 int8, ggml q5_0) | OpenAI · Zoont/faster-whisper-large-v3-turbo-int8-ct2 · ggerganov/whisper.cpp | MIT |
| Whisper small (ggml q5_1, Android) | OpenAI · ggerganov/whisper.cpp | MIT |
| Kurmancî Whisper small / large | samil24/whisper-small-kurmanji-v10, samil24/whisper-large-kurmanji-v1 | Apache-2.0 |
| Omnilingual ASR 1B CTC (Kürtçe Orta) | Meta · k2-fsa/sherpa-onnx sürümleri | Apache-2.0 |
| NLLB-200 distilled 600M (çevrimdışı çeviri / offline translation) | Meta · facebook/nllb-200-distilled-600M | **CC-BY-NC-4.0 — yalnızca ticari olmayan kullanım / non-commercial use only** |
| Silero VAD v5.1.2 | snakers4/silero-vad (ggml dönüşümü) | MIT |

Dönüştürülmüş kopyalar / converted copies: https://huggingface.co/Seyfipolat/med-live-translate-modeller

## Veri / Data
| | Kaynak | Lisans |
|---|---|---|
| `kmr_sozluk.txt` (Kurmancî yazım düzeltme sözlüğü) | Kanî Lexicon — Kanî Labs / zimankit (Kaggle: zeroxdolan/kurdish-lexicon) | **CC BY-SA 4.0** (bu dosya da CC BY-SA 4.0'dır) |

## Kütüphaneler ve araçlar / Libraries
| Bileşen | Lisans |
|---|---|
| whisper.cpp / ggml (masaüstü ekran kartı sunucusu, Android) | MIT |
| CTranslate2, faster-whisper | MIT |
| SentencePiece | Apache-2.0 |
| sherpa-onnx 2.0.3 (`android/app/libs/sherpa-onnx-2.0.3.aar`, PyPI) | Apache-2.0 |
| ONNX Runtime | MIT |
| Tauri 2 ve eklentileri | MIT / Apache-2.0 |
| AndroidX, Apache Commons Compress | Apache-2.0 |
| Python 3.11 (gömülü / embeddable) | PSF License |
| NumPy, soundcard, requests ve diğer Python paketleri (`requirements.txt`) | BSD / MIT / Apache-2.0 (her paketin kendi lisansı) |
| Microsoft Visual C++ çalışma zamanı (`msvcp140.dll`, `msvcp140_1.dll`) | Microsoft Visual C++ Redistributable lisansı (yeniden dağıtılabilir) |

## Çevrimiçi hizmetler / Online services
- **Google Çeviri / Google Translate:** çevrimiçi çeviri için yazıya dökülen metin gönderilir. Google'ın şartları ve
  gizlilik politikası geçerlidir. Çevrimdışı çeviri seçeneğiyle kapatılabilir.
- **Hugging Face, GitHub:** model ve sürüm indirmeleri.

## Ticari kullanım notu / Commercial use note
NLLB-200 modeli CC-BY-NC-4.0 lisanslı olduğundan, programın çevrimdışı çeviri özelliği **ticari amaçla**
(ücretli dağıtım, reklam, ücretli hizmet) kullanılamaz. Ticari kullanımda bu model başka bir modelle değiştirilmelidir.
