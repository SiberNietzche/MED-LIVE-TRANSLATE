// Kalıcı konuşma geçmişi: her Başlat → Durdur oturumu bilgisayarda ayrı bir kayıt olarak saklanır
// (Rust: gecmis_ekle / gecmis_liste / gecmis_oku / gecmis_sil). Başlıktaki "Geçmiş" düğmesi eski
// konuşmaları listeler; her biri okunabilir, metin dosyası olarak indirilebilir ya da silinebilir.
import { invoke } from "@tauri-apps/api/core";
import { dilAdiCevir, saatBicimi, t, yerel } from "./dil";
import type { AltyaziOlayi } from "./ortak";

interface Ozet { oturum: string; adet: number; ilk: number | null; son: number | null; ciftler: string[] }
interface Kayit { z: number; dil: string; hedef: string; o: string; c: string; rtl?: boolean; hrtl?: boolean }

const $ = <T extends HTMLElement = HTMLElement>(id: string) => document.getElementById(id) as T;

let durumBildir: (metin: () => string, tur: "hazir" | "hata") => void = () => {};
let acikMi = () => !$("gp").hidden;
let oturum: string | null = null;     // şu an kaydedilen oturum
let secili: string | null = null;     // panelde gösterilen oturum
let ozetler: Ozet[] = [];
let yenileZamanlayici: number | undefined;

const iki = (n: number) => String(n).padStart(2, "0");
const adet = (n: number) => t(n === 1 ? "gp.adet.1" : "gp.adet", { n });
/** "14:03–14:45"; aynı dakikada bittiyse yalnızca "14:03" */
const aralik = (ilk: number, son: number) => (saat(ilk) === saat(son) ? saat(ilk) : `${saat(ilk)}–${saat(son)}`);

/** Başlat'a basılınca yeni oturum (yerel saatle adlandırılır; dosya ilk cümlede oluşur). */
export function oturumBaslat() {
  const z = new Date();
  oturum = `${z.getFullYear()}-${iki(z.getMonth() + 1)}-${iki(z.getDate())}_${iki(z.getHours())}-${iki(z.getMinutes())}-${iki(z.getSeconds())}`;
}

export function oturumBitir() {
  oturum = null;
}

/** Tanınan her cümle diske eklenir (kaydetme kapalıysa çağrılmaz). */
export function kayitEkle(o: AltyaziOlayi, hedef: string) {
  if (!oturum) oturumBaslat();
  const k: Kayit = { z: Date.now(), dil: o.dil ?? "", hedef, o: o.orijinal, c: o.ceviri, rtl: o.rtl, hrtl: o.hedef_rtl };
  const ad = oturum!;
  void invoke("gecmis_ekle", { oturum: ad, satir: JSON.stringify(k) }).catch((e) => console.warn("geçmiş yazılamadı", e));
  if (acikMi()) {
    // açık panel canlı güncellenir (her cümlede değil, en çok 1,5 sn'de bir)
    clearTimeout(yenileZamanlayici);
    yenileZamanlayici = window.setTimeout(() => void listeyiYenile(), 1500);
    if (secili === ad) kayitCiz($("gp-kayitlar"), k);
  }
}

// ---------- biçimlendirme ----------
const tarih = (ms: number) => new Date(ms).toLocaleDateString(yerel(), { day: "numeric", month: "long", year: "numeric" });
const saat = (ms: number, sn = false) =>
  new Date(ms).toLocaleTimeString(saatBicimi(), { hour: "2-digit", minute: "2-digit", ...(sn ? { second: "2-digit" } : {}) });

/** "tr>kmr" → "Türkçe → Kurmancî"; hedef dil aynıysa (çeviri yok) yalnızca dil adı. */
function ciftAdi(c: string) {
  const [k, h] = c.split(">");
  return !h || h === k ? dilAdiCevir(k) : `${dilAdiCevir(k)} → ${dilAdiCevir(h)}`;
}

function sureMetni(o: Ozet) {
  if (o.ilk == null || o.son == null) return "";
  const dk = Math.round((o.son - o.ilk) / 60000);
  return dk < 1 ? t("gp.sure.az") : dk < 60 ? t("sure.dk", { n: dk }) : t("sure.sa", { s: Math.floor(dk / 60), d: dk % 60 });
}

function baslikMetni(o: Ozet) {
  if (o.ilk == null) return o.oturum;
  return `${tarih(o.ilk)} · ${aralik(o.ilk, o.son ?? o.ilk)}`;
}

function kayitlariAyristir(ham: string): Kayit[] {
  const liste: Kayit[] = [];
  for (const s of ham.split("\n")) {
    if (!s.trim()) continue;
    try { liste.push(JSON.parse(s)); } catch { /* yarım kalmış satır atlanır */ }
  }
  return liste;
}

/** İndirilecek metin: başlık + her cümle saatiyle (orijinal ve çevirisi). */
function metneCevir(o: Ozet, kayitlar: Kayit[]) {
  const satirlar = [`MED LIVE TRANSLATE — ${baslikMetni(o)}`, `${o.ciftler.map(ciftAdi).join(", ")} · ${adet(o.adet)}`, ""];
  for (const k of kayitlar) {
    satirlar.push(`[${saat(k.z, true)}] (${dilAdiCevir(k.dil)}): ${k.o}`);
    if (k.c) satirlar.push(`           → ${k.c}`);
  }
  return satirlar.join("\n");
}

async function dosyaKaydet(metin: string, ad: string) {
  try {
    const kaydedilen = await invoke<string | null>("gecmis_kaydet", {
      metin: metin + "\n", ad, baslik: t("gecmis.kaydet.baslik"), filtre: t("gecmis.kaydet.filtre"),
    });
    if (kaydedilen) durumBildir(() => t("gecmis.kaydedildi", { ad: kaydedilen }), "hazir");
  } catch (e) {
    durumBildir(() => `⚠ ${e}`, "hata");
  }
}

// ---------- panel ----------
function kayitCiz(kap: HTMLElement, k: Kayit) {
  kap.querySelector(".bos")?.remove();
  const el = document.createElement("div");
  el.className = "kayit";
  const ust = document.createElement("div");
  ust.className = "kayit-ust";
  const s = document.createElement("span"); s.textContent = saat(k.z, true);
  const e = document.createElement("span"); e.className = "etiket"; e.textContent = dilAdiCevir(k.dil);
  ust.append(s, e);
  const ana = document.createElement("div");
  ana.className = "kayit-ceviri" + ((k.c ? k.hrtl : k.rtl) ? " sag-hiza" : "");
  ana.textContent = k.c || k.o;
  el.append(ust, ana);
  if (k.c) {
    const alt = document.createElement("div");
    alt.className = "kayit-orijinal" + (k.rtl ? " sag-hiza" : "");
    alt.textContent = k.o;
    el.appendChild(alt);
  }
  kap.appendChild(el);
}

function bosYaz(kap: HTMLElement, anahtar: string) {
  kap.innerHTML = "";
  const b = document.createElement("div");
  b.className = "bos";
  b.textContent = t(anahtar);
  kap.appendChild(b);
}

async function listeyiYenile() {
  ozetler = await invoke<Ozet[]>("gecmis_liste").catch(() => []);
  const kap = $("gp-liste");
  kap.innerHTML = "";
  $("gp-alt").textContent = ozetler.length ? t("gp.alt", { n: ozetler.length }) : "";
  $<HTMLButtonElement>("gp-tum-indir").disabled = !ozetler.length;
  $<HTMLButtonElement>("gp-tum-sil").disabled = !ozetler.length;
  if (!ozetler.length) {
    bosYaz(kap, "gp.hic");
    secili = null;
    secimGoster();
    return;
  }
  let gun = "";
  for (const o of ozetler) {
    const g = o.ilk != null ? tarih(o.ilk) : "";
    if (g !== gun) {
      gun = g;
      const b = document.createElement("div"); b.className = "kv-nav-baslik gp-gun"; b.textContent = g;
      kap.appendChild(b);
    }
    const d = document.createElement("button");
    d.className = "gp-oge" + (o.oturum === secili ? " secili" : "");
    const b1 = document.createElement("b");
    b1.textContent = o.ilk != null ? aralik(o.ilk, o.son ?? o.ilk) : o.oturum;
    if (o.oturum === oturum) { const c = document.createElement("i"); c.className = "gp-canli"; c.textContent = t("gp.suruyor"); b1.appendChild(c); }
    const s1 = document.createElement("span"); s1.textContent = o.ciftler.map(ciftAdi).join(", ");
    const s2 = document.createElement("span"); s2.textContent = [adet(o.adet), sureMetni(o)].filter(Boolean).join(" · ");
    d.append(b1, s1, s2);
    d.onclick = () => void sec(o.oturum);
    kap.appendChild(d);
  }
  if (!secili || !ozetler.some((o) => o.oturum === secili)) await sec(ozetler[0].oturum);
  else secimGoster();
}

function secimGoster() {
  const o = ozetler.find((x) => x.oturum === secili);
  $("gp-secili").textContent = o ? baslikMetni(o) : "";
  $("gp-secili-alt").textContent = o ? `${o.ciftler.map(ciftAdi).join(", ")} · ${adet(o.adet)}` : "";
  $("gp-icerik-ust").hidden = !o;
  document.querySelectorAll<HTMLElement>(".gp-oge").forEach((d, i) => d.classList.toggle("secili", ozetler[i]?.oturum === secili));
  if (!o) bosYaz($("gp-kayitlar"), ozetler.length ? "gp.sec" : "gp.hic.uzun");
}

async function sec(ad: string) {
  secili = ad;
  secimGoster();
  const kap = $("gp-kayitlar");
  const ham = await invoke<string>("gecmis_oku", { oturum: ad }).catch(() => "");
  if (secili !== ad) return;  // bu arada başka biri seçildi
  kap.innerHTML = "";
  const kayitlar = kayitlariAyristir(ham);
  if (!kayitlar.length) bosYaz(kap, "gp.okunamadi");
  for (const k of kayitlar) kayitCiz(kap, k);
  kap.scrollTop = 0;
}

async function seciliyiIndir() {
  const o = ozetler.find((x) => x.oturum === secili);
  if (!o) return;
  const kayitlar = kayitlariAyristir(await invoke<string>("gecmis_oku", { oturum: o.oturum }).catch(() => ""));
  await dosyaKaydet(metneCevir(o, kayitlar), `konusma_${o.oturum}.txt`);
}

async function tumunuIndir() {
  if (!ozetler.length) return;
  const parcalar: string[] = [];
  // dosyada eskiden yeniye sıralı
  for (const o of [...ozetler].reverse()) {
    const kayitlar = kayitlariAyristir(await invoke<string>("gecmis_oku", { oturum: o.oturum }).catch(() => ""));
    parcalar.push(metneCevir(o, kayitlar));
  }
  const z = new Date();
  await dosyaKaydet(parcalar.join("\n\n" + "=".repeat(60) + "\n\n"),
    `konusma_gecmisi_${z.getFullYear()}-${iki(z.getMonth() + 1)}-${iki(z.getDate())}.txt`);
}

/** Silme onayı panelin içinde sorulur (tarayıcı onay pencereleri programı kilitleyebiliyor). */
function onayIste(metin: string, evet: string, eylem: () => Promise<void>) {
  $("gp-onay-metin").textContent = metin;
  $("gp-onay-evet").textContent = evet;
  $("gp-onay").hidden = false;
  $("gp-onay-evet").onclick = async () => { $("gp-onay").hidden = true; await eylem(); };
  $("gp-onay-hayir").onclick = () => { $("gp-onay").hidden = true; };
  $("gp-onay-hayir").focus();
}

async function sil(ad: string | null) {
  try {
    const n = await invoke<number>("gecmis_sil", { oturum: ad });
    if (ad === null || ad === oturum) oturumBitirmedenYeniDosya();
    durumBildir(() => (ad === null ? t("gp.silindi.tum", { n }) : t("gp.silindi")), "hazir");
  } catch (e) {
    durumBildir(() => `⚠ ${e}`, "hata");
  }
  if (ad === null || ad === secili) secili = null;
  await listeyiYenile();
}

/** Kaydı süren oturum silindiyse dinleme sürerken gelen cümleler yeni bir kayda yazılsın. */
function oturumBitirmedenYeniDosya() {
  if (oturum) oturumBaslat();
}

// ---------- dışarıya açılanlar ----------
export function gecmisKur(bildir: typeof durumBildir) {
  durumBildir = bildir;
  acikMi = () => !$("gp").hidden;
  $("gp-kapat").onclick = gecmisKapat;
  $("gp-indir").onclick = () => void seciliyiIndir();
  $("gp-tum-indir").onclick = () => void tumunuIndir();
  $("gp-sil").onclick = () => {
    const o = ozetler.find((x) => x.oturum === secili);
    if (o) onayIste(t("gp.onay.bir", { b: baslikMetni(o) }), t("gp.sil"), () => sil(o.oturum));
  };
  $("gp-tum-sil").onclick = () =>
    onayIste(t("gp.onay.tum", { n: ozetler.length }), t("gp.tum.sil"), () => sil(null));
}

export function gecmisAc() {
  $("gp").hidden = false;
  $("gp-onay").hidden = true;
  void listeyiYenile();
}

export function gecmisKapat() {
  $("gp").hidden = true;
}

export const gecmisAcikMi = () => !$("gp").hidden;

/** Program dili değişince açık panel yeni dilde yeniden çizilir. */
export function gecmisDilDegisti() {
  if (gecmisAcikMi()) { void listeyiYenile(); if (secili) void sec(secili); }
}
