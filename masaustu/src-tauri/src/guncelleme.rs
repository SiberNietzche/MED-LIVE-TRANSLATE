//! GitHub Releases üzerinden güncelleme: denetleme, indirme (SHA256 doğrulamalı) ve kurma.
//!
//! Sürüm dosyaları adlarının sonuna göre seçilir: `_windows.exe`, `_macos.dmg`, `_linux.AppImage`,
//! `_linux.deb` (Android `_android.apk`, uygulamanın kendisinde). Aynı sürümde `SHA256SUMS.txt`
//! bulunmalıdır; indirilen dosyanın özeti buradakiyle uyuşmazsa kurulmaz.
//! GitHub'a yalnızca sıradan bir istek gider (genel program adıyla); kişisel bilgi gönderilmez.

use std::fs::File;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::time::Duration;

use serde_json::{json, Value};
use tauri::{AppHandle, Emitter};

use crate::kurulum::sha256_dosya;

/// Herkese açık dağıtım deposu (sahip/ad).
pub const DEPO: &str = "SiberNietzche/MED-LIVE-TRANSLATE";

fn api_adresi() -> String {
    format!("https://api.github.com/repos/{DEPO}/releases/latest")
}

fn ajan() -> ureq::Agent {
    ureq::AgentBuilder::new()
        .timeout_connect(Duration::from_secs(15))
        .timeout_read(Duration::from_secs(60))
        .user_agent("MED-LIVE-TRANSLATE")
        .build()
}

/// Bu kurulum için uygun dosya adının sonu.
fn dosya_soneki() -> &'static str {
    #[cfg(windows)]
    {
        "_windows.exe"
    }
    #[cfg(target_os = "macos")]
    {
        "_macos.dmg"
    }
    #[cfg(not(any(windows, target_os = "macos")))]
    {
        if std::env::var_os("APPIMAGE").is_some() { "_linux.AppImage" } else { "_linux.deb" }
    }
}

/// "v1.2.3" / "1.2.3-beta" → [1, 2, 3]
fn surum_sayilari(s: &str) -> Vec<u64> {
    s.trim()
        .trim_start_matches(['v', 'V'])
        .split(['-', '+'])
        .next()
        .unwrap_or("")
        .split('.')
        .map(|p| p.parse::<u64>().unwrap_or(0))
        .collect()
}

pub fn daha_yeni(yeni: &str, eski: &str) -> bool {
    let (mut a, mut b) = (surum_sayilari(yeni), surum_sayilari(eski));
    let n = a.len().max(b.len());
    a.resize(n, 0);
    b.resize(n, 0);
    a > b
}

/// En son sürümü sorar. Dönen: {surum, yeni, sayfa, notlar, dosya:{ad,url,boyut}|null, sha_url}
#[tauri::command]
pub async fn guncelleme_denetle(app: AppHandle) -> Result<Value, String> {
    let simdiki = app.package_info().version.to_string();
    tauri::async_runtime::spawn_blocking(move || denetle(&simdiki))
        .await
        .map_err(|e| e.to_string())?
}

fn denetle(simdiki: &str) -> Result<Value, String> {
    let yanit = ajan()
        .get(&api_adresi())
        .set("Accept", "application/vnd.github+json")
        .call()
        .map_err(|e| match e {
            ureq::Error::Status(404, _) => "E:yok".to_string(),
            ureq::Error::Status(403 | 429, _) => "E:sinir".to_string(),
            ureq::Error::Status(k, _) => format!("GitHub yanıtı: {k}"),
            ureq::Error::Transport(_) => "E:baglanti".to_string(),
        })?;
    let metin = yanit.into_string().map_err(|e| e.to_string())?;
    let v: Value = serde_json::from_str(&metin).map_err(|e| e.to_string())?;
    let surum = v["tag_name"].as_str().unwrap_or("").trim_start_matches(['v', 'V']).to_string();
    if surum.is_empty() {
        return Err("Sürüm bilgisi okunamadı.".into());
    }
    let dosyalar = v["assets"].as_array().cloned().unwrap_or_default();
    let bul = |sonek: &str| {
        dosyalar.iter().find(|d| d["name"].as_str().is_some_and(|ad| ad.ends_with(sonek))).cloned()
    };
    let dosya = bul(dosya_soneki()).map(|d| {
        json!({
            "ad": d["name"],
            "url": d["browser_download_url"],
            "boyut": d["size"],
        })
    });
    let sha_url = bul("SHA256SUMS.txt").and_then(|d| d["browser_download_url"].as_str().map(str::to_string));
    Ok(json!({
        "surum": surum,
        "simdiki": simdiki,
        "yeni": daha_yeni(&surum, simdiki),
        "sayfa": v["html_url"],
        "notlar": v["body"].as_str().unwrap_or("").chars().take(4000).collect::<String>(),
        "dosya": dosya,
        "sha_url": sha_url,
    }))
}

/// Yalnızca GitHub adreslerinden (https) indirilir.
fn guvenli_adres(url: &str) -> bool {
    let Ok(u) = url::Url::parse(url) else { return false };
    let host = u.host_str().unwrap_or("");
    u.scheme() == "https" && (host == "github.com" || host.ends_with(".githubusercontent.com"))
}

fn indirme_klasoru() -> PathBuf {
    let k = std::env::temp_dir().join("med-live-translate-guncelleme");
    let _ = std::fs::create_dir_all(&k);
    k
}

/// Sürüm dosyasını indirir, SHA256SUMS.txt ile doğrular ve kurar. İlerleme "guncelleme" olayıyla gelir.
/// Windows / Linux AppImage / macOS'ta kurulumdan sonra program kendini kapatıp yeni sürümle açılır.
#[tauri::command]
pub async fn guncelleme_kur(app: AppHandle, ad: String, url: String, sha_url: String) -> Result<String, String> {
    if !guvenli_adres(&url) || !guvenli_adres(&sha_url) {
        return Err("E:adres".into());
    }
    // dosya adı yalnızca güvenli karakterler (yol içeremez)
    if ad.is_empty() || !ad.chars().all(|c| c.is_ascii_alphanumeric() || "._-".contains(c)) {
        return Err("Geçersiz dosya adı.".into());
    }
    let app2 = app.clone();
    tauri::async_runtime::spawn_blocking(move || {
        let yol = indir_dogrula(&app2, &ad, &url, &sha_url)?;
        let _ = app2.emit("guncelleme", json!({"asama": "kuruluyor"}));
        uygula(&app2, &yol)
    })
    .await
    .map_err(|e| e.to_string())?
}

fn indir_dogrula(app: &AppHandle, ad: &str, url: &str, sha_url: &str) -> Result<PathBuf, String> {
    let ajan = ajan();
    // önce beklenen özet
    let ozetler = ajan.get(sha_url).call().map_err(|e| format!("Özet dosyası indirilemedi: {e}"))?
        .into_string().map_err(|e| e.to_string())?;
    let beklenen = ozetler
        .lines()
        .filter_map(|s| {
            let mut p = s.split_whitespace();
            let ozet = p.next()?;
            let dosya = p.next()?.trim_start_matches('*');
            (dosya == ad).then(|| ozet.to_ascii_lowercase())
        })
        .next()
        .ok_or("E:sha_yok")?;

    let hedef = indirme_klasoru().join(ad);
    let yanit = ajan.get(url).call().map_err(|e| format!("İndirilemedi: {e}"))?;
    let toplam: u64 = yanit.header("content-length").and_then(|s| s.parse().ok()).unwrap_or(0);
    let gecici = hedef.with_extension("indiriliyor");
    let mut cikti = File::create(&gecici).map_err(|e| e.to_string())?;
    let mut okuyucu = yanit.into_reader();
    let mut tampon = vec![0u8; 1 << 16];
    let (mut inen, mut son) = (0u64, std::time::Instant::now());
    loop {
        let n = okuyucu.read(&mut tampon).map_err(|e| format!("İndirme kesildi: {e}"))?;
        if n == 0 {
            break;
        }
        cikti.write_all(&tampon[..n]).map_err(|e| format!("Diske yazılamadı: {e}"))?;
        inen += n as u64;
        if son.elapsed() > Duration::from_millis(200) {
            son = std::time::Instant::now();
            let _ = app.emit("guncelleme", json!({"asama": "indiriliyor", "inen": inen, "toplam": toplam}));
        }
    }
    drop(cikti);
    let _ = app.emit("guncelleme", json!({"asama": "dogrulaniyor", "inen": inen, "toplam": toplam}));
    if sha256_dosya(&gecici)? != beklenen {
        let _ = std::fs::remove_file(&gecici);
        return Err("E:sha".into());
    }
    let _ = std::fs::remove_file(&hedef);
    std::fs::rename(&gecici, &hedef).map_err(|e| e.to_string())?;
    Ok(hedef)
}

/// Motoru kapatıp programdan çıkar (kurucu dosyaları değiştirebilsin).
fn cik(app: &AppHandle) {
    crate::motoru_kapat_genel(app);
    std::thread::sleep(Duration::from_millis(400));
    app.exit(0);
}

#[cfg(windows)]
fn uygula(app: &AppHandle, yol: &Path) -> Result<String, String> {
    use std::os::windows::process::CommandExt;
    const DETACHED_PROCESS: u32 = 0x0000_0008;
    const CREATE_BREAKAWAY_FROM_JOB: u32 = 0x0100_0000;
    // /P: yalnızca ilerleme penceresi, /UPDATE: güncelleme kipi (indirilen bileşenler korunur),
    // /R: kurulum bitince program yeniden açılır. Kurucu bu programın iş nesnesinden ayrılmalı,
    // yoksa program kapanınca Windows onu da kapatır.
    std::process::Command::new(yol)
        .args(["/P", "/UPDATE", "/R"])
        .creation_flags(DETACHED_PROCESS | CREATE_BREAKAWAY_FROM_JOB)
        .spawn()
        .map_err(|e| format!("Kurulum başlatılamadı: {e}"))?;
    cik(app);
    Ok("kuruluyor".into())
}

#[cfg(target_os = "macos")]
fn uygula(app: &AppHandle, yol: &Path) -> Result<String, String> {
    use std::process::Command;
    // çalışan uygulama paketi: .../MED LIVE TRANSLATE.app/Contents/MacOS/<ikili>
    let paket = std::env::current_exe()
        .ok()
        .and_then(|p| p.parent()?.parent()?.parent().map(Path::to_path_buf))
        .filter(|p| p.extension().is_some_and(|e| e == "app"));
    let baglama = indirme_klasoru().join("dmg");
    let _ = Command::new("hdiutil").arg("detach").arg(&baglama).arg("-force").output();
    let _ = std::fs::create_dir_all(&baglama);
    let elle = |neden: String| -> Result<String, String> {
        // kendiliğinden değiştirilemedi: disk görüntüsü açılır, kullanıcı sürükler
        let _ = Command::new("open").arg(yol).spawn();
        Err(format!("{neden}\nDisk görüntüsü açıldı: uygulamayı Uygulamalar klasörüne sürükleyip eskisinin yerine koyun."))
    };
    let Some(paket) = paket else { return elle("Uygulama paketi bulunamadı.".into()) };
    let c = Command::new("hdiutil")
        .args(["attach", "-nobrowse", "-readonly", "-noautoopen", "-mountpoint"])
        .arg(&baglama)
        .arg(yol)
        .output()
        .map_err(|e| e.to_string())?;
    if !c.status.success() {
        return elle("Disk görüntüsü bağlanamadı.".into());
    }
    let kaynak = std::fs::read_dir(&baglama)
        .ok()
        .and_then(|d| d.flatten().map(|e| e.path()).find(|p| p.extension().is_some_and(|e| e == "app")));
    let sonuc = (|| -> Result<(), String> {
        let kaynak = kaynak.ok_or("Disk görüntüsünde uygulama yok.")?;
        let yeni = paket.with_extension("app-yeni");
        let eski = paket.with_extension("app-eski");
        let _ = std::fs::remove_dir_all(&yeni);
        let _ = std::fs::remove_dir_all(&eski);
        let d = Command::new("ditto").arg(&kaynak).arg(&yeni).status().map_err(|e| e.to_string())?;
        if !d.success() {
            let _ = std::fs::remove_dir_all(&yeni);
            return Err("Yeni sürüm kopyalanamadı (klasöre yazma izni yok olabilir).".into());
        }
        std::fs::rename(&paket, &eski).map_err(|e| e.to_string())?;
        if let Err(e) = std::fs::rename(&yeni, &paket) {
            let _ = std::fs::rename(&eski, &paket);
            return Err(e.to_string());
        }
        let _ = std::fs::remove_dir_all(&eski);
        Ok(())
    })();
    let _ = Command::new("hdiutil").arg("detach").arg(&baglama).arg("-force").output();
    if let Err(e) = sonuc {
        return elle(e);
    }
    // program kapandıktan sonra yeni sürüm açılır
    Command::new("/bin/sh")
        .arg("-c")
        .arg("sleep 2; open -n \"$1\"")
        .arg("sh")
        .arg(&paket)
        .spawn()
        .map_err(|e| e.to_string())?;
    cik(app);
    Ok("kuruluyor".into())
}

#[cfg(not(any(windows, target_os = "macos")))]
fn uygula(app: &AppHandle, yol: &Path) -> Result<String, String> {
    use std::os::unix::fs::PermissionsExt;
    use std::process::Command;
    if let Some(eski) = std::env::var_os("APPIMAGE").map(PathBuf::from) {
        // AppImage: aynı klasöre yazılır, eskisinin yerine konur, yeni sürüm açılır
        let yeni = eski.with_extension("yeni");
        std::fs::copy(yol, &yeni).map_err(|e| format!("Yeni sürüm yazılamadı: {e}"))?;
        std::fs::set_permissions(&yeni, std::fs::Permissions::from_mode(0o755)).map_err(|e| e.to_string())?;
        std::fs::rename(&yeni, &eski).map_err(|e| e.to_string())?;
        let _ = std::fs::remove_file(yol);
        Command::new("/bin/sh")
            .arg("-c")
            .arg("sleep 2; \"$1\" >/dev/null 2>&1 &")
            .arg("sh")
            .arg(&eski)
            .spawn()
            .map_err(|e| e.to_string())?;
        cik(app);
        return Ok("kuruluyor".into());
    }
    // .deb: paket yükleyicisiyle açılır (yönetici parolası orada istenir)
    Command::new("xdg-open").arg(yol).spawn().map_err(|e| format!("Paket açılamadı: {e}"))?;
    Ok(format!("deb:{}", yol.display()))
}

/// Masaüstü bildirimi (yeni sürüm vb.).
#[tauri::command]
pub fn bildirim_gonder(app: AppHandle, baslik: String, metin: String) -> Result<(), String> {
    use tauri_plugin_notification::NotificationExt;
    app.notification().builder().title(baslik).body(metin).show().map_err(|e| e.to_string())
}

#[cfg(test)]
mod testler {
    use super::daha_yeni;
    #[test]
    fn surum_karsilastirma() {
        assert!(daha_yeni("1.0.1", "1.0.0"));
        assert!(daha_yeni("v1.1.0", "1.0.9"));
        assert!(daha_yeni("2.0", "1.9.9"));
        assert!(!daha_yeni("1.0.0", "1.0.0"));
        assert!(!daha_yeni("1.0.0", "1.0.1"));
        assert!(!daha_yeni("v1.0.0-beta", "1.0.0"));
    }
}
