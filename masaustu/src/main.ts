// Ana pencere: ilk kurulum, dil seçimi, başlat/durdur, ses ölçeri, konuşma geçmişi, temalar.
import { getVersion } from "@tauri-apps/api/app";
import { invoke } from "@tauri-apps/api/core";
import { emitTo, listen } from "@tauri-apps/api/event";
import { getCurrentWindow } from "@tauri-apps/api/window";
import { WebviewWindow } from "@tauri-apps/api/webviewWindow";
import { register, unregisterAll } from "@tauri-apps/plugin-global-shortcut";
import {
  TEMALAR, depoOku, depoYaz, temaUygula,
  type AltyaziOlayi, type DilBilgisi,
} from "./ortak";
import { gecmisAc, gecmisAcikMi, gecmisDilDegisti, gecmisKapat, gecmisKur, kayitEkle, oturumBaslat, oturumBitir } from "./gecmis";
import { hakkindaAc, hakkindaAcikMi, hakkindaDilDegisti, hakkindaKapat, hakkindaKur } from "./hakkinda";
import { kilavuzAc, kilavuzAcikMi, kilavuzDiliAyarla, kilavuzIlkKez, kilavuzKapat, kilavuzKur } from "./kilavuz";
import {
  ARAYUZ_DILLERI, arayuzDiliAyarla, dilAdiCevir, kurulumMesaji, motorMesaji, saatBicimi, sayfayiCevir, t, yerel,
  type ArayuzDili,
} from "./dil";

const $ = <T extends HTMLElement = HTMLElement>(id: string) => document.getElementById(id) as T;
const pencere = getCurrentWindow();
const depo = depoOku();
let diller: DilBilgisi[] = [];
let kurmanciBuyukVar = false;
let kurmanciOmniVar = false;     // deneysel Omnilingual Kurmancî modeli kurulu
let gpuKart = false;      // NVIDIA kartı var
let gpuHazir = false;     // kart + kitaplık: model kartta çalışabilir
let kaliteliKuruluyor = false;   // bir paket (kaliteli / hızlandırma) iniyor
let ekranKarti: string | null = null;  // Vulkan ile kullanılabilecek kart (AMD / Intel / NVIDIA)
let hizlandirmaVar = false;
let kmrHizlandirmaVar = false;   // kaliteli Kurmancî'nin ekran kartı sürümü kurulu
let yerelCeviriVar = false;      // çevrimdışı çeviri modeli (NLLB) kurulu

/** Motorun bilgisayar analizi (canli_altyazi/ekran_karti.py → analiz()). Hiçbir yere gönderilmez. */
interface Analiz {
  islemci: string; cekirdek: number; ram_gb: number; avx2: boolean; vulkan: boolean;
  kartlar: { ad: string; harici: boolean; bellek_gb: number }[];
  kart: { ad: string; harici: boolean; bellek_gb: number; seviye: "guclu" | "orta" | "zayif" } | null;
  kart_denendi: boolean | null; kart_sure: number | null;
}
let analiz: Analiz | null = null;
let cihaz = "cpu";
let calisiyor = false;
let hazir = false;
let tiklamaGecir = false;
let duraklatildi = false;
const gecmisMetin: string[] = [];

// ---------- tema ----------
function temaSec(ad: string) {
  depo.tema = ad;
  temaUygula(TEMALAR[ad]);
  document.querySelectorAll<HTMLButtonElement>(".tema").forEach((b) => b.classList.toggle("secili", b.dataset.tema === ad));
  kaydet();
  gorunumGonder();
}

function temaKutulariniKur() {
  const kap = $("temalar");
  kap.innerHTML = "";
  for (const [anahtar, tm] of Object.entries(TEMALAR)) {
    const b = document.createElement("button");
    b.className = "tema" + (anahtar === depo.tema ? " secili" : "");
    b.dataset.tema = anahtar;
    b.title = t("tema." + anahtar);
    b.style.background = `linear-gradient(160deg, ${tm.arka}, ${tm.arka} 55%, ${tm.vurgu}33)`;
    b.innerHTML = `<span class="kure" style="background: linear-gradient(135deg, ${tm.vurgu}, ${tm.vurgu2})"></span><span class="ad" style="color:${tm.metin}">${t("tema." + anahtar)}</span>`;
    b.onclick = () => temaSec(anahtar);
    kap.appendChild(b);
  }
}

// ---------- ayarlar ----------
function kaydet() {
  depoYaz(depo);
  // asıl kayıt dosyada (bkz. lib.rs ayar_yaz); localStorage diğer pencerelerin hızlı açılışı için
  void invoke("ayar_yaz", { icerik: JSON.stringify(depo) }).catch(() => {});
}

/** Açılışta ayarları dosyadan alır (varsa); localStorage'dakinden farklıysa onun yerine geçer. */
async function ayarlariDosyadanAl() {
  const ham = await invoke<string | null>("ayar_oku").catch(() => null);
  if (!ham) { kaydet(); return; }  // ilk kez: mevcut ayarlar dosyaya yazılır
  Object.assign(depo, depoOku(ham));
  depoYaz(depo);
}

function gorunumGonder() {
  void emitTo("altyazi", "gorunum", { gorunum: depo.gorunum, tema: depo.tema });
  kontrolDurumGonder();
}

// Küçük kontrol çubuğuna (kontrol.ts) güncel durumu bildirir
function kontrolDurumGonder() {
  void emitTo("kontrol", "kontrol-durum", {
    calisiyor, hazir, duraklatildi,
    durum: $("t-durum")?.textContent ?? "", durumTur: $("durum")?.dataset.tur ?? "",
    diller, konusma: depo.motor.konusma_dili, hedef: depo.motor.hedef_dil, tema: depo.tema,
    arayuzDili: depo.arayuzDili,
  });
}

function motoraGonder(komut: string, ekle: Record<string, unknown> = {}) {
  return invoke("motor_komut", { komut: { komut, ...ekle } }).catch((e) => durumYaz(String(e), "hata"));
}

function dilSecenekleri(sel: HTMLSelectElement, deger: string) {
  sel.innerHTML = "";
  for (const d of diller) {
    const o = document.createElement("option");
    o.value = d.kod;
    o.textContent = dilAdiCevir(d.kod);
    sel.appendChild(o);
  }
  sel.value = deger;
}

function formuDoldur() {
  dilSecenekleri($("s-konusma"), depo.motor.konusma_dili);
  dilSecenekleri($("s-hedef"), depo.motor.hedef_dil);
  void kaliteSatiri();
  void hizSatiri();
}

/** Ekran kartı var ama o dilin kart modeli kurulu değilse indirme önerisi.
 *  Kurmancî: yalnızca kaliteli model seçiliyken (hızlı Kurmancî işlemcide zaten ~2 sn). */
async function hizSatiri() {
  const kmr = depo.motor.konusma_dili === "kmr";
  const omni = kurmanciOmniVar && depo.motor.kurmanci_omni;
  const eksik = kmr ? !omni && kurmanciBuyukVar && depo.motor.kurmanci_kaliteli && !kmrHizlandirmaVar : !hizlandirmaVar;
  const goster = !!ekranKarti && !gpuHazir && diller.length > 0 && eksik
    && (kmr ? kmrKartUygun() : kartFaydali() !== null);
  $("hiz-indir").hidden = !goster;
  if (!goster) return;
  const b = await invoke<{ indirme_mb: number }>("hizlandirma_bilgi", { kmr }).catch(() => null);
  if (!b || b.indirme_mb <= 0) { $("hiz-indir").hidden = true; return; }
  $("hiz-indir-metin").textContent = t(kmr ? "hiz.kmr" : "hiz.genel", { k: ekranKarti!, gb: gb(b.indirme_mb) });
  if (!kaliteliKuruluyor) $("b-hiz-indir").textContent = t("paket.indir");
}

const gb = (mb: number) => (mb / 1000).toLocaleString(yerel(), { maximumFractionDigits: 1 });
const sayi = (n: number) => n.toLocaleString(yerel(), { maximumFractionDigits: 1 });

// ---------- bilgisayar analizi ve öneriler ----------
/** Kart Türkçe/İngilizce/Arapça hızlandırmasında işe yarar mı: "guclu" | "orta" | null (yaramaz). */
function kartFaydali(): "guclu" | "orta" | null {
  const a = analiz;
  if (!a || !a.kart || !a.avx2 || a.kart_denendi === false) return null;
  return a.kart.seviye === "zayif" ? null : a.kart.seviye;
}

/** Büyük (kaliteli) Kurmancî modeli kartta çalışabilir mi: güçlü kart + yeterli bellek. */
function kmrKartUygun(): boolean {
  const a = analiz;
  if (kartFaydali() !== "guclu" || !a?.kart) return false;
  return a.kart.harici ? a.kart.bellek_gb >= 3 : a.ram_gb >= 15;
}

type OneriDurum = "oneri" | "kurulu" | "gereksiz" | "istege-bagli" | "uygun-degil";
interface Oneri { baslik: string; durum: OneriDurum; rozet: string; metin: string; paket?: PaketTuru; mb?: number; kilitli?: string }

async function onerileriHesapla(): Promise<Oneri[]> {
  const a = analiz;
  const kart = a?.kart;
  const k = kart ? `${kart.ad}${kart.harici ? "" : ` (${t("o.dahili")})`}` : "";
  const liste: Oneri[] = [];
  const hiz = await invoke<{ indirme_mb: number }>("hizlandirma_bilgi", { kmr: false }).catch(() => ({ indirme_mb: 0 }));
  const hizK = await invoke<{ indirme_mb: number }>("hizlandirma_bilgi", { kmr: true }).catch(() => ({ indirme_mb: 0 }));
  const kal = await invoke<{ indirme_mb: number }>("kaliteli_bilgi").catch(() => ({ indirme_mb: 0 }));
  const yeni = (baslik: string): Oneri => ({ baslik: t(baslik), durum: "uygun-degil", rozet: t("o.r.gerekyok"), metin: "" });
  const durum = (o: Oneri, d: OneriDurum, rozet: string) => { o.durum = d; o.rozet = t(rozet); };

  // 1) Türkçe / İngilizce / Arapça hızlandırması
  {
    const o = yeni("o.hiz.baslik");
    const f = kartFaydali();
    if (hizlandirmaVar) {
      durum(o, "kurulu", "o.r.kurulu");
      o.metin = !kart ? t("o.hiz.kurulu.yok") : a?.kart_denendi === false ? t("o.hiz.kurulu.yavas") : t("o.hiz.kurulu", { k });
    } else if (gpuHazir) {
      durum(o, "gereksiz", "o.r.gerekyok"); o.metin = t("o.hiz.nvidia");
    } else if (!kart) {
      o.metin = t("o.hiz.kartyok");
    } else if (!a?.avx2) {
      o.metin = t("o.hiz.avx2");
    } else if (a?.kart_denendi === false) {
      o.metin = t("o.hiz.denendi", { k });
    } else if (f === "guclu") {
      durum(o, "oneri", "o.r.oneri"); o.paket = "hizlandirma"; o.mb = hiz.indirme_mb; o.metin = t("o.hiz.guclu", { k });
    } else if (f === "orta") {
      durum(o, "oneri", "o.r.faydali"); o.paket = "hizlandirma"; o.mb = hiz.indirme_mb; o.metin = t("o.hiz.orta", { k });
    } else {
      o.metin = t("o.hiz.zayif", { k });
    }
    liste.push(o);
  }

  // 2) Kaliteli Kurmancî paketi
  {
    const o = yeni("o.kal.baslik");
    durum(o, "istege-bagli", "o.r.istege");
    const kitaplikEksik = gpuKart && !gpuHazir;
    if (kurmanciBuyukVar && !kitaplikEksik) {
      durum(o, "kurulu", "o.r.kurulu"); o.metin = t("o.kal.kurulu");
    } else if ((a?.ram_gb ?? 16) < 7.5) {
      durum(o, "uygun-degil", "o.r.uygundegil"); o.metin = t("o.kal.ram", { gb: sayi(a!.ram_gb) });
    } else if (gpuKart) {
      durum(o, "oneri", "o.r.oneri"); o.paket = "kaliteli"; o.mb = kal.indirme_mb;
      o.metin = t(kitaplikEksik && kurmanciBuyukVar ? "o.kal.kitaplik" : "o.kal.nvidia");
    } else if (kmrKartUygun()) {
      durum(o, "oneri", "o.r.oneri"); o.paket = "kaliteli"; o.mb = kal.indirme_mb; o.metin = t("o.kal.kart");
    } else {
      o.paket = "kaliteli"; o.mb = kal.indirme_mb; o.metin = t("o.kal.cpu");
    }
    liste.push(o);
  }

  // 3) Kaliteli Kurmancî — ekran kartı (whisper.cpp / Vulkan)
  {
    const o = yeni("o.kk.baslik");
    if (kmrHizlandirmaVar) {
      durum(o, "kurulu", "o.r.kurulu");
      o.metin = t(kart && kartFaydali() !== null ? "o.kk.kurulu" : "o.kk.kurulu.yok");
    } else if (gpuKart) {
      durum(o, "gereksiz", "o.r.gerekyok"); o.metin = t("o.kk.nvidia");
    } else if (!kart || kartFaydali() === null) {
      o.metin = t("o.kk.kartyok");
    } else if (!kmrKartUygun()) {
      o.metin = t("o.kk.zayif", { k });
    } else {
      durum(o, "oneri", "o.r.oneri"); o.paket = "hizlandirma_kmr"; o.mb = hizK.indirme_mb; o.metin = t("o.kk.oneri", { k });
      if (!kurmanciBuyukVar) o.kilitli = t("o.kk.kilitli");
    }
    liste.push(o);
  }

  // 4) Çevrimdışı çeviri (bilgi)
  {
    const o = yeni("o.cd.baslik");
    if (yerelCeviriVar) { durum(o, "kurulu", "o.r.kurulu"); o.metin = t("o.cd.kurulu"); }
    else { durum(o, "istege-bagli", "o.r.eksik"); o.metin = t("o.cd.eksik"); }
    liste.push(o);
  }
  return liste;
}

function sistemOzeti(a: Analiz | null): string {
  if (!a) return t("o.sistem.okunamadi");
  const kartlar = a.kartlar.length
    ? a.kartlar.map((x) => `${x.ad} (${t(x.harici ? "o.harici" : "o.dahili")}${x.harici && x.bellek_gb ? `, ${sayi(x.bellek_gb)} GB` : ""})`).join(", ")
    : t("o.sistem.yok");
  const kac = (x: string) => x.replace(/[&<>]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;" })[c]!);
  return `<b>${t("o.sistem.islemci")}:</b> ${kac(a.islemci || "?")} · ${t("o.sistem.cekirdek", { n: a.cekirdek })}<br>` +
    `<b>${t("o.sistem.bellek")}:</b> ${sayi(a.ram_gb)} GB<br><b>${t("o.sistem.kart")}:</b> ${kac(kartlar)}`;
}

let oneriler: Oneri[] = [];

async function onerileriGoster() {
  oneriler = await onerileriHesapla();
  // indirilecek bir şey kalmamışsa (dosyalar zaten inmiş) "Önerilir" yerine "Kurulu" gösterilir
  for (const o of oneriler) if (o.paket && (o.mb ?? 0) <= 0 && o.durum === "oneri") { o.durum = "kurulu"; o.rozet = t("o.r.kurulu"); }
  $("oneri-sistem").innerHTML = sistemOzeti(analiz);
  const kap = $("oneri-liste");
  kap.innerHTML = "";
  for (const o of oneriler) {
    const el = document.createElement("div");
    el.className = "oneri";
    el.dataset.durum = o.durum;
    const ust = document.createElement("div"); ust.className = "oneri-ust";
    const b = document.createElement("b"); b.textContent = o.baslik;
    const r = document.createElement("span"); r.className = "oneri-rozet"; r.textContent = o.rozet;
    ust.append(b, r);
    const m = document.createElement("p"); m.textContent = o.metin;
    el.append(ust, m);
    if (o.paket && (o.mb ?? 0) > 0) {
      const alt = document.createElement("div"); alt.className = "oneri-alt";
      const boy = document.createElement("span"); boy.textContent = o.kilitli ?? t("paket.boyut", { gb: gb(o.mb!) });
      const d = document.createElement("button"); d.className = "kucuk-dugme";
      d.textContent = t(kaliteliKuruluyor ? "paket.baska" : "paket.indir");
      d.disabled = !!o.kilitli || kaliteliKuruluyor;
      const paket = o.paket;
      d.onclick = () => { $("oneriler").hidden = true; void kaliteliKur(paket); };
      alt.append(boy, d);
      el.appendChild(alt);
    }
    kap.appendChild(el);
  }
  $("oneriler").hidden = false;
}

/** Önerilen ama kurulmamış paket varsa "Öneriler" düğmesinde nokta; ilk kez bir defa kendiliğinden açılır. */
async function oneriKontrol() {
  const liste = await onerileriHesapla();
  const bekleyen = liste.some((o) => o.durum === "oneri" && o.paket && (o.mb ?? 0) > 0 && !o.kilitli);
  $("oneri-nokta").hidden = !bekleyen;
  if (!depo.oneriGoruldu && $("kurulum").hidden) {
    if (kilavuzAcikMi()) { oneriBekliyor = true; return; }  // kılavuz kapanınca açılır
    depo.oneriGoruldu = true; kaydet();
    void onerileriGoster();
  }
}
let oneriBekliyor = false;

/** Kurmancî seçiliyken: kaliteli model kutusu, ya da (kurulu değilse / kart kitaplığı eksikse) indirme önerisi. */
async function kaliteSatiri() {
  const kmr = depo.motor.konusma_dili === "kmr" && diller.length > 0;
  $("kalite-satiri").hidden = !kmr;
  if (!kmr) return;
  kmrModelSecimi();
  void omniSatiri();

  // indirme önerisi: model yoksa, ya da NVIDIA kartı var ama kitaplığı yoksa
  const eksik = !kurmanciBuyukVar || (gpuKart && !gpuHazir);
  $("kalite-indir").hidden = !eksik;
  if (!eksik) return;
  const b = await invoke<{ indirme_mb: number; nvidia: boolean }>("kaliteli_bilgi").catch(() => null);
  if (!b || b.indirme_mb <= 0) { $("kalite-indir").hidden = true; return; }
  $("kalite-indir-metin").textContent = t(!kurmanciBuyukVar ? (b.nvidia ? "kalite.indir.nvidia" : "kalite.indir.cpu")
    : "kalite.indir.kitaplik", { gb: gb(b.indirme_mb) });
  if (!kaliteliKuruluyor) $("b-kaliteli-indir").textContent = t("paket.indir");
}

/** Kurmancî modeli seçimi: Kürtçe Küçük (her zaman) / Orta (Omnilingual) / Büyük (Whisper large), kurulu olanlar. */
function kmrModelSecimi() {
  const s = $<HTMLSelectElement>("s-kmr-model");
  const secenekler: [string, string][] = [["kucuk", "kmr.kucuk"]];
  if (kurmanciOmniVar) secenekler.push(["orta", "kmr.orta"]);
  if (kurmanciBuyukVar) secenekler.push(["buyuk", "kmr.buyuk"]);
  s.replaceChildren(...secenekler.map(([v, a]) => { const o = document.createElement("option"); o.value = v; o.textContent = t(a); return o; }));
  const m = depo.motor;
  const secili = m.kurmanci_omni && kurmanciOmniVar ? "orta" : m.kurmanci_kaliteli && kurmanciBuyukVar ? "buyuk" : "kucuk";
  s.value = secili;
  s.disabled = secenekler.length < 2;
  $("kmr-model-not").textContent = t(secili === "orta" ? "kmr.orta.not" : secili === "buyuk"
    ? (gpuHazir || (kmrHizlandirmaVar && !!ekranKarti) ? "kmr.buyuk.not.hizli" : "kmr.buyuk.not.yavas") : "kmr.kucuk.not");
}

/** Kürtçe Orta (Omnilingual) kurulu değilse indirme önerisi. */
async function omniSatiri() {
  $("omni-indir").hidden = true;
  if (kurmanciOmniVar) return;
  const b = await invoke<{ indirme_mb: number; destek: boolean }>("omni_bilgi").catch(() => null);
  if (!b || !b.destek || b.indirme_mb <= 0) return;
  $("omni-indir-metin").textContent = t("omni.indir", { gb: gb(b.indirme_mb) });
  if (!kaliteliKuruluyor) $("b-omni-indir").textContent = t("paket.indir");
  $("omni-indir").hidden = false;
}

type PaketTuru = "kaliteli" | "hizlandirma" | "hizlandirma_kmr" | "omni";
const PAKETLER: Record<PaketTuru, { dugme: string; baslik: string; komut: string; arg?: Record<string, unknown> }> = {
  // baslik: sözlük anahtarı (dil.ts)
  kaliteli: { dugme: "b-kaliteli-indir", baslik: "paket.kaliteli", komut: "kaliteli_kur" },
  omni: { dugme: "b-omni-indir", baslik: "paket.omni", komut: "omni_kur" },
  hizlandirma: { dugme: "b-hiz-indir", baslik: "paket.hiz", komut: "hizlandirma_kur", arg: { kmr: false } },
  hizlandirma_kmr: { dugme: "b-hiz-indir", baslik: "paket.hiz.kmr", komut: "hizlandirma_kur", arg: { kmr: true } },
};
let kurulanPaket: PaketTuru = "kaliteli";

/** İsteğe bağlı paketi (kaliteli Kurmancî / ekran kartı hızlandırması) kurar; ekran arka plana alınabilir. */
async function kaliteliKur(tur: PaketTuru = "kaliteli") {
  if (kaliteliKuruluyor) { $("kurulum").hidden = false; return; }  // aynı anda tek paket
  kaliteliKuruluyor = true;
  kurulanPaket = tur;
  const p = PAKETLER[tur];
  // indirme sürerken düğme ilerleme ekranını yeniden açar (ekran gizlenmiş olabilir)
  $(p.dugme).textContent = t("paket.ilerleme");
  $("k-baslik").textContent = t(p.baslik);
  $("k-aciklama").textContent = t("k.paket.aciklama");
  $("k-yuzde").textContent = t("yuzde", { n: 0 }); $("k-dolgu").style.width = "0%";
  $("k-mesaj").textContent = t("k.basliyor"); $("k-ayrinti").textContent = "";
  $("k-hata").hidden = true; $("k-tekrar").hidden = true; $("k-gizle").hidden = false;
  $("kurulum").classList.remove("bitti");
  $("kurulum").hidden = false;
  const birak = await listen<KurulumOlayi>("kurulum", ({ payload }) => {
    kurulumGoster(payload);
    if (payload.yuzde !== undefined) $(p.dugme).textContent = t("paket.ilerleme.y", { y: Math.floor(Math.min(100, payload.yuzde)) });
    if (payload.asama === "hata") $(p.dugme).textContent = t("paket.ilerleme.durdu");
    if (payload.asama === "hata" && $("kurulum").hidden) durumYaz(() => `⚠ ${t(p.baslik)}: ${kurulumMesaji(payload.mesaj)}`, "hata");
  });
  const sonuc = await new Promise<boolean>((bitti) => {
    kurulumBitti = () => bitti(true);
    const basla = () => {
      $("k-hata").hidden = true;
      $("k-tekrar").hidden = true;
      invoke(p.komut, p.arg).catch((e) => kurulumGoster({ asama: "hata", mesaj: String(e) }));
    };
    $("k-tekrar").onclick = basla;
    basla();
  });
  birak();
  kurulumBitti = null;
  kaliteliKuruluyor = false;
  $(p.dugme).textContent = t("paket.indir");
  $("k-gizle").hidden = true;
  $("kurulum").classList.add("bitti");
  setTimeout(() => ($("kurulum").hidden = true), 900);
  if (sonuc) {
    if (tur === "kaliteli") { depo.motor.kurmanci_kaliteli = true; depo.motor.kurmanci_omni = false; kaydet(); }
    if (tur === "omni") { depo.motor.kurmanci_omni = true; depo.motor.kurmanci_kaliteli = false; kaydet(); }
    void motoraGonder("bilgi");  // motor yeni modeli / kitaplığı görsün; ardından yeniden yüklenir
    durumYaz(() => t("paket.kuruldu", { p: t(p.baslik) }), "hazir");
  }
}

function ayarDegisti() {
  const m = depo.motor;
  m.konusma_dili = $<HTMLSelectElement>("s-konusma").value || m.konusma_dili;
  m.hedef_dil = $<HTMLSelectElement>("s-hedef").value || m.hedef_dil;
  void kaliteSatiri();
  void hizSatiri();
  kaydet();
  kontrolDurumGonder();
  void motoraGonder(calisiyor ? "ayarlar" : "yukle", { ayarlar: m });
}

function gorunumFormu() {
  const g = depo.gorunum;
  const bagla = (id: string, deger: number, goster: (v: number) => string, yaz: (v: number) => void) => {
    const r = $<HTMLInputElement>(id);
    const etiket = $("v-" + id.slice(2));
    r.value = String(deger);
    etiket.textContent = goster(deger);
    r.oninput = () => {
      const v = Number(r.value);
      etiket.textContent = goster(v);
      yaz(v);
      kaydet();
      gorunumGonder();
    };
  };
  bagla("r-boyut", g.yaziBoyutu, (v) => `${v}px`, (v) => (g.yaziBoyutu = v));
  bagla("r-satir", g.satirSayisi, (v) => String(v), (v) => (g.satirSayisi = v));
  bagla("r-opaklik", g.opaklik, (v) => t("yuzde", { n: Math.round(v * 100) }), (v) => (g.opaklik = v));
  const k = $<HTMLInputElement>("a-orijinal");
  k.checked = g.orijinal;
  k.onchange = () => { g.orijinal = k.checked; kaydet(); gorunumGonder(); };
}

// ---------- durum ----------
type DurumTur = "hazir" | "dinliyor" | "yukleniyor" | "hata" | "bos";
let sonDurum: { metin: string | (() => string); tur: DurumTur } = { metin: () => t("d.aciliyor"), tur: "bos" };
/** metin bir işlevse program dili değişince yeniden üretilir (durum satırı da yeni dilde görünür). */
function durumYaz(metin: string | (() => string), tur: DurumTur) {
  sonDurum = { metin, tur };
  $("t-durum").textContent = typeof metin === "function" ? metin() : metin;
  $("durum").dataset.tur = tur;
  kontrolDurumGonder();
}

function baslatDugmesi() {
  const b = $("b-baslat");
  b.classList.toggle("calisiyor", calisiyor);
  b.classList.toggle("bekliyor", !calisiyor && !hazir);
  $("t-baslat").textContent = t(calisiyor ? "durdur" : "baslat");
  $("b-baslat").setAttribute("aria-label", t(calisiyor ? "durdur" : "baslat"));
  $("b-duraklat").querySelector("span")!.textContent = t(duraklatildi ? "hap.devam" : "hap.duraklat");
  kontrolDurumGonder();
}

// ---------- geçmiş ----------
const liste = () => $("gecmis");
const sondaMi = () => { const l = liste(); return l.scrollHeight - l.scrollTop - l.clientHeight < 30; };
let yeniSayisi = 0;

function dilAdi(kod: string | null) {
  return kod ? dilAdiCevir(kod) : "";
}

function gecmiseEkle(o: AltyaziOlayi) {
  const l = liste();
  const sondaydi = sondaMi();
  l.querySelector(".bos")?.remove();
  const saat = new Date().toLocaleTimeString(saatBicimi());
  const el = document.createElement("div");
  el.className = "kayit";
  const ust = document.createElement("div");
  ust.className = "kayit-ust";
  ust.innerHTML = `<span>${saat}</span><span class="etiket">${dilAdi(o.dil)}</span>`;
  el.appendChild(ust);
  const ana = document.createElement("div");
  ana.className = "kayit-ceviri" + ((o.ceviri ? o.hedef_rtl : o.rtl) ? " sag-hiza" : "");
  ana.textContent = o.ceviri || o.orijinal;
  el.appendChild(ana);
  if (o.ceviri) {
    const alt = document.createElement("div");
    alt.className = "kayit-orijinal" + (o.rtl ? " sag-hiza" : "");
    alt.textContent = o.orijinal;
    el.appendChild(alt);
  }
  l.appendChild(el);
  // çok uzun toplantılarda pencere ağırlaşmasın: ekranda en çok 400 satır
  while (l.children.length > 400) l.firstElementChild?.remove();
  gecmisMetin.push(`[${saat}] (${o.dil}): ${o.orijinal}` + (o.ceviri ? `\n           → ${o.ceviri}` : ""));
  if (sondaydi) l.scrollTop = l.scrollHeight;
  else { yeniSayisi++; const b = $("b-yeni"); b.textContent = t("gecmis.yeni", { n: yeniSayisi }); b.hidden = false; }
}

function onizlemeGuncelle(o: AltyaziOlayi) {
  const c = $("on-ceviri"), r = $("on-orijinal");
  c.textContent = o.ceviri || o.orijinal;
  r.textContent = o.ceviri ? o.orijinal : "";
  c.classList.toggle("sag-hiza", o.ceviri ? o.hedef_rtl : o.rtl);
  r.classList.toggle("sag-hiza", o.rtl);
}

// ---------- ilk kurulum ----------
interface KurulumOlayi {
  asama: "indir" | "donustur" | "denetle" | "tamam" | "hata";
  yuzde?: number; mesaj: string; indirilen?: number; toplam?: number; hiz?: number; kalan_sn?: number | null;
}

const mb = (b: number) => (b / 1048576).toLocaleString(yerel(), { maximumFractionDigits: 0 });
function sure(sn: number) {
  if (sn < 60) return t("sure.sn", { n: Math.max(1, Math.round(sn)) });
  const dk = Math.round(sn / 60);
  return dk < 60 ? t("sure.dk", { n: dk }) : t("sure.sa", { s: Math.floor(dk / 60), d: dk % 60 });
}

let kurulumBitti: (() => void) | null = null;

function kurulumGoster(o: KurulumOlayi) {
  if (o.yuzde !== undefined) {
    const y = Math.min(100, Math.max(0, o.yuzde));
    $("k-yuzde").textContent = t("yuzde", { n: Math.floor(y) });
    $("k-dolgu").style.width = `${y}%`;
  }
  if (o.asama === "hata") {
    $("k-hata").hidden = false;
    $("k-hata").textContent = t("k.durdu", { m: kurulumMesaji(o.mesaj ?? "") });
    $("k-tekrar").hidden = false;
    $("k-mesaj").textContent = t(/disk|alan/i.test(o.mesaj ?? "") ? "k.disk" : "k.internet");
    $("k-ayrinti").textContent = "";
    $("kurulum").dataset.durum = "hata";
    return;
  }
  $("kurulum").dataset.durum = "";
  $("k-mesaj").textContent = kurulumMesaji(o.mesaj);
  if (o.asama === "indir" && o.toplam) {
    let a = `${mb(o.indirilen ?? 0)} / ${mb(o.toplam)} MB`;
    if (o.hiz && o.hiz > 1000) a += `  ·  ${(o.hiz / 1048576).toLocaleString(yerel(), { maximumFractionDigits: 1 })} ${t("k.mbsn")}`;
    if (o.kalan_sn != null) a += `  ·  ${t("k.kalan", { s: sure(o.kalan_sn) })}`;
    $("k-ayrinti").textContent = a;
  } else if (o.asama !== "tamam") {
    $("k-ayrinti").textContent = t("k.son");
  }
  if (o.asama === "tamam") {
    $("k-ayrinti").textContent = "";
    kurulumBitti?.();
  }
}

/** Bileşenler kurulu değilse kurulum ekranını gösterip bitmesini bekler. */
async function kurulumuTamamla() {
  const d = await invoke<{ tamam: boolean; kok: string | null }>("bilesen_durumu");
  if (d.tamam) return;
  $("k-baslik").textContent = t("k.baslik");
  $("k-aciklama").textContent = t("k.aciklama");
  $("k-mesaj").textContent = t("k.basliyor");
  ilkKurulumSuruyor = true;
  $("kurulum").hidden = false;
  durumYaz(() => t("d.ilkkurulum"), "yukleniyor");
  // dinleyici kurulumdan ÖNCE hazır olmalı: aksi halde hızlı biten bir kurulumun "tamam"ı kaçar
  const birak = await listen<KurulumOlayi>("kurulum", ({ payload }) => kurulumGoster(payload));
  await new Promise<void>((bitti) => {
    kurulumBitti = bitti;
    const basla = () => {
      $("k-hata").hidden = true;
      $("k-tekrar").hidden = true;
      invoke("bilesen_kur").catch((e) => kurulumGoster({ asama: "hata", mesaj: String(e) }));
    };
    $("k-tekrar").onclick = basla;
    basla();
  });
  birak();
  ilkKurulumSuruyor = false;
  $("kurulum").classList.add("bitti");
  setTimeout(() => ($("kurulum").hidden = true), 900);
}
let ilkKurulumSuruyor = false;

// ---------- motor olayları ----------
async function motorDinle() {
  await listen<Record<string, any>>("motor", ({ payload: o }) => {
    switch (o.tur) {
      case "bilgi": {
        diller = o.diller;
        kurmanciBuyukVar = o.kurmanci_buyuk === true;
        kurmanciOmniVar = o.kurmanci_omni === true;
        gpuKart = o.gpu === true;
        gpuHazir = o.gpu_hazir === true;
        ekranKarti = typeof o.ekran_karti === "string" ? o.ekran_karti : null;
        hizlandirmaVar = o.hizlandirma_var === true;
        kmrHizlandirmaVar = o.kmr_hizlandirma_var === true;
        yerelCeviriVar = o.yerel_ceviri_var === true;
        analiz = (o.analiz as Analiz) ?? null;
        formuDoldur();
        cevrimdisiDugmesi();
        void oneriKontrol();
        kontrolDurumGonder();
        void motoraGonder("yukle", { ayarlar: depo.motor });
        break;
      }
      case "durum": {
        const m: string = o.metin;
        if (typeof o.hazir === "boolean") hazir = o.hazir;
        if (m === "Dinleniyor") { calisiyor = true; durumYaz(() => t("d.dinleniyor"), "dinliyor"); }
        else if (m.startsWith("Dinleniyor")) { calisiyor = true; durumYaz(() => motorMesaji(m), "yukleniyor"); }
        else if (m === "Durduruldu") { calisiyor = false; const h = hazir; durumYaz(() => t(h ? "d.durduruldu.hazir" : "d.durduruldu"), "hazir"); }
        else if (m.startsWith("Hazır —")) {
          const kartta = cihaz === "cuda" || cihaz === "vulkan";
          durumYaz(() => t("d.hazir") + (kartta ? t("d.ekrankarti") : ""), "hazir");
        }
        else durumYaz(() => motorMesaji(m), "yukleniyor");
        baslatDugmesi();
        break;
      }
      case "hata": {
        const m: string = o.metin;
        durumYaz(() => "⚠ " + motorMesaji(m), "hata");
        if (o.durdu) { calisiyor = false; baslatDugmesi(); }  // başlatılamadı: düğme "Başlat"a dönsün
        break;
      }
      case "cihaz":
        cihaz = o.deger;
        break;
      case "motor_kapandi":
        calisiyor = false; hazir = false;
        { const m: string = o.metin; durumYaz(() => "⚠ " + motorMesaji(m), "hata"); }
        baslatDugmesi();
        break;
      case "seviye": {
        const yuzde = (db: number) => Math.max(0, Math.min(100, (db + 60) * 2));
        $("o-bilgisayar").style.width = `${calisiyor ? yuzde(o.bilgisayar) : 0}%`;
        break;
      }
      case "duraklatildi":
        duraklatildi = o.deger;
        $("b-duraklat").classList.toggle("etkin", duraklatildi);
        $("b-duraklat").querySelector("span")!.textContent = t(duraklatildi ? "hap.devam" : "hap.duraklat");
        durumYaz(() => t(duraklatildi ? "d.duraklatildi" : "d.dinleniyor"), duraklatildi ? "yukleniyor" : "dinliyor");
        break;
      case "son":
        gecmiseEkle(o as AltyaziOlayi);
        if (depo.gecmisKaydet) kayitEkle(o as AltyaziOlayi, depo.motor.hedef_dil);
        onizlemeGuncelle(o as AltyaziOlayi);
        break;
    }
  });
  // küçük kontrol çubuğundan gelen komutlar
  await listen<{ eylem: string; konusma?: string; hedef?: string }>("kontrol-komut", async ({ payload: k }) => {
    switch (k.eylem) {
      case "iste": kontrolDurumGonder(); break;
      case "baslat": baslatDurdur(); break;
      case "duraklat": if (calisiyor) kisayolEylemi("duraklat"); break;
      case "serit": void seritGosterGizle(); break;
      case "dil":
        if (k.konusma) $<HTMLSelectElement>("s-konusma").value = k.konusma;
        if (k.hedef) $<HTMLSelectElement>("s-hedef").value = k.hedef;
        ayarDegisti();
        break;
      case "ana":
        await pencere.unminimize();
        await pencere.show();
        await pencere.setFocus();
        break;
    }
  });
  await listen<{ tiklama: boolean }>("altyazi-durum", ({ payload }) => {
    tiklamaGecir = payload.tiklama;
    $("b-gecir").classList.toggle("etkin", tiklamaGecir);
  });
}

// ---------- eylemler ----------
function baslatDurdur() {
  if (!diller.length) return; // motor henüz açılmadı (ilk kurulum sürüyor)
  if (calisiyor) {
    void motoraGonder("durdur");
    calisiyor = false;
    oturumBitir();
  } else {
    oturumBaslat();
    void emitTo("altyazi", "altyazi-komut", { eylem: "temizle" });
    void motoraGonder("baslat", { ayarlar: depo.motor });
    calisiyor = true;
    durumYaz(() => t("d.baslatiliyor"), "yukleniyor");
  }
  baslatDugmesi();
  void seritDurumu();
}

async function seritGosterGizle() {
  const w = await WebviewWindow.getByLabel("altyazi");
  if (!w) return;
  if (await w.isVisible()) await w.hide(); else { await w.show(); await w.setAlwaysOnTop(true); }
  void seritDurumu();
}

/** Şerit açıkken düğme vurgulu; dinlerken şerit kapalıysa düğme yanıp söner (açılabileceğini hatırlatır). */
async function seritDurumu() {
  const w = await WebviewWindow.getByLabel("altyazi");
  const acik = !!w && (await w.isVisible());
  $("b-serit").classList.toggle("etkin", acik);
  $("b-serit").classList.toggle("dikkat", !acik && calisiyor);
}

function cevrimdisiDugmesi() {
  const acik = depo.motor.cevrimdisi;
  $("b-cevrimdisi").classList.toggle("etkin", acik);
  $("b-cevrimdisi").querySelector("span")!.textContent = t(acik ? "hap.cevrimdisi.acik" : "hap.cevrimdisi");
}

function cevrimdisiDegistir() {
  if (!depo.motor.cevrimdisi && !yerelCeviriVar) {
    durumYaz(() => t("d.cd.yok"), "hata");
    return;
  }
  depo.motor.cevrimdisi = !depo.motor.cevrimdisi;
  kaydet();
  cevrimdisiDugmesi();
  void motoraGonder(calisiyor ? "ayarlar" : "yukle", { ayarlar: depo.motor });
  const acik = depo.motor.cevrimdisi;
  durumYaz(() => t(acik ? "d.cd.acik" : "d.cd.kapali"), calisiyor ? "dinliyor" : "hazir");
}

async function kontrolGosterGizle() {
  const w = await WebviewWindow.getByLabel("kontrol");
  if (!w) return;
  if (await w.isVisible()) await w.hide();
  else { await w.show(); await w.setAlwaysOnTop(true); kontrolDurumGonder(); }
}

function kisayolEylemi(eylem: string) {
  if (eylem === "gizle") void seritGosterGizle();
  else if (eylem === "kontrol") void kontrolGosterGizle();
  else if (eylem === "duraklat") void motoraGonder("duraklat");
  else if (eylem === "gecir") void emitTo("altyazi", "altyazi-komut", { eylem: "tiklama" });
  else if (eylem === "buyut" || eylem === "kucult") {
    const g = depo.gorunum;
    g.yaziBoyutu = Math.max(16, Math.min(64, g.yaziBoyutu + (eylem === "buyut" ? 2 : -2)));
    gorunumFormu(); kaydet(); gorunumGonder();
  }
}

async function kisayollariKur() {
  try {
    await unregisterAll();
    const harita: Record<string, string> = {
      "CommandOrControl+Alt+H": "gizle", "CommandOrControl+Alt+P": "duraklat", "CommandOrControl+Alt+M": "kontrol",
      "CommandOrControl+Alt+K": "gecir", "CommandOrControl+Alt+Up": "buyut", "CommandOrControl+Alt+Down": "kucult",
    };
    await register(Object.keys(harita), (e) => { if (e.state === "Pressed") kisayolEylemi(harita[e.shortcut] ?? harita[Object.keys(harita).find((k) => k.toLowerCase() === e.shortcut.toLowerCase()) ?? ""]); });
  } catch (e) {
    console.warn("Kısayollar kaydedilemedi", e);
  }
}

function cekmece(ac: boolean) {
  $("cekmece").classList.toggle("acik", ac);
  $("cekmece").setAttribute("aria-hidden", String(!ac));
  $("perde").hidden = !ac;
}

// Tam ekran bölümler (Öneriler, Geçmiş, Kılavuz) aynı anda yalnızca biri açık olur; "Ana sayfa" hepsini kapatır.
type Ekran = "oneriler" | "gecmis" | "kilavuz" | "hakkinda";
const ekranAcikMi = (e: Ekran) => e === "oneriler" ? !$("oneriler").hidden : e === "gecmis" ? gecmisAcikMi() : e === "hakkinda" ? hakkindaAcikMi() : kilavuzAcikMi();

function anaSayfa() {
  cekmece(false);
  kilavuzKapat();
  gecmisKapat();
  hakkindaKapat();
  $("oneriler").hidden = true;
}

/** Düğmeye basılınca: o bölüm açıksa ana sayfaya döner, değilse diğerlerini kapatıp onu açar. */
function ekranAc(e: Ekran) {
  if (ekranAcikMi(e)) { anaSayfa(); return; }
  anaSayfa();
  if (e === "oneriler") void onerileriGoster();
  else if (e === "gecmis") gecmisAc();
  else if (e === "hakkinda") hakkindaAc();
  else kilavuzAc();
}

function secileniGuncelle() {
  const acik: Ekran | "ana" | "" = !$("kurulum").hidden ? "" : (["kilavuz", "gecmis", "oneriler", "hakkinda"] as Ekran[]).find(ekranAcikMi) ?? "ana";
  for (const [ad, id] of [["ana", "b-ana"], ["oneriler", "b-oneriler"], ["gecmis", "b-gecmis"], ["kilavuz", "b-kilavuz"], ["hakkinda", "b-hakkinda"]])
    $(id).classList.toggle("secili", acik === ad);
}

function olaylariBagla() {
  $("b-kucult").onclick = () => void pencere.minimize();
  $("b-buyut").onclick = () => void pencere.toggleMaximize();
  $("b-kapat").onclick = () => void pencere.close();
  $("b-ayarlar").onclick = () => cekmece(true);
  $("b-mini").onclick = () => void kontrolGosterGizle();
  $("b-ana").onclick = anaSayfa;
  $("b-oneriler").onclick = () => ekranAc("oneriler");
  $("b-hakkinda").onclick = () => ekranAc("hakkinda");
  hakkindaKur(depo, kaydet, () => { if (calisiyor) baslatDurdur(); });
  $("oneri-kapat").onclick = () => { $("oneriler").hidden = true; };
  // açık ekranın düğmesi vurgulanır (ekranlar nereden açılıp kapanırsa kapansın)
  const izle = new MutationObserver(secileniGuncelle);
  for (const id of ["oneriler", "gp", "kilavuz", "kurulum", "hakkinda"]) izle.observe($(id), { attributes: true, attributeFilter: ["hidden"] });
  secileniGuncelle();
  $("b-cevrimdisi").onclick = cevrimdisiDegistir;
  // ilk açılışta kılavuz açıksa öneriler kılavuz kapanınca gösterilir
  new MutationObserver(() => {
    if (oneriBekliyor && !kilavuzAcikMi()) { oneriBekliyor = false; void oneriKontrol(); }
  }).observe($("kilavuz"), { attributes: true, attributeFilter: ["hidden"] });
  $("b-cekmece-kapat").onclick = () => cekmece(false);
  $("perde").onclick = () => cekmece(false);
  $("b-kilavuz").onclick = () => ekranAc("kilavuz");
  kilavuzKur();
  $("b-gecmis").onclick = () => ekranAc("gecmis");
  gecmisKur((metin, tur) => durumYaz(metin, tur));
  const gk = $<HTMLInputElement>("a-gecmis");
  gk.checked = depo.gecmisKaydet;
  gk.onchange = () => { depo.gecmisKaydet = gk.checked; kaydet(); };
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape") {
      if (!$("gp-onay").hidden) { $("gp-onay").hidden = true; return; }  // önce silme onayı kapanır
      anaSayfa();
    }
    if (e.key === "F1") { e.preventDefault(); if (!kilavuzAcikMi()) ekranAc("kilavuz"); }
  });

  $("b-baslat").onclick = baslatDurdur;
  $("b-serit").onclick = () => void seritGosterGizle();
  $("b-gecir").onclick = () => kisayolEylemi("gecir");
  $("b-duraklat").onclick = () => { if (calisiyor) kisayolEylemi("duraklat"); };
  $("s-konusma").addEventListener("change", ayarDegisti);
  $("s-hedef").addEventListener("change", ayarDegisti);
  // yalnızca kullanıcı seçince ayar değişir (liste dil değişiminde yeniden doldurulur)
  $("s-kmr-model").addEventListener("change", () => {
    const secim = $<HTMLSelectElement>("s-kmr-model").value;
    depo.motor.kurmanci_kaliteli = secim === "buyuk";
    depo.motor.kurmanci_omni = secim === "orta";
    ayarDegisti();
  });
  $("b-omni-indir").onclick = () => void kaliteliKur("omni");
  $("b-kaliteli-indir").onclick = () => void kaliteliKur("kaliteli");
  $("b-hiz-indir").onclick = () => void kaliteliKur(depo.motor.konusma_dili === "kmr" ? "hizlandirma_kmr" : "hizlandirma");
  $("k-gizle").onclick = () => {
    $("kurulum").hidden = true;
    const tur = kurulanPaket;
    durumYaz(() => t("paket.arka", { p: t(PAKETLER[tur].baslik) }), "yukleniyor");
  };
  // durum satırına tıklayınca süren indirmenin ilerleme ekranı yeniden açılır
  $("durum").onclick = () => { if (kaliteliKuruluyor) $("kurulum").hidden = false; };
  $("b-degistir").onclick = () => {
    const k = $<HTMLSelectElement>("s-konusma"), h = $<HTMLSelectElement>("s-hedef");
    [k.value, h.value] = [h.value, k.value];
    ayarDegisti();
  };

  liste().addEventListener("scroll", () => { if (sondaMi()) { yeniSayisi = 0; $("b-yeni").hidden = true; } });
  $("b-yeni").onclick = () => { liste().scrollTop = liste().scrollHeight; };
  $("b-kopyala").onclick = async () => {
    await navigator.clipboard.writeText(gecmisMetin.join("\n"));
    const b = $("b-kopyala"); b.classList.add("tamam"); setTimeout(() => b.classList.remove("tamam"), 1200);
  };
  $("b-indir").onclick = async () => {
    if (!gecmisMetin.length) { durumYaz(() => t("gecmis.yok"), "hazir"); return; }
    const z = new Date(), iki = (n: number) => String(n).padStart(2, "0");
    // yerel saat (toISOString UTC verir: Türkiye'de 3 saat geri çıkıyordu)
    const ad = `toplanti_${z.getFullYear()}-${iki(z.getMonth() + 1)}-${iki(z.getDate())}_${iki(z.getHours())}-${iki(z.getMinutes())}.txt`;
    try {
      const kaydedilen = await invoke<string | null>("gecmis_kaydet", {
        metin: gecmisMetin.join("\n") + "\n", ad, baslik: t("gecmis.kaydet.baslik"), filtre: t("gecmis.kaydet.filtre"),
      });
      if (kaydedilen) durumYaz(() => t("gecmis.kaydedildi", { ad: kaydedilen }), "hazir");
    } catch (e) {
      durumYaz(`⚠ ${e}`, "hata");
    }
  };
  $("b-temizle").onclick = () => {
    gecmisMetin.length = 0;
    liste().innerHTML = "";
    const b = document.createElement("div"); b.className = "bos"; b.dataset.t = "gecmis.temizlendi";
    b.textContent = t("gecmis.temizlendi"); liste().appendChild(b);
  };
}

// ---------- açılış ----------
// ---------- program dili ----------
function dilSecicileriKur() {
  for (const id of ["ayar-dil", "kurulum-dil"]) {
    const kap = $(id);
    kap.innerHTML = "";
    for (const d of ARAYUZ_DILLERI) {
      const b = document.createElement("button");
      b.className = "dil-dugme" + (d.kod === depo.arayuzDili ? " secili" : "");
      b.textContent = d.ad;
      b.setAttribute("aria-pressed", String(d.kod === depo.arayuzDili));
      b.onclick = () => arayuzDiliDegistir(d.kod);
      kap.appendChild(b);
    }
  }
}

/** Program dilini değiştirir: bütün metinler, kontrol çubuğu, altyazı şeridi ve kılavuz anında yeni dile geçer. */
function arayuzDiliDegistir(d: ArayuzDili) {
  depo.arayuzDili = d;
  kaydet();
  arayuzDiliAyarla(d);
  kilavuzDiliAyarla(d);
  sayfayiCevir();
  dilSecicileriKur();
  temaKutulariniKur();
  gorunumFormu();
  baslatDugmesi();
  cevrimdisiDugmesi();
  if (diller.length) formuDoldur();
  if (!$("oneriler").hidden) void onerileriGoster();
  gecmisDilDegisti();
  hakkindaDilDegisti();
  if (ilkKurulumSuruyor) { $("k-baslik").textContent = t("k.baslik"); $("k-aciklama").textContent = t("k.aciklama"); }
  else if (kaliteliKuruluyor) { $("k-baslik").textContent = t(PAKETLER[kurulanPaket].baslik); $("k-aciklama").textContent = t("k.paket.aciklama"); }
  if (!$("on-orijinal").textContent) $("on-ceviri").textContent = t("onizleme.bos");
  if (motorYolu) $("motor-yolu").textContent = t("a.klasor", { y: motorYolu });
  durumYaz(sonDurum.metin, sonDurum.tur);
  void emitTo("altyazi", "arayuz-dili", { dil: d });
  kontrolDurumGonder();
}
let motorYolu = "";

async function ac() {
  await ayarlariDosyadanAl();
  arayuzDiliAyarla(depo.arayuzDili);
  // diğer pencereler eski localStorage ile açılmış olabilir: dosyadaki dil onlara da bildirilir
  void emitTo("altyazi", "arayuz-dili", { dil: depo.arayuzDili });
  kilavuzDiliAyarla(depo.arayuzDili);
  sayfayiCevir();
  dilSecicileriKur();
  $("on-ceviri").textContent = t("onizleme.bos");
  cevrimdisiDugmesi();
  durumYaz(() => t("d.aciliyor"), "bos");
  temaKutulariniKur();
  temaSec(depo.tema);
  gorunumFormu();
  olaylariBagla();
  baslatDugmesi();
  getVersion().then((s) => { $("surum").textContent = `· v${s}`; }).catch(() => {});
  await motorDinle();
  await kisayollariKur();
  try {
    await kurulumuTamamla();
    kilavuzIlkKez(); // yeni kullanıcıya kılavuz bir kez kendiliğinden açılır
    const yol = await invoke<string>("motor_hazirla");
    motorYolu = yol;
    $("motor-yolu").textContent = t("a.klasor", { y: yol });
    durumYaz(() => t("d.motor"), "yukleniyor");
    void motoraGonder("bilgi"); // motor zaten çalışıyorsa dil listesini yeniden iste
  } catch (e) {
    durumYaz(`⚠ ${e}`, "hata");
  }
}

void ac();
