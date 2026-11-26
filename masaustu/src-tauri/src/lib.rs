//! MED LIVE TRANSLATE — Tauri ön yüzü ile Python motoru arasındaki köprü.
//!
//! Python motoru (`python -m canli_altyazi.sunucu`) alt süreç olarak başlatılır.
//! Python ve modeller ilk açılışta `kurulum` modülüyle (ilerleme yüzdesiyle) indirilir.
//! Motorun stdout'undaki her JSON satırı tüm pencerelere "motor" olayı olarak yayınlanır;
//! ön yüzden gelen komutlar (`motor_komut`) motorun stdin'ine yazılır.

use std::io::{BufRead, BufReader, Write};
use std::path::{Path, PathBuf};
use std::process::{Child, ChildStdin, Command, Stdio};
use std::sync::Mutex;

use serde_json::Value;
use tauri::{AppHandle, Emitter, Manager, RunEvent, State};

mod guncelleme;
mod kurulum;

#[cfg(windows)]
use std::os::windows::process::CommandExt;
#[cfg(windows)]
const CREATE_NO_WINDOW: u32 = 0x0800_0000;

/// Windows iş nesnesi (Job Object): içine konan süreçler, nesnenin son tutamacı kapanınca
/// — yani bu program hangi yolla kapanırsa kapansın — Windows tarafından sonlandırılır.
/// Alt süreçler (venv'in başlattığı asıl python.exe) de işi devralır.
#[cfg(windows)]
mod is_nesnesi {
    use windows_sys::Win32::Foundation::HANDLE;
    use windows_sys::Win32::System::Threading::GetCurrentProcess;
    use windows_sys::Win32::System::JobObjects::{
        AssignProcessToJobObject, CreateJobObjectW, JobObjectExtendedLimitInformation,
        SetInformationJobObject, JOBOBJECT_EXTENDED_LIMIT_INFORMATION,
        JOB_OBJECT_LIMIT_BREAKAWAY_OK, JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE,
    };

    pub struct IsNesnesi(HANDLE);
    // Tutamaç yalnızca süreç eklemek için kullanılır; iş parçacıkları arasında güvenle paylaşılır.
    unsafe impl Send for IsNesnesi {}
    unsafe impl Sync for IsNesnesi {}

    impl IsNesnesi {
        pub fn yeni() -> Option<Self> {
            unsafe {
                let h = CreateJobObjectW(std::ptr::null(), std::ptr::null());
                if h.is_null() {
                    return None;
                }
                let mut bilgi: JOBOBJECT_EXTENDED_LIMIT_INFORMATION = std::mem::zeroed();
                // BREAKAWAY_OK: güncelleme kurucusu bilerek işten ayrılarak başlatılır (program kapanınca o sürmeli)
                bilgi.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE | JOB_OBJECT_LIMIT_BREAKAWAY_OK;
                let tamam = SetInformationJobObject(
                    h,
                    JobObjectExtendedLimitInformation,
                    &bilgi as *const _ as *const core::ffi::c_void,
                    std::mem::size_of::<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>() as u32,
                );
                if tamam == 0 { None } else { Some(Self(h)) }
            }
        }

        /// Bu programın kendisini işe koyar: sonradan başlatılan bütün süreçler (motor ve onun
        /// başlattığı asıl python.exe) işi kendiliğinden devralır — başlatma anında yarış olmaz.
        pub fn kendini_ekle(&self) -> bool {
            unsafe { AssignProcessToJobObject(self.0, GetCurrentProcess()) != 0 }
        }
    }

    /// Program açıkken hiç kapatılmaz; program sonlanınca tutamacı Windows kapatır ve işteki
    /// süreçleri (motoru) sonlandırır.
    static IS: std::sync::OnceLock<Option<IsNesnesi>> = std::sync::OnceLock::new();

    pub fn kur() {
        IS.get_or_init(|| {
            let is = IsNesnesi::yeni()?;
            if !is.kendini_ekle() {
                eprintln!("Uyarı: iş nesnesi kurulamadı; program kapanınca motor açık kalabilir.");
            }
            Some(is)
        });
    }
}

#[derive(Default)]
struct Motor {
    surec: Mutex<Option<Child>>,
    girdi: Mutex<Option<ChildStdin>>,
    kok: Mutex<Option<PathBuf>>,
}

/// Motor dosyaları exe'nin içine de gömülüdür. Kurulumda bir dosya yazılamadıysa (antivirüs
/// taraması dosyayı kilitledi, kullanıcı "Yoksay"a bastı vb.) ya da sonradan silindiyse,
/// program her açılışta eksik/eski dosyayı yeniden yazar. Yalnızca kurulu sürümde çalışır
/// (exe'nin yanında NSIS'in uninstall.exe'si varsa); geliştirme klasörüne dokunmaz.
const GOMULU_DOSYALAR: &[(&str, &str)] = &[
    ("__init__.py", include_str!("../../../canli_altyazi/__init__.py")),
    ("bolumleyici.py", include_str!("../../../canli_altyazi/bolumleyici.py")),
    ("ceviri.py", include_str!("../../../canli_altyazi/ceviri.py")),
    ("diller.py", include_str!("../../../canli_altyazi/diller.py")),
    ("donustur.py", include_str!("../../../canli_altyazi/donustur.py")),
    ("ekran_karti.py", include_str!("../../../canli_altyazi/ekran_karti.py")),
    ("kmr_sozluk.txt", include_str!("../../../canli_altyazi/kmr_sozluk.txt")),
    ("kurmanci.py", include_str!("../../../canli_altyazi/kurmanci.py")),
    ("motor.py", include_str!("../../../canli_altyazi/motor.py")),
    ("ses.py", include_str!("../../../canli_altyazi/ses.py")),
    ("sunucu.py", include_str!("../../../canli_altyazi/sunucu.py")),
    ("tanima.py", include_str!("../../../canli_altyazi/tanima.py")),
];

/// Kurulu programın çalışma klasörü (Python, modeller ve motor dosyaları burada).
/// Windows: exe'nin klasörü (NSIS kullanıcı klasörüne kurar; yanında uninstall.exe olur).
/// Linux: program salt okunur bir yere kurulur (/usr/bin, AppImage); çalışma klasörü kullanıcının
/// veri klasörüdür: $XDG_DATA_HOME/med-live-translate (yoksa ~/.local/share/med-live-translate).
/// macOS: ~/Library/Application Support/med-live-translate (uygulama paketi imzalı, içine yazılmaz).
fn kurulu_kok() -> Option<PathBuf> {
    #[cfg(windows)]
    {
        let dizin = std::env::current_exe().ok()?.parent()?.to_path_buf();
        dizin.join("uninstall.exe").is_file().then_some(dizin)
    }
    #[cfg(target_os = "macos")]
    {
        let ev = std::env::var_os("HOME").map(PathBuf::from)?;
        Some(ev.join("Library").join("Application Support").join("med-live-translate"))
    }
    #[cfg(not(any(windows, target_os = "macos")))]
    {
        let veri = std::env::var_os("XDG_DATA_HOME")
            .map(PathBuf::from)
            .filter(|p| p.is_absolute())
            .or_else(|| std::env::var_os("HOME").map(|h| PathBuf::from(h).join(".local").join("share")))?;
        Some(veri.join("med-live-translate"))
    }
}

/// Linux / macOS: programla gelen yardımcı programlar (ekran kartı sunucusu whisper-server; macOS'ta
/// ayrıca ses yakalayıcı) kaynaklardan çalışma klasörüne kopyalanır — motor onları kendi klasöründe arar.
/// Boyutu aynıysa dokunulmaz.
#[cfg(not(windows))]
fn yardimcilari_yerlestir(app: &AppHandle) {
    use std::os::unix::fs::PermissionsExt;
    let (Some(kok), Ok(kaynaklar)) = (kurulu_kok(), app.path().resource_dir()) else { return };
    let mut liste = vec![("whisper", "whisper-server")];
    if cfg!(target_os = "macos") {
        liste.push(("macos", "ses_yakala"));
    }
    for (klasor, ad) in liste {
        let kaynak = kaynaklar.join(klasor).join(ad);
        let hedef = kok.join(klasor).join(ad);
        let Ok(k) = std::fs::metadata(&kaynak) else { continue };
        if std::fs::metadata(&hedef).map(|h| h.len() == k.len()).unwrap_or(false) {
            continue;
        }
        let _ = std::fs::create_dir_all(kok.join(klasor));
        let gecici = hedef.with_extension("yeni");
        if std::fs::copy(&kaynak, &gecici).is_ok() {
            let _ = std::fs::set_permissions(&gecici, std::fs::Permissions::from_mode(0o755));
            let _ = std::fs::rename(&gecici, &hedef);
        }
    }
}

/// Ana pencere ekrandan büyükse (küçük dizüstü ekranı, büyük ölçekleme) ekrana sığacak kadar küçültülüp
/// ortalanır; yoksa pencerenin bir kısmı ekran dışında kalıyordu.
fn ekrana_sigdir(app: &AppHandle) {
    let Some(p) = app.get_webview_window("main") else { return };
    let (Ok(Some(ekran)), Ok(boyut)) = (p.current_monitor(), p.outer_size()) else { return };
    let olcek = ekran.scale_factor();
    let alan = ekran.size().to_logical::<f64>(olcek);
    let simdi = boyut.to_logical::<f64>(olcek);
    // üstteki menü çubuğu / alttaki görev çubuğu ve Dock için pay
    let (en_fazla_g, en_fazla_y) = (alan.width - 40.0, alan.height - 110.0);
    if simdi.width <= en_fazla_g && simdi.height <= en_fazla_y {
        return;
    }
    let (g, y) = (simdi.width.min(en_fazla_g).max(860.0), simdi.height.min(en_fazla_y).max(620.0));
    let _ = p.set_size(tauri::LogicalSize::new(g, y));
    // center() eski boyutla hesaplıyordu (pencere sola taşıyordu): konum yeni boyuta göre elle verilir
    let koken = ekran.position().to_logical::<f64>(olcek);
    let _ = p.set_position(tauri::LogicalPosition::new(
        koken.x + ((alan.width - g) / 2.0).max(0.0),
        koken.y + ((alan.height - y) / 2.0).max(0.0),
    ));
}

/// Yazılamayan dosyaların adlarını döndürür (boşsa her şey yerinde).
fn motor_dosyalarini_onar() -> Vec<&'static str> {
    let Some(dizin) = kurulu_kok() else {
        return Vec::new();
    };
    let hedef = dizin.join("canli_altyazi");
    let _ = std::fs::create_dir_all(&hedef);
    let mut yazilamayan = Vec::new();
    for (ad, icerik) in GOMULU_DOSYALAR {
        let yol = hedef.join(ad);
        if std::fs::read(&yol).map(|v| v == icerik.as_bytes()).unwrap_or(false) {
            continue;
        }
        // antivirüs taraması dosyayı birkaç saniye kilitleyebilir: kısa aralıklarla yeniden dene
        let mut tamam = false;
        for _ in 0..5 {
            if std::fs::write(&yol, icerik).is_ok() {
                tamam = true;
                break;
            }
            std::thread::sleep(std::time::Duration::from_millis(800));
        }
        if !tamam {
            yazilamayan.push(*ad);
        }
    }
    yazilamayan
}

static ONARIM_HATASI: Mutex<Vec<&'static str>> = Mutex::new(Vec::new());

fn bulunamadi_mesaji() -> String {
    let eksik = ONARIM_HATASI.lock().unwrap();
    if eksik.is_empty() {
        "Program dosyaları (canli_altyazi) bulunamadı. Programı yeniden kurun.".into()
    } else {
        format!(
            "Program dosyaları yazılamadı ({}). Antivirüs programınız bu dosyaları engellemiş olabilir:              antivirüsün karantina / koruma geçmişinden geri yükleyip programı yeniden açın.",
            eksik.join(", ")
        )
    }
}

/// Program klasörü: içinde `canli_altyazi/` bulunan dizin (Python ve modeller de buraya kurulur).
fn motor_klasoru_gecerli(p: &Path) -> bool {
    p.join("canli_altyazi").join("sunucu.py").is_file()
}

fn motor_klasorunu_bul(app: &AppHandle) -> Option<PathBuf> {
    // 1) ortam değişkeni
    if let Ok(y) = std::env::var("CANLI_ALTYAZI_MOTOR") {
        let p = PathBuf::from(y);
        if motor_klasoru_gecerli(&p) {
            return Some(p);
        }
    }
    // 2) kayıtlı ayar (uygulama yapılandırma klasöründe motor_yolu.txt)
    if let Ok(dir) = app.path().app_config_dir() {
        if let Ok(y) = std::fs::read_to_string(dir.join("motor_yolu.txt")) {
            let p = PathBuf::from(y.trim());
            if motor_klasoru_gecerli(&p) {
                return Some(p);
            }
        }
    }
    // 3) kurulu programın çalışma klasörü (Linux'ta kullanıcının veri klasörü)
    if let Some(k) = kurulu_kok().filter(|k| motor_klasoru_gecerli(k)) {
        return Some(k);
    }
    // 4) exe'nin ve çalışma klasörünün üst dizinleri (geliştirme)
    let mut adaylar: Vec<PathBuf> = Vec::new();
    if let Ok(exe) = std::env::current_exe() {
        adaylar.extend(exe.ancestors().map(Path::to_path_buf));
    }
    if let Ok(cwd) = std::env::current_dir() {
        adaylar.extend(cwd.ancestors().map(Path::to_path_buf));
    }
    adaylar.into_iter().find(|p| motor_klasoru_gecerli(p))
}

fn yayinla(app: &AppHandle, olay: Value) {
    let _ = app.emit("motor", olay);
}

fn motoru_baslat(app: &AppHandle, motor: &Motor) -> Result<PathBuf, String> {
    if let Some(cocuk) = motor.surec.lock().unwrap().as_mut() {
        if cocuk.try_wait().map(|d| d.is_none()).unwrap_or(false) {
            return Ok(motor.kok.lock().unwrap().clone().unwrap_or_default());
        }
    }
    let kok = motor_klasorunu_bul(app)
        .ok_or_else(bulunamadi_mesaji)?;
    if !kurulum::tamam_mi(&kok) {
        return Err("Bileşen kurulumu tamamlanmadı.".into());
    }
    let python = kurulum::python_yolu(&kok);
    // günlük her açılışta büyümeye devam etmesin: 5 MB'ı geçtiyse baştan başla
    let gunluk_yolu = kok.join("motor_gunluk.txt");
    if std::fs::metadata(&gunluk_yolu).map(|m| m.len() > 5_000_000).unwrap_or(false) {
        let _ = std::fs::remove_file(&gunluk_yolu);
    }
    let gunluk = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(&gunluk_yolu)
        .map_err(|e| e.to_string())?;

    let mut komut = Command::new(&python);
    komut
        .args(["-u", "-m", "canli_altyazi.sunucu"])
        .current_dir(&kok)
        .env("PYTHONIOENCODING", "utf-8")
        .env("HF_HUB_OFFLINE", "1")
        .env("HF_HUB_DISABLE_TELEMETRY", "1")
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::from(gunluk));
    #[cfg(windows)]
    komut.creation_flags(CREATE_NO_WINDOW);

    let mut cocuk = komut
        .spawn()
        .map_err(|e| format!("Motor başlatılamadı ({}): {e}", python.display()))?;
    let cikti = cocuk.stdout.take().ok_or("stdout alınamadı")?;
    *motor.girdi.lock().unwrap() = cocuk.stdin.take();
    *motor.surec.lock().unwrap() = Some(cocuk);
    *motor.kok.lock().unwrap() = Some(kok.clone());

    let app2 = app.clone();
    std::thread::spawn(move || {
        for satir in BufReader::new(cikti).lines() {
            let Ok(satir) = satir else { break };
            if let Ok(olay) = serde_json::from_str::<Value>(&satir) {
                yayinla(&app2, olay);
            }
        }
        yayinla(
            &app2,
            serde_json::json!({"tur": "motor_kapandi",
                "metin": "Motor beklenmedik şekilde kapandı. Ayrıntı: motor_gunluk.txt"}),
        );
    });
    Ok(kok)
}

/// Bileşenler kurulu mu? {"tamam": bool, "kok": klasör}
/// Ayarlar ayrıca bir dosyada tutulur: WebView'in yerel deposu program kapanırken diske yazılmadan
/// kalabiliyordu (değişiklikten hemen sonra kapatılınca dil / tema seçimi geri dönüyordu).
fn ayar_dosyasi(app: &AppHandle) -> Option<PathBuf> {
    app.path().app_config_dir().ok().map(|d| d.join("ayarlar.json"))
}

#[tauri::command]
fn ayar_oku(app: AppHandle) -> Option<String> {
    std::fs::read_to_string(ayar_dosyasi(&app)?).ok().filter(|s| s.trim_start().starts_with('{'))
}

#[tauri::command]
fn ayar_yaz(app: AppHandle, icerik: String) -> Result<(), String> {
    let yol = ayar_dosyasi(&app).ok_or("ayar klasörü bulunamadı")?;
    if let Some(ust) = yol.parent() {
        std::fs::create_dir_all(ust).map_err(|e| e.to_string())?;
    }
    // önce geçici dosya, sonra yeniden adlandırma: yazarken kapanırsa eski ayarlar bozulmaz
    let gecici = yol.with_extension("json.tmp");
    std::fs::write(&gecici, icerik).map_err(|e| e.to_string())?;
    std::fs::rename(&gecici, &yol).map_err(|e| e.to_string())
}

/// Konuşma geçmişini kullanıcının seçtiği yere kaydeder ("Farklı kaydet" penceresi).
/// Vazgeçilirse None, kaydedilirse dosya adı döner.
#[tauri::command]
async fn gecmis_kaydet(
    app: AppHandle, metin: String, ad: String, baslik: Option<String>, filtre: Option<String>,
) -> Result<Option<String>, String> {
    use tauri_plugin_dialog::DialogExt;
    // pencere başlığı ve dosya türü adı program dilinde gelir (gelmezse Türkçe)
    let baslik = baslik.unwrap_or_else(|| "Konuşma geçmişini kaydet".into());
    let filtre = filtre.unwrap_or_else(|| "Metin dosyası".into());
    let mut pencere = app.dialog().file().set_title(&baslik).set_file_name(&ad).add_filter(&filtre, &["txt"]);
    if let Ok(d) = app.path().download_dir() {
        pencere = pencere.set_directory(d);
    }
    let Some(secilen) = pencere.blocking_save_file() else {
        return Ok(None);
    };
    let yol = secilen.into_path().map_err(|e| e.to_string())?;
    // BOM: eski Not Defteri / Excel de Türkçe ve Kürtçe harfleri doğru göstersin
    let mut icerik = String::from("\u{feff}");
    icerik.push_str(&metin.replace("\r\n", "\n").replace('\n', "\r\n"));
    std::fs::write(&yol, icerik).map_err(|e| format!("Kaydedilemedi: {e}"))?;
    Ok(yol.file_name().map(|a| a.to_string_lossy().into_owned()))
}

// ---------- kalıcı konuşma geçmişi ----------
// Her Başlat → Durdur oturumu %APPDATA%\com.canlialtyazi.app\gecmis\<oturum>.jsonl dosyasıdır; her satır bir
// cümle: {"z": ms, "dil", "hedef", "o": orijinal, "c": çeviri, "rtl", "hrtl"}. Yalnızca bu bilgisayarda durur.

fn gecmis_klasoru(app: &AppHandle) -> Result<PathBuf, String> {
    app.path().app_config_dir().map(|d| d.join("gecmis")).map_err(|e| e.to_string())
}

/// Oturum adı arayüzden gelir: klasör dışına çıkılamasın diye yalnızca harf, rakam, - ve _ kabul edilir.
fn oturum_yolu(app: &AppHandle, oturum: &str) -> Result<PathBuf, String> {
    let gecerli = !oturum.is_empty() && oturum.len() <= 64
        && oturum.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_');
    if !gecerli {
        return Err("geçersiz oturum adı".into());
    }
    Ok(gecmis_klasoru(app)?.join(format!("{oturum}.jsonl")))
}

#[tauri::command]
fn gecmis_ekle(app: AppHandle, oturum: String, satir: String) -> Result<(), String> {
    let yol = oturum_yolu(&app, &oturum)?;
    if let Some(ust) = yol.parent() {
        std::fs::create_dir_all(ust).map_err(|e| e.to_string())?;
    }
    let mut f = std::fs::OpenOptions::new().create(true).append(true).open(&yol).map_err(|e| e.to_string())?;
    // tek satır: cümledeki satır sonları JSON içinde zaten kaçışlı gelir
    writeln!(f, "{}", satir.replace(['\r', '\n'], " ")).map_err(|e| e.to_string())
}

/// Kayıtlı oturumların özeti, en yenisi başta: [{oturum, adet, ilk, son, ciftler: ["tr>kmr", ...]}]
#[tauri::command]
fn gecmis_liste(app: AppHandle) -> Result<Vec<Value>, String> {
    let Ok(okuyucu) = std::fs::read_dir(gecmis_klasoru(&app)?) else {
        return Ok(Vec::new());
    };
    let mut liste = Vec::new();
    for g in okuyucu.flatten() {
        let yol = g.path();
        if yol.extension().and_then(|e| e.to_str()) != Some("jsonl") {
            continue;
        }
        let Some(oturum) = yol.file_stem().and_then(|s| s.to_str()).map(String::from) else { continue };
        let Ok(icerik) = std::fs::read_to_string(&yol) else { continue };
        let (mut adet, mut ilk, mut son) = (0u64, None::<i64>, None::<i64>);
        let mut ciftler: Vec<String> = Vec::new();
        for s in icerik.lines() {
            let Ok(v) = serde_json::from_str::<Value>(s) else { continue };
            adet += 1;
            if let Some(z) = v.get("z").and_then(Value::as_i64) {
                ilk = Some(ilk.map_or(z, |i| i.min(z)));
                son = Some(son.map_or(z, |x| x.max(z)));
            }
            let cift = format!("{}>{}", v.get("dil").and_then(Value::as_str).unwrap_or(""),
                               v.get("hedef").and_then(Value::as_str).unwrap_or(""));
            if !ciftler.contains(&cift) {
                ciftler.push(cift);
            }
        }
        if adet == 0 {
            continue;
        }
        liste.push(serde_json::json!({"oturum": oturum, "adet": adet, "ilk": ilk, "son": son, "ciftler": ciftler}));
    }
    liste.sort_by(|a, b| b["oturum"].as_str().cmp(&a["oturum"].as_str()));
    Ok(liste)
}

#[tauri::command]
fn gecmis_oku(app: AppHandle, oturum: String) -> Result<String, String> {
    std::fs::read_to_string(oturum_yolu(&app, &oturum)?).map_err(|e| e.to_string())
}

/// Bir oturumu (oturum verilirse) ya da bütün geçmişi siler; silinen dosya sayısını döndürür.
#[tauri::command]
fn gecmis_sil(app: AppHandle, oturum: Option<String>) -> Result<u32, String> {
    if let Some(o) = oturum {
        std::fs::remove_file(oturum_yolu(&app, &o)?).map_err(|e| e.to_string())?;
        return Ok(1);
    }
    let Ok(okuyucu) = std::fs::read_dir(gecmis_klasoru(&app)?) else {
        return Ok(0);
    };
    let mut adet = 0;
    let mut hata = None;
    for g in okuyucu.flatten() {
        let yol = g.path();
        if yol.extension().and_then(|e| e.to_str()) == Some("jsonl") {
            match std::fs::remove_file(&yol) {
                Ok(()) => adet += 1,
                Err(e) => hata = Some(e.to_string()),
            }
        }
    }
    match hata {
        Some(e) => Err(format!("Bazı kayıtlar silinemedi: {e}")),
        None => Ok(adet),
    }
}

#[tauri::command]
fn bilesen_durumu(app: AppHandle) -> Value {
    match motor_klasorunu_bul(&app) {
        Some(k) => serde_json::json!({"tamam": kurulum::tamam_mi(&k), "kok": k.display().to_string()}),
        None => serde_json::json!({"tamam": false, "kok": null}),
    }
}

/// Bileşen kurulumunu arka planda başlatır; ilerleme "kurulum" olayıyla gelir.
#[tauri::command]
fn bilesen_kur(app: AppHandle) -> Result<(), String> {
    let kok = motor_klasorunu_bul(&app)
        .ok_or_else(bulunamadi_mesaji)?;
    kurulum::baslat(app, kok);
    Ok(())
}

/// İsteğe bağlı kaliteli Kurmancî paketi: boyut bilgisi.
#[tauri::command]
fn kaliteli_bilgi(app: AppHandle) -> Value {
    match motor_klasorunu_bul(&app) {
        Some(k) => kurulum::kaliteli_bilgi(&k),
        None => serde_json::json!({"indirme_mb": 0, "nvidia": false}),
    }
}

/// İsteğe bağlı deneysel Kurmancî (Omnilingual) paketi: boyut bilgisi.
#[tauri::command]
fn omni_bilgi(app: AppHandle) -> Value {
    match motor_klasorunu_bul(&app) {
        Some(k) => kurulum::omni_bilgi(&k),
        None => serde_json::json!({"indirme_mb": 0, "destek": false}),
    }
}

/// İsteğe bağlı deneysel Kurmancî paketini arka planda kurar.
#[tauri::command]
fn omni_kur(app: AppHandle) -> Result<(), String> {
    let kok = motor_klasorunu_bul(&app)
        .ok_or_else(bulunamadi_mesaji)?;
    kurulum::omni_baslat(app, kok);
    Ok(())
}

/// İsteğe bağlı ekran kartı hızlandırma paketi: boyut bilgisi.
#[tauri::command]
fn hizlandirma_bilgi(app: AppHandle, kmr: Option<bool>) -> Value {
    match motor_klasorunu_bul(&app) {
        Some(k) => kurulum::hizlandirma_bilgi(&k, kmr.unwrap_or(false)),
        None => serde_json::json!({"indirme_mb": 0}),
    }
}

/// İsteğe bağlı ekran kartı hızlandırma paketini arka planda kurar.
#[tauri::command]
fn hizlandirma_kur(app: AppHandle, kmr: Option<bool>) -> Result<(), String> {
    let kok = motor_klasorunu_bul(&app)
        .ok_or_else(bulunamadi_mesaji)?;
    kurulum::hizlandirma_baslat(app, kok, kmr.unwrap_or(false));
    Ok(())
}

/// İsteğe bağlı kaliteli Kurmancî paketini arka planda kurar.
#[tauri::command]
fn kaliteli_kur(app: AppHandle) -> Result<(), String> {
    let kok = motor_klasorunu_bul(&app)
        .ok_or_else(bulunamadi_mesaji)?;
    kurulum::kaliteli_baslat(app, kok);
    Ok(())
}

/// Motoru (gerekirse) başlatır ve motor klasörünü döndürür.
#[tauri::command]
fn motor_hazirla(app: AppHandle, motor: State<'_, Motor>) -> Result<String, String> {
    motoru_baslat(&app, &motor).map(|p| p.display().to_string())
}

/// Ön yüzden gelen JSON komutunu motorun stdin'ine yazar.
#[tauri::command]
fn motor_komut(app: AppHandle, motor: State<'_, Motor>, komut: Value) -> Result<(), String> {
    motoru_baslat(&app, &motor)?;
    let mut girdi = motor.girdi.lock().unwrap();
    let akis = girdi.as_mut().ok_or("Motor girdisi yok")?;
    writeln!(akis, "{}", komut).map_err(|e| e.to_string())?;
    akis.flush().map_err(|e| e.to_string())
}

/// Motor klasörünü elle ayarlar (exe başka bir yere taşındıysa).
#[tauri::command]
fn motor_yolu_ayarla(app: AppHandle, yol: String) -> Result<(), String> {
    let p = PathBuf::from(&yol);
    if !motor_klasoru_gecerli(&p) {
        return Err("Bu klasörde canli_altyazi/ bulunamadı.".into());
    }
    let dir = app.path().app_config_dir().map_err(|e| e.to_string())?;
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    std::fs::write(dir.join("motor_yolu.txt"), yol).map_err(|e| e.to_string())
}

/// Güncelleme kurulmadan önce motoru kapatır.
pub(crate) fn motoru_kapat_genel(app: &AppHandle) {
    motoru_kapat(&app.state::<Motor>());
}

fn motoru_kapat(motor: &Motor) {
    if let Some(mut g) = motor.girdi.lock().unwrap().take() {
        let _ = writeln!(g, "{{\"komut\":\"cikis\"}}");
    }
    if let Some(mut c) = motor.surec.lock().unwrap().take() {
        std::thread::sleep(std::time::Duration::from_millis(300));
        let _ = c.kill();
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
/// Program zaten açıksa (kullanıcı simgeye iki kez bastı) ikinci kopya açık pencereyi öne getirip
/// kapanır. İki kopya aynı anda bileşen indirip aynı dosyalara yazıyor, iki motor çalıştırıyordu.
#[cfg(windows)]
fn zaten_acik_mi() -> bool {
    use windows_sys::Win32::Foundation::{GetLastError, ERROR_ALREADY_EXISTS};
    use windows_sys::Win32::System::Threading::CreateMutexW;
    use windows_sys::Win32::UI::WindowsAndMessaging::{FindWindowW, SetForegroundWindow, ShowWindow, SW_RESTORE};
    let ad: Vec<u16> = "Local\\MEDLIVETRANSLATE-tek-kopya\0".encode_utf16().collect();
    // tutamaç bilerek kapatılmaz: süreç yaşadıkça kilit sürer
    let tutamac = unsafe { CreateMutexW(std::ptr::null(), 0, ad.as_ptr()) };
    if tutamac.is_null() || unsafe { GetLastError() } != ERROR_ALREADY_EXISTS {
        return false;
    }
    let baslik: Vec<u16> = "MED LIVE TRANSLATE\0".encode_utf16().collect();
    let pencere = unsafe { FindWindowW(std::ptr::null(), baslik.as_ptr()) };
    if !pencere.is_null() {
        unsafe {
            ShowWindow(pencere, SW_RESTORE);
            SetForegroundWindow(pencere);
        }
    }
    true
}

pub fn run() {
    #[cfg(windows)]
    if zaten_acik_mi() {
        return;
    }
    #[cfg(windows)]
    is_nesnesi::kur(); // motor başlatılmadan önce
    *ONARIM_HATASI.lock().unwrap() = motor_dosyalarini_onar();
    let kurucu = tauri::Builder::default();
    // Linux/macOS: ikinci kopya açılmaz, var olan pencere öne gelir (Windows'ta bunu adlı kilit yapar)
    #[cfg(not(windows))]
    let kurucu = kurucu.plugin(tauri_plugin_single_instance::init(|app, _, _| {
        if let Some(p) = app.get_webview_window("main") {
            let _ = p.unminimize();
            let _ = p.show();
            let _ = p.set_focus();
        }
    }));
    let uygulama = kurucu
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_notification::init())
        .plugin(tauri_plugin_global_shortcut::Builder::new().build())
        // konum ve boyut hatırlanır, görünürlük hatırlanmaz: altyazı şeridi ve kontrol çubuğu her açılışta
        // gizli başlar (macOS'ta eklenti gizli pencereleri açılışta gösteriyordu)
        .plugin(
            tauri_plugin_window_state::Builder::default()
                .with_state_flags(
                    tauri_plugin_window_state::StateFlags::all() & !tauri_plugin_window_state::StateFlags::VISIBLE,
                )
                .build(),
        )
        .manage(Motor::default())
        .setup(|app| {
            #[cfg(not(windows))]
            yardimcilari_yerlestir(app.handle());
            ekrana_sigdir(app.handle());
            Ok(())
        })
        .on_window_event(|pencere, olay| {
            // ana pencere kapanınca altyazı şeridi dahil tüm uygulama kapanır
            if pencere.label() == "main" {
                if let tauri::WindowEvent::CloseRequested { .. } = olay {
                    motoru_kapat(&pencere.app_handle().state::<Motor>());
                    pencere.app_handle().exit(0);
                }
            }
        })
        .invoke_handler(tauri::generate_handler![
            motor_hazirla, motor_komut, motor_yolu_ayarla, bilesen_durumu, bilesen_kur, kaliteli_bilgi, kaliteli_kur, omni_bilgi, omni_kur, hizlandirma_bilgi, hizlandirma_kur, gecmis_kaydet, gecmis_ekle, gecmis_liste, gecmis_oku, gecmis_sil, ayar_oku, ayar_yaz,
            guncelleme::guncelleme_denetle, guncelleme::guncelleme_kur, guncelleme::bildirim_gonder
        ])
        .build(tauri::generate_context!())
        .expect("uygulama oluşturulamadı");

    uygulama.run(|app, olay| {
        if let RunEvent::ExitRequested { .. } | RunEvent::Exit = olay {
            motoru_kapat(&app.state::<Motor>());
        }
    });
}
