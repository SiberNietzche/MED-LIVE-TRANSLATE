// Hakkında ekranı (sürüm, geliştirici, sosyal medya bağlantıları) ve GitHub üzerinden güncelleme.
// Denetim: açılıştan 20 sn sonra ve program açık kaldıkça 6 saatte bir (Ayarlar → kapatılabilir).
// Yeni sürüm bulununca: üstte şerit + "Hakkında" düğmesinde nokta + (her sürüm için bir kez) masaüstü bildirimi.
// İndirilen dosya SHA256SUMS.txt ile doğrulanmadan kurulmaz (bkz. src-tauri/src/guncelleme.rs).
import { getVersion } from "@tauri-apps/api/app";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { openUrl } from "@tauri-apps/plugin-opener";
import { t } from "./dil";
import type { Depo } from "./ortak";

const $ = <T extends HTMLElement = HTMLElement>(id: string) => document.getElementById(id) as T;

/** Sosyal medya ve iletişim bağlantıları: düğmeye basınca tarayıcıda açılır. */
const BAGLANTILAR: { ad: string; kullanici: string; url: string; ikon: string }[] = [
  { ad: "Instagram", kullanici: "@cybergahnet", url: "https://www.instagram.com/cybergahnet/",
    ikon: `<rect x="3" y="3" width="18" height="18" rx="5"/><circle cx="12" cy="12" r="4"/><path d="M17.5 6.5h.01"/>` },
  { ad: "X", kullanici: "@seyfi_polat21", url: "https://x.com/seyfi_polat21",
    ikon: `<path d="M4 4l16 16M20 4 4 20" />` },
  { ad: "X", kullanici: "@cyberagent2112", url: "https://x.com/cyberagent2112",
    ikon: `<path d="M4 4l16 16M20 4 4 20" />` },
  { ad: "YouTube", kullanici: "@cybergah", url: "https://www.youtube.com/@cybergah",
    ikon: `<rect x="2.5" y="5.5" width="19" height="13" rx="4"/><path d="m10 9 5 3-5 3V9Z"/>` },
  { ad: "TikTok", kullanici: "@cybergeh", url: "https://www.tiktok.com/@cybergeh?lang=tr-TR",
    ikon: `<path d="M14 3v11.5a3.5 3.5 0 1 1-3.5-3.5"/><path d="M14 3c.4 2.6 2.2 4.3 5 4.5"/>` },
  { ad: "cybergah.com", kullanici: "web", url: "https://cybergah.com/tr/",
    ikon: `<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c2.5 2.7 3.8 5.7 3.8 9s-1.3 6.3-3.8 9c-2.5-2.7-3.8-5.7-3.8-9S9.5 5.7 12 3Z"/>` },
];

/** Program ile ilgili iletişim hesabı (Hakkında'nın en altında). */
const ILETISIM = "https://x.com/seyfi_polat21";

const ALTI_SAAT = 6 * 60 * 60 * 1000;

interface Surum {
  surum: string; simdiki: string; yeni: boolean; sayfa: string; notlar: string;
  dosya: { ad: string; url: string; boyut: number } | null; sha_url: string | null;
}

let depo: Depo;
let kaydet: () => void;
let dinlemeyiDurdur: () => void = () => {};
let son: Surum | null = null;
let kuruluyor = false;
let durum: () => string = () => "";
let durumTur: "" | "hata" | "tamam" = "";

export const hakkindaAcikMi = () => !$("hakkinda").hidden;
export function hakkindaKapat() { $("hakkinda").hidden = true; }

export function hakkindaAc() {
  $("hakkinda").hidden = false;
  ciz();
}

function durumYaz(f: () => string, tur: "" | "hata" | "tamam" = "") {
  durum = f; durumTur = tur;
  const d = $("hk-durum");
  d.textContent = f();
  d.className = "hk-durum" + (tur ? " " + tur : "");
}

/** Rust'ın kısa hata kodları (E:...) program diline çevrilir; diğerleri olduğu gibi. */
const hataMetni = (e: unknown) => { const s = String(e); return s.startsWith("E:") ? t("gu.e." + s.slice(2)) : s; };

function mb(n: number) { return (n / 1048576).toFixed(n < 10 * 1048576 ? 1 : 0); }

/** Yeni sürüm gösterimi: şerit, nokta, Hakkında ekranındaki düğmeler. */
function yeniSurumGoster() {
  const var_ = !!son?.yeni;
  $("hk-nokta").hidden = !var_;
  $("gs").hidden = !var_ || $("gs").dataset.kapatildi === son?.surum;
  if (var_) $("gs-metin").textContent = t("gu.serit", { s: son!.surum });
  $("hk-kur").hidden = !var_ || kuruluyor;
  $("hk-kur").textContent = son?.dosya ? t("gu.kur", { mb: mb(son.dosya.boyut) }) : t("gu.sayfa");
  $("hk-notlar").hidden = !var_ || !son?.notlar.trim();
  $("hk-notlar").textContent = son?.notlar ?? "";
}

function ciz() {
  $("hk-oto").querySelector("input")!.checked = depo.guncellemeOtomatik;
  if (!kuruluyor && !son && !durum()) durumYaz(() => t("gu.bilinmiyor"));
  else durumYaz(durum, durumTur);
  yeniSurumGoster();
}

/** GitHub'dan son sürümü sorar. elle: kullanıcı düğmeye bastı (sonuç her durumda yazılır). */
export async function denetle(elle = false) {
  if (kuruluyor) return;
  if (elle) durumYaz(() => t("gu.denetleniyor"));
  try {
    son = await invoke<Surum>("guncelleme_denetle");
    depo.sonDenetim = Date.now(); kaydet();
    if (son.yeni) {
      durumYaz(() => t("gu.var", { s: son!.surum, e: son!.simdiki }), "tamam");
      if (depo.bildirilenSurum !== son.surum) {
        depo.bildirilenSurum = son.surum; kaydet();
        void invoke("bildirim_gonder", { baslik: "MED LIVE TRANSLATE", metin: t("gu.bildirim", { s: son.surum }) }).catch(() => {});
      }
    } else {
      durumYaz(() => t("gu.guncel", { s: son!.simdiki }), "tamam");
    }
  } catch (e) {
    // kendiliğinden denetimde hata sessiz kalır (internet yok vb.); sonraki denemede yeniden sorulur
    if (elle) durumYaz(() => t("gu.hata", { h: hataMetni(e) }), "hata");
  }
  yeniSurumGoster();
}

async function kur() {
  if (!son?.yeni) return;
  if (!son.dosya || !son.sha_url) { void openUrl(son.sayfa); return; }
  kuruluyor = true;
  yeniSurumGoster();
  dinlemeyiDurdur();
  $("hk-ilerleme").hidden = false;
  durumYaz(() => t("gu.indiriliyor", { a: "0", b: mb(son!.dosya!.boyut) }));
  try {
    const sonuc = await invoke<string>("guncelleme_kur", { ad: son.dosya.ad, url: son.dosya.url, shaUrl: son.sha_url });
    if (sonuc.startsWith("deb:")) durumYaz(() => t("gu.deb"), "tamam");
    else durumYaz(() => t("gu.kapaniyor"), "tamam"); // program birazdan kapanıp yeni sürümle açılır
  } catch (e) {
    kuruluyor = false;
    $("hk-ilerleme").hidden = true;
    durumYaz(() => t("gu.kur.hata", { h: hataMetni(e) }), "hata");
    yeniSurumGoster();
  }
}

export function hakkindaDilDegisti() {
  if (!$("hakkinda").hidden) ciz();
  else if (durum()) $("hk-durum").textContent = durum();
  baglantilariCiz();
  yeniSurumGoster();
}

function baglantilariCiz() {
  const kap = $("hk-baglantilar");
  kap.innerHTML = "";
  for (const b of BAGLANTILAR) {
    const d = document.createElement("button");
    d.className = "hk-baglanti";
    d.title = b.url;
    d.innerHTML = `<svg viewBox="0 0 24 24">${b.ikon}</svg><span><b></b><small></small></span>`;
    d.querySelector("b")!.textContent = b.ad;
    d.querySelector("small")!.textContent = b.kullanici === "web" ? t("hk.site") : b.kullanici;
    d.onclick = () => void openUrl(b.url).catch((e) => durumYaz(() => String(e), "hata"));
    kap.appendChild(d);
  }
  // en altta: iletişim satırı ({x} yerine tıklanabilir X hesabı)
  const il = $("hk-iletisim");
  il.innerHTML = "";
  const [once, sonra = ""] = t("hk.iletisim").split("{x}");
  const x = document.createElement("button");
  x.className = "hk-x"; x.textContent = "X @seyfi_polat21"; x.title = ILETISIM;
  x.onclick = () => void openUrl(ILETISIM).catch(() => {});
  il.append(once, x, sonra);
}

export function hakkindaKur(d: Depo, kaydetF: () => void, durdur: () => void) {
  depo = d; kaydet = kaydetF; dinlemeyiDurdur = durdur;
  baglantilariCiz();
  getVersion().then((s) => { $("hk-surum").textContent = t("hk.surum", { s }); $("hk-surum").dataset.s = s; }).catch(() => {});
  $("hk-kapat").onclick = hakkindaKapat;
  $("hk-denetle").onclick = () => void denetle(true);
  $("hk-kur").onclick = () => void kur();
  const oto = $("hk-oto").querySelector("input")!;
  oto.onchange = () => { depo.guncellemeOtomatik = oto.checked; kaydet(); };
  $("gs-ac").onclick = () => hakkindaAc();
  $("gs-kapat").onclick = () => { $("gs").dataset.kapatildi = son?.surum ?? ""; $("gs").hidden = true; };
  void listen<{ asama: string; inen?: number; toplam?: number }>("guncelleme", (e) => {
    const { asama, inen = 0, toplam = 0 } = e.payload;
    const dolgu = $("hk-dolgu");
    if (asama === "indiriliyor") {
      const b = toplam || son?.dosya?.boyut || 0;
      dolgu.style.width = b ? `${Math.min(100, (inen / b) * 100).toFixed(1)}%` : "30%";
      durumYaz(() => t("gu.indiriliyor", { a: mb(inen), b: mb(b) }));
    } else if (asama === "dogrulaniyor") {
      dolgu.style.width = "100%";
      durumYaz(() => t("gu.dogrulaniyor"));
    } else if (asama === "kuruluyor") {
      durumYaz(() => t("gu.kapaniyor"), "tamam");
    }
  });

  // kendiliğinden denetim: her açılışta 20 sn sonra, program açık kaldıkça son denetimden 6 saat geçince
  setTimeout(() => { if (depo.guncellemeOtomatik) void denetle(false); }, 20_000);
  setInterval(() => {
    if (depo.guncellemeOtomatik && Date.now() - depo.sonDenetim >= ALTI_SAAT) void denetle(false);
  }, 30 * 60 * 1000);
}
