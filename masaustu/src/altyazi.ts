// Altyazı şeridi: kaymayan, okuma süresine uyan, fare üstündeyken donan altyazı.
import { emitTo, listen } from "@tauri-apps/api/event";
import { PhysicalPosition, PhysicalSize, currentMonitor, getCurrentWindow } from "@tauri-apps/api/window";
import { TEMALAR, depoOku, okumaSuresi, type AltyaziOlayi, type Gorunum } from "./ortak";
import { arayuzDiliAyarla, gecerliDil, motorMesaji, sayfayiCevir, t } from "./dil";

const pencere = getCurrentWindow();
const $ = (id: string) => document.getElementById(id)!;
const kutu = $("kutu"), icerik = $("icerik"), menu = $("menu");

const BIRIKME_SINIRI = 3;  // bu kadar cümle beklerse okuma süresi beklenmez
const ARALIK = 1200;       // iki cümlenin belirmesi arasında en az (ms)

let gorunum: Gorunum = depoOku().gorunum;
let bloklar: { o: AltyaziOlayi; t: number }[] = [];
let bekleyen: AltyaziOlayi[] = [];
let taslak: AltyaziOlayi | null = null;
let ikiKaynak = false;
let tiklamaGecir = false;
let degisti = true;
// gösterilecek bilgi: sözlük anahtarı (program dili değişince yeni dilde çizilir) ya da motordan gelen metin
let bilgi = "s.bilgi";

// ---------- görünüm ----------
function gorunumUygula(g: Gorunum, temaAdi: string) {
  gorunum = g;
  const t = TEMALAR[temaAdi] ?? TEMALAR.nebula;
  const s = document.documentElement.style;
  s.setProperty("--boyut", `${g.yaziBoyutu}px`);
  s.setProperty("--opaklik", String(g.opaklik));
  s.setProperty("--serit", t.serit);
  s.setProperty("--vurgu", t.vurgu);
  s.setProperty("--vurgu2", t.vurgu2);
  while (bloklar.length > g.satirSayisi) bloklar.shift();
  degisti = true;
}

// ---------- çizim ----------
function blokHtml(o: AltyaziOlayi, eski: boolean): HTMLElement {
  const el = document.createElement("div");
  el.className = "blok" + (eski ? " eski" : "");
  const ana = document.createElement("div");
  const anaRtl = o.ceviri ? o.hedef_rtl : o.rtl;
  ana.className = "ceviri" + (anaRtl ? " sag" : "") + (ikiKaynak && o.kaynak === "mikrofon" ? " siz" : "");
  ana.textContent = (ikiKaynak && o.kaynak === "mikrofon" ? t("s.siz") : "") + (o.ceviri || o.orijinal);
  el.appendChild(ana);
  if (o.ceviri && gorunum.orijinal) {
    const alt = document.createElement("div");
    alt.className = "orijinal" + (o.rtl ? " sag" : "");
    alt.textContent = o.orijinal;
    el.appendChild(alt);
  }
  return el;
}

const oncekiIdler = new Set<number>();
function ciz() {
  degisti = false;
  const yeniIcerik = document.createDocumentFragment();
  if (!bloklar.length && !taslak) {
    const b = document.createElement("div");
    b.className = "bilgi";
    b.textContent = bilgi.startsWith("s.") ? t(bilgi) : motorMesaji(bilgi);
    yeniIcerik.appendChild(b);
  }
  bloklar.forEach((b, i) => {
    const el = blokHtml(b.o, i < bloklar.length - 1);
    if (oncekiIdler.has(b.o.id)) el.style.animation = "none"; // yalnızca yeni cümle canlansın
    yeniIcerik.appendChild(el);
  });
  oncekiIdler.clear();
  bloklar.forEach((b) => oncekiIdler.add(b.o.id));
  if (taslak) {
    const el = document.createElement("div");
    if (gorunum.taslak) {
      el.className = "taslak" + (taslak.rtl ? " sag" : "");
      el.textContent = taslak.orijinal + " …";
    } else {
      el.className = "isaret";
      el.innerHTML = "<i></i><i></i><i></i><span>konuşuluyor</span>";
    }
    yeniIcerik.appendChild(el);
  }
  icerik.replaceChildren(yeniIcerik);
  void yuksekligiAyarla();
}

// Pencere yüksekliği içeriğe göre: alt kenar sabit, yukarı doğru büyür.
let ayarlaniyor = false;
async function yuksekligiAyarla() {
  if (ayarlaniyor) return;
  ayarlaniyor = true;
  try {
    const olcek = await pencere.scaleFactor();
    const menuH = menu.hidden ? 0 : menu.offsetHeight + 16;
    const gerekli = Math.ceil(Math.max(kutu.offsetHeight, menuH) * olcek);
    const boyut = await pencere.outerSize();
    if (Math.abs(boyut.height - gerekli) > 2) {
      const konum = await pencere.outerPosition();
      const alt = konum.y + boyut.height;
      await pencere.setSize(new PhysicalSize(boyut.width, gerekli));
      await pencere.setPosition(new PhysicalPosition(konum.x, alt - gerekli));
    }
  } finally {
    ayarlaniyor = false;
  }
}

// ---------- sıra ----------
function ilerleyebilir(simdi: number) {
  if (bekleyen.length >= BIRIKME_SINIRI) return true;
  if (bloklar.length && simdi - bloklar[bloklar.length - 1].t < ARALIK) return false;
  if (bloklar.length < gorunum.satirSayisi) return true;
  const enEski = bloklar[0];
  return simdi - enEski.t >= okumaSuresi(enEski.o.ceviri || enEski.o.orijinal);
}

function tik() {
  // fare üstündeyken de akış sürer (eskiden "okumak için" durduruluyordu: program donmuş gibi görünüyordu)
  const simdi = performance.now();
  while (bekleyen.length && ilerleyebilir(simdi)) {
    bloklar.push({ o: bekleyen.shift()!, t: simdi });
    while (bloklar.length > gorunum.satirSayisi) bloklar.shift();
    degisti = true;
  }
  if (degisti) ciz();
}

function olay(o: AltyaziOlayi) {
  if (o.tur === "ara") taslak = o;
  else if (o.tur === "sil") { if (taslak?.id === o.id) taslak = null; }
  else if (o.tur === "son") {
    if (taslak?.id === o.id) taslak = null;
    bekleyen.push(o);
  }
  degisti = true;
}

function temizle() {
  bloklar = []; bekleyen = []; taslak = null; degisti = true;
}

// ---------- etkileşim ----------
async function tiklamaGecirDegistir() {
  tiklamaGecir = !tiklamaGecir;
  await pencere.setIgnoreCursorEvents(tiklamaGecir);
  void emitTo("main", "altyazi-durum", { tiklama: tiklamaGecir });
}

function eylem(ad: string) {
  menu.hidden = true;
  if (ad === "temizle") temizle();
  else if (ad === "tiklama") void tiklamaGecirDegistir();
  else if (ad === "gizle") void pencere.hide();
  else if (ad === "buyut" || ad === "kucult") {
    gorunum.yaziBoyutu = Math.max(16, Math.min(64, gorunum.yaziBoyutu + (ad === "buyut" ? 2 : -2)));
    document.documentElement.style.setProperty("--boyut", `${gorunum.yaziBoyutu}px`);
    degisti = true;
  }
  void yuksekligiAyarla();
}

kutu.addEventListener("mousedown", (e) => {
  if (e.button !== 0 || e.target === $("tutamak")) return;
  menu.hidden = true;
  void pencere.startDragging();
});
$("tutamak").addEventListener("mousedown", (e) => {
  e.stopPropagation();
  void pencere.startResizeDragging("East");
});
document.addEventListener("contextmenu", (e) => {
  e.preventDefault();
  menu.hidden = false;
  menu.style.left = `${Math.min(e.clientX, window.innerWidth - 240)}px`;
  menu.style.top = "8px";
  void yuksekligiAyarla();
});
menu.addEventListener("click", (e) => {
  const d = (e.target as HTMLElement).closest<HTMLButtonElement>("button");
  if (d?.dataset.eylem) eylem(d.dataset.eylem);
});
document.addEventListener("mouseleave", () => { menu.hidden = true; void yuksekligiAyarla(); });
window.addEventListener("resize", () => { degisti = true; });

// ---------- açılış ----------
async function ilkKonum() {
  const ANAHTAR = "altyazi-konumlandi-v1";
  if (localStorage.getItem(ANAHTAR)) return;
  const ekran = await currentMonitor();
  if (ekran) {
    const g = Math.round(ekran.size.width * 0.62);
    const boyut = await pencere.outerSize();
    await pencere.setSize(new PhysicalSize(g, boyut.height));
    await pencere.setPosition(new PhysicalPosition(
      ekran.position.x + Math.round((ekran.size.width - g) / 2),
      ekran.position.y + ekran.size.height - boyut.height - Math.round(90 * ekran.scaleFactor)));
  }
  localStorage.setItem(ANAHTAR, "1");
}

async function ac() {
  const d = depoOku();
  arayuzDiliAyarla(d.arayuzDili);
  sayfayiCevir();
  gorunumUygula(d.gorunum, d.tema);
  ikiKaynak = false; // yalnızca bilgisayar sesi dinlenir
  await ilkKonum();
  await listen<Record<string, any>>("motor", ({ payload: o }) => {
    if (o.tur === "ara" || o.tur === "son" || o.tur === "sil") olay(o as AltyaziOlayi);
    else if (o.tur === "durum") {
      const m: string = o.metin;
      if (m === "Dinleniyor") { bilgi = "s.dinleniyor"; }
      else if (m === "Durduruldu") bilgi = "s.durduruldu";
      else if (m.startsWith("Hazır —")) bilgi = "s.hazir";
      else bilgi = m;
      if (!bloklar.length) degisti = true;
    }
  });
  await listen<{ gorunum: Gorunum; tema: string }>("gorunum", ({ payload }) => gorunumUygula(payload.gorunum, payload.tema));
  await listen<{ eylem: string }>("altyazi-komut", ({ payload }) => eylem(payload.eylem));
  await listen<{ dil: string }>("arayuz-dili", ({ payload }) => {
    if (!gecerliDil(payload.dil)) return;
    arayuzDiliAyarla(payload.dil);
    sayfayiCevir();
    degisti = true;
  });
  ciz();
  setInterval(tik, 100);
}

void ac();
