<p align="center"><img src="icon.png" width="128" alt="MED LIVE TRANSLATE"></p>

<h1 align="center">MED LIVE TRANSLATE</h1>

<p align="center">
Kurmancî · Türkçe · English · العربية — canlı altyazı ve çeviri<br>
Windows · macOS · Linux · Android
</p>

---

**MED LIVE TRANSLATE**, bilgisayarınızda ya da telefonunuzda çalan sesi (Zoom, Teams, Meet, YouTube, canlı yayın…)
dinler, konuşmayı **yazıya döker**, seçtiğiniz dile **çevirir** ve ekranın üstünde **altyazı** olarak gösterir.

- **Diller:** Kurmancî (Kürtçe), Türkçe, İngilizce, Arapça — hepsi birbirine çevrilir.
- **Kurmancî için özel modeller:** Kürtçe Küçük (hızlı), Orta (Meta Omnilingual) ve Büyük (daha doğru) + yazım düzeltme.
- **Gizlilik:** ses kaydedilmez ve gönderilmez; konuşma cihazınızda yazıya dökülür. İsterseniz çeviri de
  **çevrimdışı** yapılır. Hesap yok, reklam yok, kullanım istatistiği yok.
- Diğer uygulamaların üstünde duran **altyazı şeridi** ve toplantı sırasında **küçük kontrol çubuğu**.
- Ekran kartı gerekmez; varsa (NVIDIA, AMD, Intel, Apple) program bunu fark edip hızlandırma önerir.
- Türkçe, English ve Kurmancî arayüz; uygulama içinde görselli kullanım kılavuzu.

## İndir

En son sürüm: **[Releases](../../releases/latest)** sayfası.

| Platform | Dosya | Not |
|---|---|---|
| Windows 10/11 (64 bit) | `MED-LIVE-TRANSLATE_<sürüm>_windows.exe` | Kurulum birkaç saniye; modeller ilk açılışta iner (~1,2–1,8 GB) |
| macOS 13+ (Apple Silicon ve Intel) | `MED-LIVE-TRANSLATE_<sürüm>_macos.dmg` | Universal; ilk açılışta aşağıdaki "imzasız uygulama" adımı gerekir |
| Linux (x86_64, Ubuntu 22.04+ / glibc 2.34+) | `MED-LIVE-TRANSLATE_<sürüm>_linux.AppImage` | `chmod +x` ile çalıştırılır; kendi kendini güncelleyebilir |
| Linux (Debian / Ubuntu) | `MED-LIVE-TRANSLATE_<sürüm>_linux.deb` | `sudo apt install ./dosya.deb` |
| Android 10+ | `MED-LIVE-TRANSLATE_<sürüm>_android.apk` | Modeller ilk açılışta iner (~380 MB) |

Her sürümde **`SHA256SUMS.txt`** bulunur. İndirdiğiniz dosyayı doğrulamak için:
Windows `certutil -hashfile dosya SHA256` · macOS/Linux `shasum -a 256 dosya` — çıkan değer listedekiyle aynı olmalı.

### İmzasız dosya uyarıları

Program şu an ücretli bir kod imzalama sertifikasıyla imzalanmadığı için işletim sistemleri ilk açılışta uyarır:

- **Windows (SmartScreen):** "Windows bilgisayarınızı korudu" → **Ek bilgi** → **Yine de çalıştır**.
- **macOS (Gatekeeper):** uygulamayı Uygulamalar klasörüne sürükleyin, bir kez açmayı deneyin, sonra
  **Sistem Ayarları → Gizlilik ve Güvenlik → "Yine de Aç"**. Ya da Terminal'de:
  `xattr -dr com.apple.quarantine "/Applications/MED LIVE TRANSLATE.app"`
  Bilgisayar sesini duyabilmek için ilk Başlat'ta **Ekran ve Sistem Sesi Kaydı** izni istenir.
- **Android:** dosyayı açarken "Bu kaynaktan uygulama yüklemeye izin ver" seçeneğini açın.

## Kullanım

1. **Konuşulan dil** ve **Altyazı dili**ni seçin (⇄ ile yer değiştirir).
2. **Başlat**'a basın. Bilgisayarda çalan her ses (toplantı, video) dinlenir; Android'de "Telefonda çalan ses" ya da
   mikrofon seçilir.
3. Altyazı şeridini istediğiniz yere taşıyın; toplantı sırasında **Kontrol çubuğu** ile programa dönmeden
   başlat / durdur / dil değiştir.
4. Konuşmalar **Geçmiş**'te saklanır (yalnızca cihazınızda); metin dosyası olarak indirilebilir.

Ayrıntılı anlatım programın içindeki **Kılavuz** bölümündedir (F1).

## Güncellemeler

Program açılışta ve açık kaldıkça 6 saatte bir bu deponun son sürümüne bakar. Yeni sürüm varsa üstte bir şerit ve
bildirim çıkar; **Hakkında → Şimdi güncelle** ile indirip kurar. İndirilen dosya `SHA256SUMS.txt` ile doğrulanmadan
kurulmaz. Otomatik denetim Hakkında bölümünden kapatılabilir. Denetim yalnızca GitHub'dan sürüm bilgisini okur.

## Gizlilik

Kısaca: **ses cihazdan çıkmaz**; çevrimiçi çeviride yalnızca yazıya dökülen **metin** Google Çeviri'ye gider;
çevrimdışı çeviride hiçbir şey gönderilmez. Ayrıntılar: [GIZLILIK.md](GIZLILIK.md).

## Kaynaktan derleme

| Klasör | İçerik |
|---|---|
| `canli_altyazi/` | Python konuşma motoru (faster-whisper / CTranslate2, whisper.cpp sunucusu, çeviri, Kurmancî yazım düzeltme) |
| `masaustu/` | Masaüstü arayüzü (Tauri 2 + TypeScript); `src-tauri/` Rust tarafı, `kurulum/*.json` indirilecek bileşen listeleri |
| `android/` | Android uygulaması (Kotlin + whisper.cpp / CTranslate2 / sherpa-onnx JNI) |
| `macos/` | macOS ses yakalayıcı (ScreenCaptureKit, Swift) ve otomatik deneme betikleri |
| `.github/workflows/` | macOS universal derleme ve gerçek Mac'te deneme |

**Masaüstü** (Node 22, Rust, Windows'ta NSIS):
```
cd masaustu
npm ci
npx tauri build
```
Program motoru (Python) ve modelleri ilk açılışta kendisi kurar; geliştirme için `requirements.txt`.

**Android** (JDK 17+, Android SDK 35, NDK 29, CMake):
```
cd android
./gradlew :app:assembleRelease
```
İmza anahtarı depoda yoktur; `android/imza/imza.properties` yoksa hata ayıklama anahtarıyla imzalanır.

**macOS:** Actions → *macOS* → *Run workflow* (universal `.dmg`).

## Lisans

Kaynak kod **MIT** lisanslıdır — [LICENSE](LICENSE). Modeller ve veriler kendi lisanslarını taşır —
[UCUNCU_TARAF_LISANSLARI.md](UCUNCU_TARAF_LISANSLARI.md). Çevrimdışı çeviri modeli (NLLB-200) **ticari olmayan**
kullanım lisanslıdır.

## İletişim

Program ile ilgili iletişim için X'te **[@seyfi_polat21](https://x.com/seyfi_polat21)** hesabından bize ulaşabilirsiniz.

[cybergah.com](https://cybergah.com/tr/) · [Instagram](https://www.instagram.com/cybergahnet/) ·
[YouTube](https://www.youtube.com/@cybergah) · [TikTok](https://www.tiktok.com/@cybergeh) ·
[X @cyberagent2112](https://x.com/cyberagent2112)

---

## English

**MED LIVE TRANSLATE** listens to the sound playing on your computer or phone (Zoom, Teams, Meet, YouTube, live
streams…), transcribes the speech, translates it and shows it as live subtitles on top of the screen.
Languages: **Kurmanji (Kurdish), Turkish, English, Arabic** — any of them into any other.

- **Download:** see [Releases](../../releases/latest) — Windows `.exe`, macOS universal `.dmg` (macOS 13+),
  Linux `.AppImage` / `.deb`, Android `.apk` (Android 10+). Verify files with `SHA256SUMS.txt`.
- **Unsigned builds:** Windows SmartScreen → *More info* → *Run anyway*; macOS → *System Settings → Privacy &
  Security → Open Anyway* (or `xattr -dr com.apple.quarantine "/Applications/MED LIVE TRANSLATE.app"`).
- **Privacy:** audio never leaves your device; with online translation only the transcribed text is sent to
  Google Translate; offline translation sends nothing. See [GIZLILIK.md](GIZLILIK.md).
- **Updates:** the app checks this repository's latest release on start and every 6 hours and can update itself
  (verified with `SHA256SUMS.txt`).
- **License:** source code MIT; models and data keep their own licenses (the offline NLLB-200 model is
  non-commercial). Contact: X [@seyfi_polat21](https://x.com/seyfi_polat21).
