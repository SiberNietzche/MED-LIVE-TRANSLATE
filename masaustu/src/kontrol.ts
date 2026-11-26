// Küçük kontrol çubuğu: konferans programının üstünde durur; başlat/durdur ve dil seçimi.
// Motorla doğrudan konuşmaz: komutları ana pencereye iletir, durumu ana pencereden alır.
// Böylece iki pencere her zaman aynı durumu gösterir.
import { emitTo, listen } from "@tauri-apps/api/event";
import { PhysicalPosition, currentMonitor, getCurrentWindow } from "@tauri-apps/api/window";
import { TEMALAR, depoOku, temaUygula, type DilBilgisi } from "./ortak";
import { arayuzDili, arayuzDiliAyarla, dilAdiCevir, gecerliDil, sayfayiCevir, t } from "./dil";

interface Durum {
  calisiyor: boolean; hazir: boolean; duraklatildi: boolean; durum: string; durumTur: string;
  diller: DilBilgisi[]; konusma: string; hedef: string; tema: string; arayuzDili?: string;
}

const pencere = getCurrentWindow();
const $ = <T extends HTMLElement = HTMLElement>(id: string) => document.getElementById(id) as T;
const konusma = $<HTMLSelectElement>("s-konusma");
const hedef = $<HTMLSelectElement>("s-hedef");
let dilImzasi = "";
let temaAdi = "";

const anaya = (eylem: string, ekle: Record<string, unknown> = {}) =>
  void emitTo("main", "kontrol-komut", { eylem, ...ekle });

function secenekler(sel: HTMLSelectElement, diller: DilBilgisi[]) {
  sel.innerHTML = "";
  for (const d of diller) {
    const o = document.createElement("option");
    o.value = d.kod;
    o.textContent = dilAdiCevir(d.kod);
    sel.appendChild(o);
  }
}

function uygula(d: Durum) {
  if (d.tema !== temaAdi && TEMALAR[d.tema]) { temaAdi = d.tema; temaUygula(TEMALAR[d.tema]); }

  // program dili ana pencereden gelir: değişince bütün metinler yeniden yazılır
  if (gecerliDil(d.arayuzDili) && d.arayuzDili !== arayuzDili()) {
    arayuzDiliAyarla(d.arayuzDili);
    sayfayiCevir();
    dilImzasi = "";
  }
  // dil listesi yalnızca değişince yeniden kurulur (açık bir listeyi kapatmasın)
  const imza = d.diller.map((x) => x.kod).join(",") + "|" + arayuzDili();
  if (imza && imza !== dilImzasi) {
    dilImzasi = imza;
    secenekler(konusma, d.diller);
    secenekler(hedef, d.diller);
  }
  if (document.activeElement !== konusma) konusma.value = d.konusma;
  if (document.activeElement !== hedef) hedef.value = d.hedef;

  const b = $("b-baslat");
  b.classList.toggle("calisiyor", d.calisiyor);
  b.dataset.tur = d.durumTur;
  $("t-baslat").textContent = t(d.calisiyor ? "durdur" : "baslat");
  b.title = d.durum || t(d.calisiyor ? "durdur" : "baslat");

  const p = $("b-duraklat");
  p.classList.toggle("etkin", d.duraklatildi);
  p.title = t(d.duraklatildi ? "kc.devam" : "kc.duraklat");
}

// İlk açılışta ekranın üst ortasına yerleş (sonra konumu pencere durumu eklentisi hatırlar)
async function ilkKonum() {
  const ANAHTAR = "kontrol-konumlandi-v1";
  try { if (localStorage.getItem(ANAHTAR)) return; } catch { /* depolama yoksa her açılışta ortala */ }
  const ekran = await currentMonitor();
  if (ekran) {
    const boyut = await pencere.outerSize();
    await pencere.setPosition(new PhysicalPosition(
      ekran.position.x + Math.round((ekran.size.width - boyut.width) / 2),
      ekran.position.y + Math.round(12 * ekran.scaleFactor)));
  }
  try { localStorage.setItem(ANAHTAR, "1"); } catch { /* yok say */ }
}

async function ac() {
  const d = depoOku();
  arayuzDiliAyarla(d.arayuzDili);
  sayfayiCevir();
  $("t-baslat").textContent = t("baslat");
  temaAdi = d.tema;
  temaUygula(TEMALAR[d.tema] ?? TEMALAR.nebula);
  await ilkKonum();

  $("b-baslat").onclick = () => anaya("baslat");
  $("b-duraklat").onclick = () => anaya("duraklat");
  $("b-serit").onclick = () => anaya("serit");
  $("b-ana").onclick = () => anaya("ana");
  $("b-gizle").onclick = () => void pencere.hide();
  konusma.onchange = () => anaya("dil", { konusma: konusma.value });
  hedef.onchange = () => anaya("dil", { hedef: hedef.value });
  $("b-degistir").onclick = () => {
    anaya("dil", { konusma: hedef.value, hedef: konusma.value });
  };

  await listen<Durum>("kontrol-durum", ({ payload }) => uygula(payload));
  anaya("iste"); // ana pencereden güncel durumu iste
}

void ac();
