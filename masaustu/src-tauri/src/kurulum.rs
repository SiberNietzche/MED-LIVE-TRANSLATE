//! Bileşen kurulumu: programın ilk açılışında Python'u, paketleri ve modelleri indirir.
//!
//! Her şey `kurulum/bilesenler.json` listesinden gelir (adres, boyut, sha256). Toplam boyut önceden
//! bilindiği için ön yüze gerçek yüzde, hız ve kalan süre gönderilir ("kurulum" olayı).
//! pip kullanılmaz: paketler (wheel = zip) doğrudan Python'un site-packages klasörüne açılır.
//! Kopan indirme kaldığı yerden sürer; biten her adım bir daha yapılmaz.

use std::fs::{self, File, OpenOptions};
use std::io::{BufRead, BufReader, Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

use serde::Deserialize;
use serde_json::json;
use sha2::{Digest, Sha256};
use tauri::{AppHandle, Emitter};

#[cfg(windows)]
use std::os::windows::process::CommandExt;
#[cfg(windows)]
const CREATE_NO_WINDOW: u32 = 0x0800_0000;

/// Kurulabilir bir bileşen paketi: dosya listesi + bitti işareti + dönüştürülecek Kurmancî modeli.
struct Paket {
    liste: &'static str,
    tamam: &'static str,
    ham: &'static str,
    ct2: &'static str,
    donustur_mesaj: &'static str,
}

/// İlk açılışta kurulan temel bileşenler (Python, paketler, genel model, hızlı Kurmancî modeli).
const TEMEL: Paket = Paket {
    #[cfg(windows)]
    liste: include_str!("../kurulum/bilesenler.json"),
    #[cfg(target_os = "linux")]
    liste: include_str!("../kurulum/bilesenler_linux.json"),
    // universal Mac uygulaması iki ayrı derlemenin birleşimidir: her yarı kendi işlemcisinin listesini taşır
    #[cfg(all(target_os = "macos", target_arch = "aarch64"))]
    liste: include_str!("../kurulum/bilesenler_macos_arm64.json"),
    #[cfg(all(target_os = "macos", target_arch = "x86_64"))]
    liste: include_str!("../kurulum/bilesenler_macos_x64.json"),
    tamam: "bilesenler_tamam.txt",
    ham: "modeller/_kurmanci_ham",
    ct2: "modeller/whisper-small-kurmanji-ct2",
    donustur_mesaj: "Kurmancî modeli hazırlanıyor…",
};

/// İsteğe bağlı "kaliteli Kurmancî": büyük model (+ NVIDIA kartı varsa cuBLAS).
const KALITELI: Paket = Paket {
    #[cfg(windows)]
    liste: include_str!("../kurulum/kaliteli.json"),
    #[cfg(target_os = "linux")]
    liste: include_str!("../kurulum/kaliteli_linux.json"),
    #[cfg(all(target_os = "macos", target_arch = "aarch64"))]
    liste: include_str!("../kurulum/kaliteli_macos_arm64.json"),
    #[cfg(all(target_os = "macos", target_arch = "x86_64"))]
    liste: include_str!("../kurulum/kaliteli_macos_x64.json"),
    tamam: "kaliteli_tamam.txt",
    ham: "modeller/_kurmanci_buyuk_ham",
    ct2: "modeller/whisper-large-kurmanji-ct2",
    donustur_mesaj: "Kaliteli Kurmancî modeli hazırlanıyor…",
};
/// İsteğe bağlı "ekran kartı hızlandırması": Vulkan ile her marka kartta çalışan whisper.cpp modeli.
/// Dönüştürme yok (ct2 boş): dosya olduğu gibi kullanılır.
const HIZLANDIRMA: Paket = Paket {
    liste: include_str!("../kurulum/hizlandirma.json"),
    tamam: "hizlandirma_tamam.txt",
    ham: "",
    ct2: "",
    donustur_mesaj: "",
};
/// Kaliteli Kurmancî modelinin ekran kartı (whisper.cpp) sürümü.
const HIZLANDIRMA_KMR: Paket = Paket {
    liste: include_str!("../kurulum/hizlandirma_kmr.json"),
    tamam: "hizlandirma_kmr_tamam.txt",
    ham: "",
    ct2: "",
    donustur_mesaj: "",
};
/// İsteğe bağlı "Kürtçe Orta": Meta Omnilingual ASR 1B CTC v2 (sherpa-onnx, işlemcide).
const OMNI: Paket = Paket {
    #[cfg(windows)]
    liste: include_str!("../kurulum/omni.json"),
    #[cfg(target_os = "linux")]
    liste: include_str!("../kurulum/omni_linux.json"),
    #[cfg(all(target_os = "macos", target_arch = "aarch64"))]
    liste: include_str!("../kurulum/omni_macos_arm64.json"),
    #[cfg(all(target_os = "macos", target_arch = "x86_64"))]
    liste: include_str!("../kurulum/omni_macos_x64.json"),
    tamam: "omni_tamam.txt",
    ham: "",
    ct2: "",
    donustur_mesaj: "",
};
/// İndirmeler toplam ilerlemenin bu kadarı; kalan kısım dönüştürme ve denetim.
const INDIRME_PAYI: f64 = 92.0;

static CALISIYOR: AtomicBool = AtomicBool::new(false);

#[derive(Deserialize)]
struct Liste {
    surum: u32,
    dosyalar: Vec<Dosya>,
}

#[derive(Deserialize, Clone)]
struct Dosya {
    tur: String, // python | paket | gpu | model | arsiv (tar.bz2, "ad" klasörüne açılır)
    ad: String,
    url: String,
    boyut: u64,
    sha256: String,
    aciklama: String,
}

fn liste(p: &Paket) -> Liste {
    serde_json::from_str(p.liste).expect("bileşen listesi bozuk")
}

/// NVIDIA sürücüsü kurulu mu (CUDA kitaplığını yalnızca o zaman indir).
fn nvidia_var() -> bool {
    #[cfg(windows)]
    {
        let sistem = std::env::var("SystemRoot").unwrap_or_else(|_| r"C:\Windows".into());
        Path::new(&sistem).join("System32").join("nvcuda.dll").is_file()
    }
    #[cfg(not(windows))]
    {
        Path::new("/proc/driver/nvidia/version").is_file()
    }
}

pub fn python_yolu(kok: &Path) -> PathBuf {
    #[cfg(windows)]
    {
        kok.join("python").join("python.exe")
    }
    #[cfg(not(windows))]
    {
        kok.join("python").join("bin").join("python3")
    }
}

/// Paketlerin açıldığı klasör.
fn site_paketleri(kok: &Path) -> PathBuf {
    #[cfg(windows)]
    {
        kok.join("python").join("Lib").join("site-packages")
    }
    #[cfg(not(windows))]
    {
        kok.join("python").join("lib").join("python3.11").join("site-packages")
    }
}

/// Python açma işi bitince yazılan işaret: yoksa önceki açma yarıda kalmıştır, Python yeniden kurulur.
fn python_isareti(kok: &Path) -> PathBuf {
    #[cfg(windows)]
    {
        kok.join("python").join("python311._pth")
    }
    #[cfg(not(windows))]
    {
        kok.join("python").join(".mlt_python_tamam")
    }
}

/// Bileşenler eksiksiz kurulu mu?
pub fn tamam_mi(kok: &Path) -> bool {
    let surum = liste(&TEMEL).surum.to_string();
    python_yolu(kok).is_file()
        && kok.join(TEMEL.ct2).join("model.bin").is_file()
        && fs::read_to_string(kok.join(TEMEL.tamam)).map(|s| s.trim() == surum).unwrap_or(false)
}

/// İsteğe bağlı paketin boyutu (MB) ve bu bilgisayar için indirilecek kısmı.
pub fn kaliteli_bilgi(kok: &Path) -> serde_json::Value {
    let l = liste(&KALITELI);
    let gpu = nvidia_var();
    let model_var = kok.join(KALITELI.ct2).join("model.bin").is_file();
    let kurulanlar = fs::read_to_string(kok.join("python").join("kurulan_paketler.txt")).unwrap_or_default();
    let mb: u64 = l
        .dosyalar
        .iter()
        .filter(|d| match d.tur.as_str() {
            "gpu" => gpu && !kurulanlar.lines().any(|s| s == d.ad),
            _ => !model_var,
        })
        .map(|d| d.boyut)
        .sum::<u64>()
        / 1_000_000;
    json!({"indirme_mb": mb, "nvidia": gpu})
}

/// Kürtçe Orta paketinin kalan indirme boyutu (MB).
pub fn omni_bilgi(kok: &Path) -> serde_json::Value {
    let kurulanlar = fs::read_to_string(kok.join("python").join("kurulan_paketler.txt")).unwrap_or_default();
    let mb: u64 = liste(&OMNI)
        .dosyalar
        .iter()
        .filter(|d| match d.tur.as_str() {
            "arsiv" => !arsiv_acik(kok, d),
            _ => !kurulanlar.lines().any(|s| s == d.ad),
        })
        .map(|d| d.boyut)
        .sum::<u64>()
        / 1_000_000;
    json!({"indirme_mb": mb, "destek": true})
}

/// Arşivden açılan model klasörü hazır mı.
fn arsiv_acik(kok: &Path, d: &Dosya) -> bool {
    let k = kok.join(&d.ad);
    k.join("model.int8.onnx").is_file() && k.join("tokens.txt").is_file()
}

/// tar.bz2 model arşivini Python'un tarfile'ı ile açar (yalnızca model dosyaları, önce geçici klasöre).
fn arsiv_ac(b: &mut Bildirici, kok: &Path, arsiv: &Path, d: &Dosya) -> Result<(), String> {
    b.asama("donustur", INDIRME_PAYI, &format!("{} açılıyor…", d.aciklama));
    const BETIK: &str = r#"
import os, shutil, sys, tarfile
arsiv, hedef = sys.argv[1], sys.argv[2]
gecici = hedef + ".acilis"
shutil.rmtree(gecici, ignore_errors=True)
os.makedirs(gecici)
with tarfile.open(arsiv, "r:bz2") as t:
    for u in t:
        ad = os.path.basename(u.name)
        if u.isfile() and ad in ("model.int8.onnx", "tokens.txt", "LICENSE", "README.md"):
            with t.extractfile(u) as k, open(os.path.join(gecici, ad), "wb") as c:
                shutil.copyfileobj(k, c, 1 << 20)
if not os.path.isfile(os.path.join(gecici, "model.int8.onnx")):
    sys.exit("arşivde model.int8.onnx yok")
shutil.rmtree(hedef, ignore_errors=True)
os.replace(gecici, hedef)
"#;
    let cikti = python_komutu(kok)
        .arg("-c").arg(BETIK).arg(arsiv).arg(kok.join(&d.ad))
        .output()
        .map_err(|e| format!("Python başlatılamadı: {e}"))?;
    if !cikti.status.success() {
        let hata = String::from_utf8_lossy(&cikti.stderr).to_string();
        b.gunluk(&format!("arşiv açma hatası:
{hata}"));
        let son = hata.lines().rev().find(|s| !s.trim().is_empty()).unwrap_or("").to_string();
        return Err(format!("{} açılamadı (disk dolu olabilir): {son}", d.aciklama));
    }
    Ok(())
}

/// Hızlandırma paketinin bu bilgisayar için kalan indirme boyutu (MB).
pub fn hizlandirma_bilgi(kok: &Path, kmr: bool) -> serde_json::Value {
    let mb: u64 = liste(if kmr { &HIZLANDIRMA_KMR } else { &HIZLANDIRMA })
        .dosyalar
        .iter()
        .filter(|d| fs::metadata(kok.join(&d.ad)).map(|m| m.len() != d.boyut).unwrap_or(true))
        .map(|d| d.boyut)
        .sum::<u64>()
        / 1_000_000;
    json!({"indirme_mb": mb})
}

// ---------------------------------------------------------------- ilerleme bildirimi

struct Bildirici {
    app: AppHandle,
    gunluk: Option<File>,
    toplam: u64,
    biten: u64,      // tamamen inmiş dosyaların toplamı (+ inmekte olanın kısmı)
    son_gonderim: Instant,
    olcum: Vec<(Instant, u64)>, // hız için son birkaç saniyenin örnekleri
    mesaj: String,
    baslangic: Instant,
}

impl Bildirici {
    fn gunluk(&mut self, metin: &str) {
        if let Some(g) = self.gunluk.as_mut() {
            let sn = self.baslangic.elapsed().as_secs();
            let _ = writeln!(g, "[+{:02}:{:02}] {metin}", sn / 60, sn % 60);
        }
    }

    fn yuzde_indirme(&self) -> f64 {
        if self.toplam == 0 { INDIRME_PAYI } else { INDIRME_PAYI * self.biten as f64 / self.toplam as f64 }
    }

    fn hiz(&mut self) -> f64 {
        let simdi = Instant::now();
        self.olcum.push((simdi, self.biten));
        self.olcum.retain(|(t, _)| simdi.duration_since(*t) < Duration::from_secs(6));
        let (t0, b0) = self.olcum[0];
        let sure = simdi.duration_since(t0).as_secs_f64();
        if sure < 0.5 { 0.0 } else { (self.biten.saturating_sub(b0)) as f64 / sure }
    }

    fn indirme_bildir(&mut self, zorla: bool) {
        if !zorla && self.son_gonderim.elapsed() < Duration::from_millis(250) {
            return;
        }
        self.son_gonderim = Instant::now();
        let hiz = self.hiz();
        let kalan = if hiz > 1.0 { Some(((self.toplam - self.biten.min(self.toplam)) as f64 / hiz) as u64) } else { None };
        let _ = self.app.emit("kurulum", json!({
            "asama": "indir", "yuzde": self.yuzde_indirme(), "mesaj": self.mesaj,
            "indirilen": self.biten, "toplam": self.toplam, "hiz": hiz, "kalan_sn": kalan,
        }));
    }

    fn asama(&mut self, asama: &str, yuzde: f64, mesaj: &str) {
        self.gunluk(mesaj);
        let _ = self.app.emit("kurulum", json!({
            "asama": asama, "yuzde": yuzde, "mesaj": mesaj,
            "indirilen": self.biten, "toplam": self.toplam,
        }));
    }
}

// ---------------------------------------------------------------- indirme

fn ajan() -> ureq::Agent {
    ureq::AgentBuilder::new()
        .timeout_connect(Duration::from_secs(20))
        .timeout_read(Duration::from_secs(60))
        .redirects(0) // yönlendirmeler elle izlenir: "Range" başlığı her adımda korunsun
        .user_agent("MED-LIVE-TRANSLATE-kurulum")
        .build()
}

/// `baslangic` baytından itibaren ister; yönlendirmeleri izler.
fn iste(ajan: &ureq::Agent, url: &str, baslangic: u64) -> Result<ureq::Response, String> {
    let mut adres = url.to_string();
    for _ in 0..10 {
        let mut istek = ajan.get(&adres);
        if baslangic > 0 {
            istek = istek.set("Range", &format!("bytes={baslangic}-"));
        }
        let yanit = match istek.call() {
            Ok(y) => y,
            Err(ureq::Error::Status(kod, y)) if (300..400).contains(&kod) => y,
            Err(ureq::Error::Status(416, _)) => return Err("aralık geçersiz (416)".into()),
            Err(e) => return Err(e.to_string()),
        };
        if (300..400).contains(&yanit.status()) {
            let konum = yanit.header("location").ok_or("yönlendirme adresi yok")?;
            adres = if konum.starts_with("http") {
                konum.to_string()
            } else {
                let kok = url::Url::parse(&adres).map_err(|e| e.to_string())?;
                kok.join(konum).map_err(|e| e.to_string())?.to_string()
            };
            continue;
        }
        return Ok(yanit);
    }
    Err("çok fazla yönlendirme".into())
}

pub(crate) fn sha256_dosya(yol: &Path) -> Result<String, String> {
    let mut f = File::open(yol).map_err(|e| e.to_string())?;
    let mut h = Sha256::new();
    let mut tampon = vec![0u8; 1 << 20];
    loop {
        let n = f.read(&mut tampon).map_err(|e| e.to_string())?;
        if n == 0 {
            break;
        }
        h.update(&tampon[..n]);
    }
    Ok(format!("{:x}", h.finalize()))
}

/// Dosyayı `hedef`e indirir (önce `hedef.part`). Kopan bağlantıda kaldığı yerden sürer.
fn indir(b: &mut Bildirici, d: &Dosya, hedef: &Path) -> Result<(), String> {
    let ajan = ajan();
    let parca = PathBuf::from(format!("{}.part", hedef.display()));
    if let Some(ust) = hedef.parent() {
        fs::create_dir_all(ust).map_err(|e| e.to_string())?;
    }
    let onceki_biten = b.biten;
    let mut deneme = 0u32;
    loop {
        let mevcut = fs::metadata(&parca).map(|m| m.len()).unwrap_or(0);
        if mevcut > d.boyut {
            // sunucudaki dosya listedekinden büyük: baştan indir, sürekli oluyorsa vazgeç
            let _ = fs::remove_file(&parca);
            deneme += 1;
            if deneme > 3 {
                return Err(format!("{} beklenenden farklı boyutta iniyor", d.ad));
            }
            continue;
        }
        b.biten = onceki_biten + mevcut;
        if mevcut == d.boyut {
            // boyut tamam: içerik denetimi
            if !d.sha256.is_empty() {
                b.mesaj = format!("{} doğrulanıyor…", d.aciklama);
                b.indirme_bildir(true);
                if sha256_dosya(&parca)? != d.sha256 {
                    b.gunluk(&format!("sha256 uyuşmadı, yeniden indirilecek: {}", d.ad));
                    let _ = fs::remove_file(&parca);
                    deneme += 1;
                    if deneme > 3 {
                        return Err(format!("{} bozuk iniyor (doğrulama başarısız)", d.ad));
                    }
                    continue;
                }
            }
            let _ = fs::remove_file(hedef);
            fs::rename(&parca, hedef).map_err(|e| e.to_string())?;
            b.biten = onceki_biten + d.boyut;
            return Ok(());
        }

        let sonuc = (|| -> Result<(), String> {
            let yanit = iste(&ajan, &d.url, mevcut)?;
            let ekle = mevcut > 0 && yanit.status() == 206;
            if !ekle {
                b.biten = onceki_biten; // sunucu baştan gönderiyor
            }
            let mut f = if ekle {
                OpenOptions::new().append(true).open(&parca)
            } else {
                File::create(&parca)
            }
            .map_err(|e| e.to_string())?;
            let mut okuyucu = yanit.into_reader();
            let mut tampon = vec![0u8; 256 * 1024];
            loop {
                let n = okuyucu.read(&mut tampon).map_err(|e| e.to_string())?;
                if n == 0 {
                    break;
                }
                f.write_all(&tampon[..n]).map_err(|e| format!("diske yazılamadı: {e}"))?;
                b.biten += n as u64;
                b.indirme_bildir(false);
            }
            f.flush().map_err(|e| e.to_string())
        })();

        if let Err(e) = sonuc {
            // disk dolu / yazma izni yok: yeniden denemek işe yaramaz, "bağlantı koptu" da denmemeli
            if e.starts_with("diske yazılamadı") {
                return Err(format!("{} kaydedilemedi, disk dolu olabilir: {e}", d.aciklama));
            }
            deneme += 1;
            b.gunluk(&format!("indirme hatası ({}), deneme {deneme}: {e}", d.ad));
            if deneme >= 30 {
                return Err(format!("{} indirilemedi: {e}", d.aciklama));
            }
            let bekle = (deneme as u64 * 2).min(15);
            b.mesaj = format!("Bağlantı koptu, {bekle} sn sonra kaldığı yerden devam edilecek…");
            b.indirme_bildir(true);
            std::thread::sleep(Duration::from_secs(bekle));
            b.mesaj = format!("{} indiriliyor", d.aciklama);
        }
    }
}

/// Klasörün bulunduğu diskteki boş alan (bayt); öğrenilemezse None (denetim atlanır).
fn bos_alan(kok: &Path) -> Option<u64> {
    #[cfg(windows)]
    {
        use std::os::windows::ffi::OsStrExt;
        use windows_sys::Win32::Storage::FileSystem::GetDiskFreeSpaceExW;
        let yol: Vec<u16> = kok.as_os_str().encode_wide().chain(std::iter::once(0)).collect();
        let mut bos: u64 = 0;
        let tamam = unsafe { GetDiskFreeSpaceExW(yol.as_ptr(), &mut bos, std::ptr::null_mut(), std::ptr::null_mut()) };
        return (tamam != 0).then_some(bos);
    }
    #[cfg(unix)]
    {
        use std::os::unix::ffi::OsStrExt;
        let yol = std::ffi::CString::new(kok.as_os_str().as_bytes()).ok()?;
        let mut s: libc::statvfs = unsafe { std::mem::zeroed() };
        if unsafe { libc::statvfs(yol.as_ptr(), &mut s) } != 0 {
            return None;
        }
        return Some(s.f_bavail as u64 * s.f_frsize as u64);
    }
    #[allow(unreachable_code)]
    None
}

// ---------------------------------------------------------------- açma

fn zip_ac(arsiv: &Path, hedef: &Path, wheel: bool) -> Result<(), String> {
    let f = File::open(arsiv).map_err(|e| e.to_string())?;
    let mut z = zip::ZipArchive::new(f).map_err(|e| format!("{}: {e}", arsiv.display()))?;
    for i in 0..z.len() {
        let mut girdi = z.by_index(i).map_err(|e| e.to_string())?;
        if girdi.is_dir() {
            continue;
        }
        let Some(ad) = girdi.enclosed_name() else { continue };
        let mut parcalar: Vec<String> =
            ad.components().map(|c| c.as_os_str().to_string_lossy().into_owned()).collect();
        // wheel'in "<paket>.data/purelib|platlib/..." klasörleri site-packages'a; diğerleri gereksiz
        if wheel && parcalar.first().is_some_and(|p| p.ends_with(".data")) {
            if parcalar.len() > 2 && (parcalar[1] == "purelib" || parcalar[1] == "platlib") {
                parcalar.drain(..2);
            } else {
                continue;
            }
        }
        let yol = parcalar.iter().fold(hedef.to_path_buf(), |y, p| y.join(p));
        if let Some(ust) = yol.parent() {
            fs::create_dir_all(ust).map_err(|e| e.to_string())?;
        }
        let mut cikti = File::create(&yol).map_err(|e| format!("{}: {e}", yol.display()))?;
        std::io::copy(&mut girdi, &mut cikti).map_err(|e| e.to_string())?;
    }
    Ok(())
}

#[cfg(not(windows))]
fn python_kur(kok: &Path, arsiv: &Path) -> Result<(), String> {
    // python-build-standalone "install_only": arşivin içinde tek bir "python/" klasörü var
    let py = kok.join("python");
    if py.exists() {
        fs::remove_dir_all(&py).map_err(|e| format!("eski Python silinemedi: {e}"))?;
    }
    let f = File::open(arsiv).map_err(|e| e.to_string())?;
    let mut t = tar::Archive::new(flate2::read::GzDecoder::new(f));
    t.unpack(kok).map_err(|e| format!("Python açılamadı (disk dolu olabilir): {e}"))?;
    if !python_yolu(kok).is_file() {
        return Err("Python arşivi beklenen düzende değil".into());
    }
    fs::create_dir_all(site_paketleri(kok)).map_err(|e| e.to_string())?;
    fs::write(python_isareti(kok), "1").map_err(|e| e.to_string())?;
    Ok(())
}

#[cfg(windows)]
fn python_kur(kok: &Path, arsiv: &Path) -> Result<(), String> {
    let py = kok.join("python");
    if py.exists() {
        fs::remove_dir_all(&py).map_err(|e| format!("eski Python silinemedi: {e}"))?;
    }
    zip_ac(arsiv, &py, false)?;
    // gömülebilir Python yalnızca bu dosyadaki yollara bakar: paketler + program klasörü (..)
    fs::write(py.join("python311._pth"), "python311.zip\r\n.\r\nLib\\site-packages\r\n..\r\nimport site\r\n")
        .map_err(|e| e.to_string())?;
    fs::create_dir_all(py.join("Lib").join("site-packages")).map_err(|e| e.to_string())?;
    // Visual C++ çalışma kitaplığı (msvcp140): sistemde kurulu olmasa da çalışsın
    if let Ok(ler) = fs::read_dir(kok.join("kurulum").join("vcrt")) {
        for g in ler.flatten() {
            let _ = fs::copy(g.path(), py.join(g.file_name()));
        }
    }
    Ok(())
}

// ---------------------------------------------------------------- python adımları

fn python_komutu(kok: &Path) -> Command {
    let mut k = Command::new(python_yolu(kok));
    k.current_dir(kok).env("PYTHONIOENCODING", "utf-8").env("HF_HUB_OFFLINE", "1");
    #[cfg(windows)]
    k.creation_flags(CREATE_NO_WINDOW);
    k
}

fn donustur(b: &mut Bildirici, kok: &Path, p: &Paket) -> Result<(), String> {
    let mesaj = p.donustur_mesaj;
    b.asama("donustur", INDIRME_PAYI, mesaj);
    let mut cocuk = python_komutu(kok)
        .args(["-u", "-m", "canli_altyazi.donustur", p.ham, p.ct2, "int8"])
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .map_err(|e| format!("Python başlatılamadı: {e}"))?;
    let hata_akisi = cocuk.stderr.take();
    let hata_is = std::thread::spawn(move || {
        let mut s = String::new();
        if let Some(mut h) = hata_akisi {
            let _ = h.read_to_string(&mut s);
        }
        s
    });
    if let Some(cikti) = cocuk.stdout.take() {
        for satir in BufReader::new(cikti).lines().map_while(Result::ok) {
            if let Some(y) = satir.strip_prefix("ILERLEME ").and_then(|s| s.trim().parse::<f64>().ok()) {
                let yuzde = INDIRME_PAYI + (99.0 - INDIRME_PAYI) * y / 100.0;
                let _ = b.app.emit("kurulum", json!({"asama": "donustur", "yuzde": yuzde, "mesaj": mesaj,
                    "indirilen": b.biten, "toplam": b.toplam}));
            }
        }
    }
    let durum = cocuk.wait().map_err(|e| e.to_string())?;
    let hata = hata_is.join().unwrap_or_default();
    if !hata.trim().is_empty() {
        b.gunluk(&format!("dönüştürme çıktısı:\n{hata}"));
    }
    if !durum.success() {
        let son = hata.lines().rev().find(|s| !s.trim().is_empty()).unwrap_or("").to_string();
        return Err(format!("Kurmancî modeli dönüştürülemedi: {son}"));
    }
    let _ = fs::remove_dir_all(kok.join(p.ham));
    Ok(())
}

fn denetle(b: &mut Bildirici, kok: &Path) -> Result<(), String> {
    b.asama("denetle", 99.5, "Son denetim…");
    let cikti = python_komutu(kok)
        // sondaki argüman gerekli: soundcard Linux'ta program adını sys.argv[1]'den alıyor ("-c" ile tek başına çöküyor)
        .args(["-c", "import faster_whisper, ctranslate2, onnxruntime, soundcard, requests, canli_altyazi.motor", "denetim"])
        .output()
        .map_err(|e| format!("Python başlatılamadı: {e}"))?;
    if !cikti.status.success() {
        let hata = String::from_utf8_lossy(&cikti.stderr).to_string();
        b.gunluk(&format!("denetim hatası:\n{hata}"));
        let son = hata.lines().rev().find(|s| !s.trim().is_empty()).unwrap_or("").to_string();
        return Err(format!("Program bileşenleri çalışmadı: {son}"));
    }
    Ok(())
}

// ---------------------------------------------------------------- ana akış

/// Eski sürümlerin (PyTorch'lu) artıkları: yer kaplamasın, yeni Python ile karışmasın.
fn eski_surum_artiklarini_sil(b: &mut Bildirici, kok: &Path) {
    // yalnızca kurulmuş programda (geliştirme klasöründeki .venv'e dokunulmaz)
    if !kok.join("uninstall.exe").is_file() {
        return;
    }
    let eskiler = [
        kok.join(".venv"),
        kok.join("python").join("tools"), // eski NuGet Python düzeni
        kok.join("modeller").join("nllb-1.3B-ct2"),
    ];
    for y in eskiler {
        if y.exists() {
            b.gunluk(&format!("eski sürüm artığı siliniyor: {}", y.display()));
            if y.ends_with("tools") {
                let _ = fs::remove_dir_all(kok.join("python"));
            } else {
                let _ = fs::remove_dir_all(&y);
            }
        }
    }
}

fn kur(app: &AppHandle, kok: &Path, p: &Paket) -> Result<(), String> {
    let temel = std::ptr::eq(p, &TEMEL);
    if !temel && !tamam_mi(kok) {
        return Err("Önce temel kurulum tamamlanmalı.".into());
    }
    let liste = liste(p);
    let nvidia = nvidia_var();
    let gunluk = OpenOptions::new().create(true).append(true).open(kok.join("kurulum_gunluk.txt")).ok();
    let mut b = Bildirici {
        app: app.clone(), gunluk, toplam: 0, biten: 0,
        son_gonderim: Instant::now() - Duration::from_secs(1), olcum: Vec::new(), mesaj: String::new(),
        baslangic: Instant::now(),
    };
    b.gunluk(&format!("kurulum başladı (liste sürümü {}), klasör: {}", liste.surum, kok.display()));
    let _ = fs::remove_file(kok.join(p.tamam));
    if temel {
        eski_surum_artiklarini_sil(&mut b, kok);
    }

    let indirilenler = kok.join("indirilenler");
    fs::create_dir_all(&indirilenler).map_err(|e| format!("klasör oluşturulamadı: {e}"))?;
    let py = kok.join("python");
    let kayit_yolu = py.join("kurulan_paketler.txt");
    // ._pth açma bittikten sonra yazılır: yoksa önceki açma yarıda kalmıştır, Python yeniden kurulur
    let python_var = python_yolu(kok).is_file() && python_isareti(kok).is_file();
    let kurulanlar: Vec<String> = if python_var {
        fs::read_to_string(&kayit_yolu).unwrap_or_default().lines().map(str::to_string).collect()
    } else {
        Vec::new()
    };
    let kurmanci_hazir = kok.join(p.ct2).join("model.bin").is_file();

    // yapılacaklar ve toplam boyut
    let mut yapilacak: Vec<Dosya> = Vec::new();
    for d in &liste.dosyalar {
        let gerekli = match d.tur.as_str() {
            "python" => !python_var,
            "paket" => !kurulanlar.contains(&d.ad),
            "gpu" => nvidia && !kurulanlar.contains(&d.ad),
            "arsiv" => !arsiv_acik(kok, d),
            _ => {
                // hazır (dönüştürülmüş) model indirilir; eski kurulumda dönüştürülmüş olanı varsa yeniden inmez
                if !p.ct2.is_empty() && (d.ad.starts_with(p.ham) || d.ad.starts_with(p.ct2)) && kurmanci_hazir {
                    false
                } else {
                    fs::metadata(kok.join(&d.ad)).map(|m| m.len() != d.boyut).unwrap_or(true)
                }
            }
        };
        if gerekli {
            yapilacak.push(d.clone());
        }
    }
    b.toplam = yapilacak.iter().map(|d| d.boyut).sum();
    b.gunluk(&format!("{} dosya, {} MB indirilecek", yapilacak.len(), b.toplam / 1_000_000));
    // açılan paketler ve geçici dosyalar için pay: indirilenin ~1,3 katı + 300 MB
    if let Some(bos) = bos_alan(kok) {
        let gereken = b.toplam / 10 * 13 + 300_000_000;
        if bos < gereken {
            return Err(format!(
                "Diskte yeterli boş alan yok: {:.1} GB gerekiyor, {:.1} GB boş. Yer açıp yeniden deneyin.",
                gereken as f64 / 1e9, bos as f64 / 1e9
            ));
        }
    }

    for d in &yapilacak {
        b.mesaj = format!("{} indiriliyor", d.aciklama);
        b.indirme_bildir(true);
        match d.tur.as_str() {
            "python" | "paket" | "gpu" => {
                let arsiv = indirilenler.join(&d.ad);
                indir(&mut b, d, &arsiv)?;
                if d.tur == "python" {
                    python_kur(kok, &arsiv)?;
                    let _ = fs::remove_file(&kayit_yolu);
                } else {
                    zip_ac(&arsiv, &site_paketleri(kok), true)
                        .map_err(|e| format!("Paket açılamadı (disk dolu olabilir): {e}"))?;
                    let mut k = OpenOptions::new().create(true).append(true).open(&kayit_yolu)
                        .map_err(|e| e.to_string())?;
                    writeln!(k, "{}", d.ad).map_err(|e| e.to_string())?;
                }
                let _ = fs::remove_file(&arsiv);
            }
            "arsiv" => {
                let arsiv = indirilenler.join(d.url.rsplit('/').next().unwrap_or("arsiv.tar.bz2"));
                indir(&mut b, d, &arsiv)?;
                arsiv_ac(&mut b, kok, &arsiv, d)?;
                let _ = fs::remove_file(&arsiv);
            }
            _ => indir(&mut b, d, &kok.join(&d.ad))?,
        }
        b.gunluk(&format!("tamam: {}", d.ad));
    }
    b.indirme_bildir(true);
    let _ = fs::remove_dir_all(&indirilenler);

    if !p.ct2.is_empty() && !kok.join(p.ct2).join("model.bin").is_file() {
        donustur(&mut b, kok, p)?;
    }
    if temel {
        denetle(&mut b, kok)?;
    }
    fs::write(kok.join(p.tamam), liste.surum.to_string()).map_err(|e| e.to_string())?;
    b.asama("tamam", 100.0, "Kurulum tamamlandı");
    Ok(())
}

/// Temel kurulumu arka planda başlatır (zaten bir kurulum çalışıyorsa bir şey yapmaz).
pub fn baslat(app: AppHandle, kok: PathBuf) {
    arka_planda(app, kok, &TEMEL);
}

/// İsteğe bağlı kaliteli Kurmancî paketini kurar (ilerleme yine "kurulum" olayıyla).
pub fn kaliteli_baslat(app: AppHandle, kok: PathBuf) {
    arka_planda(app, kok, &KALITELI);
}

/// İsteğe bağlı Kürtçe Orta (Omnilingual) paketini kurar.
pub fn omni_baslat(app: AppHandle, kok: PathBuf) {
    arka_planda(app, kok, &OMNI);
}

/// İsteğe bağlı ekran kartı hızlandırma paketini kurar.
pub fn hizlandirma_baslat(app: AppHandle, kok: PathBuf, kmr: bool) {
    arka_planda(app, kok, if kmr { &HIZLANDIRMA_KMR } else { &HIZLANDIRMA });
}

fn arka_planda(app: AppHandle, kok: PathBuf, p: &'static Paket) {
    if CALISIYOR.swap(true, Ordering::SeqCst) {
        return;
    }
    std::thread::spawn(move || {
        if let Err(e) = kur(&app, &kok, p) {
            if let Ok(mut g) = OpenOptions::new().create(true).append(true).open(kok.join("kurulum_gunluk.txt")) {
                let _ = writeln!(g, "HATA: {e}");
            }
            let _ = app.emit("kurulum", json!({"asama": "hata", "mesaj": e}));
        }
        CALISIYOR.store(false, Ordering::SeqCst);
    });
}
