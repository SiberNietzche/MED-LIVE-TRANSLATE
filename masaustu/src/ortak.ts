import { type ArayuzDili, gecerliDil, varsayilanArayuzDili } from "./dil";
// Ana pencere ve altyazı şeridinin paylaştığı tipler, temalar ve ayar deposu.

export interface MotorAyarlari {
  konusma_dili: string;  // kmr | tr | en | ar
  hedef_dil: string;
  kurmanci_kaliteli: boolean; // büyük Kurmancî modeli (kuruluysa)
  kurmanci_omni: boolean;     // deneysel Omnilingual Kurmancî modeli (kuruluysa; kaliteliden önce gelir)
  cevrimdisi: boolean;        // çeviri yalnızca bu bilgisayarda (internete metin gönderilmez)
}

export interface DilBilgisi { kod: string; ad: string; rtl: boolean; }

export interface Gorunum {
  yaziBoyutu: number;   // px
  satirSayisi: number;  // aynı anda gösterilen cümle
  opaklik: number;      // 0.3–1 şerit arka planı
  orijinal: boolean;    // çevirinin altında orijinali göster
  taslak: boolean;      // konuşurken taslak metni göster
}

export interface Tema {
  ad: string;
  acik: boolean;
  arka: string;
  panel: string;
  panelKoyu: string;
  kenar: string;
  metin: string;
  soluk: string;
  vurgu: string;
  vurgu2: string;
  basari: string;
  uyari: string;
  tehlike: string;
  serit: string; // altyazı şeridi zemin rengi (r,g,b)
}

export const TEMALAR: Record<string, Tema> = {
  nebula: {
    ad: "Nebula", acik: false, arka: "#07060f", panel: "rgba(24, 20, 48, 0.55)",
    panelKoyu: "rgba(14, 11, 30, 0.72)", kenar: "rgba(167, 139, 250, 0.20)", metin: "#efeaff",
    soluk: "#a39cd0", vurgu: "#8b5cf6", vurgu2: "#22d3ee", basari: "#34d399", uyari: "#fbbf24",
    tehlike: "#fb7185", serit: "14, 11, 30",
  },
  aurora: {
    ad: "Aurora", acik: false, arka: "#03121a", panel: "rgba(10, 38, 48, 0.55)",
    panelKoyu: "rgba(4, 22, 30, 0.75)", kenar: "rgba(45, 212, 191, 0.22)", metin: "#e6fbf6",
    soluk: "#8ec5bd", vurgu: "#10b981", vurgu2: "#38bdf8", basari: "#4ade80", uyari: "#facc15",
    tehlike: "#fb7185", serit: "4, 22, 30",
  },
  kor: {
    ad: "Kor", acik: false, arka: "#110706", panel: "rgba(48, 20, 14, 0.55)",
    panelKoyu: "rgba(28, 10, 8, 0.75)", kenar: "rgba(251, 146, 60, 0.22)", metin: "#fff1ea",
    soluk: "#d4a493", vurgu: "#f97316", vurgu2: "#f43f5e", basari: "#a3e635", uyari: "#fde047",
    tehlike: "#f43f5e", serit: "28, 10, 8",
  },
  altin: {
    ad: "Gece Altını", acik: false, arka: "#09090b", panel: "rgba(32, 30, 26, 0.55)",
    panelKoyu: "rgba(18, 17, 15, 0.78)", kenar: "rgba(234, 179, 8, 0.22)", metin: "#fafaf9",
    soluk: "#b8b3a6", vurgu: "#eab308", vurgu2: "#e7e5e4", basari: "#84cc16", uyari: "#f59e0b",
    tehlike: "#ef4444", serit: "18, 17, 15",
  },
  buzul: {
    ad: "Buzul", acik: true, arka: "#e9f0fa", panel: "rgba(255, 255, 255, 0.62)",
    panelKoyu: "rgba(255, 255, 255, 0.85)", kenar: "rgba(37, 99, 235, 0.18)", metin: "#0f172a",
    soluk: "#51607a", vurgu: "#2563eb", vurgu2: "#06b6d4", basari: "#059669", uyari: "#d97706",
    tehlike: "#e11d48", serit: "15, 23, 42",
  },
};

export const VARSAYILAN_MOTOR: MotorAyarlari = { konusma_dili: "kmr", hedef_dil: "tr", kurmanci_kaliteli: false, kurmanci_omni: false, cevrimdisi: false };
const DIL_KODLARI = ["kmr", "tr", "en", "ar"];

export const VARSAYILAN_GORUNUM: Gorunum = {
  yaziBoyutu: 30, satirSayisi: 2, opaklik: 0.82, orijinal: true, taslak: false,
};

export interface Depo {
  motor: MotorAyarlari;
  gorunum: Gorunum;
  tema: string;
  oneriGoruldu: boolean;  // "Bu bilgisayar için öneriler" ekranı bir kez kendiliğinden açıldı
  arayuzDili: ArayuzDili; // programın kendi dili (menüler, düğmeler, kılavuz): tr | en | kmr
  gecmisKaydet: boolean;  // konuşmalar bilgisayarda saklansın (Geçmiş ekranı); varsayılan açık
  guncellemeOtomatik: boolean; // GitHub'dan yeni sürüm kendiliğinden denetlensin (varsayılan açık)
  sonDenetim: number;          // son güncelleme denetimi (ms)
  bildirilenSurum: string;     // masaüstü bildirimi gönderilen son sürüm (her sürüm için bir kez)
}

const ANAHTAR = "canli-altyazi-ayarlar-v2";

function motorAyari(eski: any): MotorAyarlari {
  // eski sürümden kalan ayar (ör. "auto", "ckb") geçerli değilse varsayılana dön
  const k = DIL_KODLARI.includes(eski?.konusma_dili) ? eski.konusma_dili : VARSAYILAN_MOTOR.konusma_dili;
  const h = DIL_KODLARI.includes(eski?.hedef_dil) ? eski.hedef_dil : VARSAYILAN_MOTOR.hedef_dil;
  return { konusma_dili: k, hedef_dil: h, kurmanci_kaliteli: eski?.kurmanci_kaliteli === true,
    kurmanci_omni: eski?.kurmanci_omni === true,
    cevrimdisi: eski?.cevrimdisi === true };
}

export function depoOku(ham?: string): Depo {
  try {
    const d = JSON.parse(ham ?? (localStorage.getItem(ANAHTAR) || "{}"));
    return {
      motor: motorAyari(d.motor),
      gorunum: { ...VARSAYILAN_GORUNUM, ...(d.gorunum || {}) },
      tema: d.tema && TEMALAR[d.tema] ? d.tema : "nebula",
      oneriGoruldu: d.oneriGoruldu === true,
      arayuzDili: gecerliDil(d.arayuzDili) ? d.arayuzDili : varsayilanArayuzDili(),
      gecmisKaydet: d.gecmisKaydet !== false,
      guncellemeOtomatik: d.guncellemeOtomatik !== false,
      sonDenetim: typeof d.sonDenetim === "number" ? d.sonDenetim : 0,
      bildirilenSurum: typeof d.bildirilenSurum === "string" ? d.bildirilenSurum : "",
    };
  } catch {
    return { motor: { ...VARSAYILAN_MOTOR }, gorunum: { ...VARSAYILAN_GORUNUM }, tema: "nebula", oneriGoruldu: false,
      arayuzDili: varsayilanArayuzDili(), gecmisKaydet: true, guncellemeOtomatik: true, sonDenetim: 0, bildirilenSurum: "" };
  }
}

export function depoYaz(d: Depo): void {
  try {
    localStorage.setItem(ANAHTAR, JSON.stringify(d));
  } catch {
    /* depolama kapalıysa ayarlar yalnızca bu oturumda kalır */
  }
}

export function temaUygula(tema: Tema, kok: HTMLElement = document.documentElement): void {
  const s = kok.style;
  s.setProperty("--arka", tema.arka);
  s.setProperty("--panel", tema.panel);
  s.setProperty("--panel-koyu", tema.panelKoyu);
  s.setProperty("--kenar", tema.kenar);
  s.setProperty("--metin", tema.metin);
  s.setProperty("--soluk", tema.soluk);
  s.setProperty("--vurgu", tema.vurgu);
  s.setProperty("--vurgu2", tema.vurgu2);
  s.setProperty("--basari", tema.basari);
  s.setProperty("--uyari", tema.uyari);
  s.setProperty("--tehlike", tema.tehlike);
  s.setProperty("--serit", tema.serit);
  kok.dataset.acik = tema.acik ? "1" : "0";
}

// Motor olayları
export interface AltyaziOlayi {
  tur: "ara" | "son" | "sil";
  id: number;
  kaynak: "bilgisayar" | "mikrofon";
  orijinal: string;
  ceviri: string;
  dil: string | null;
  rtl: boolean;
  hedef_rtl: boolean;
}

export const okumaSuresi = (metin: string) => Math.max(2.5, Math.min(7, 1 + metin.length / 15)) * 1000;
