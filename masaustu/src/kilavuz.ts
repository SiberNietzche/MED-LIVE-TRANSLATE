// Kullanım kılavuzu: ana pencerenin içinde açılan, Türkçe / English / Kurmancî sekmeli rehber.
// Ekran görüntüleri public/kilavuz/ klasöründedir (örnek konuşmalarla alınmıştır, kişisel bilgi içermez).
// Resimlerdeki numaralı işaretler dilden bağımsızdır; metindeki numaralarla eşleşir.

type KDil = "tr" | "en" | "kmr";
interface Bolum { id: string; baslik: string; icerik: string; }
interface Kilavuz { sekme: string; baslik: string; alt: string; icindekiler: string; bolumler: Bolum[]; }

const resim = (ad: string, aciklama: string) =>
  `<figure><img src="/kilavuz/${ad}.webp" alt="${aciklama}" loading="lazy" /><figcaption>${aciklama}</figcaption></figure>`;
const no = (n: number) => `<b class="no">${n}</b>`;
const tus = (...t: string[]) => t.map((x) => `<kbd>${x}</kbd>`).join("");

// ---------------------------------------------------------------- Türkçe
const TR: Kilavuz = {
  sekme: "Türkçe",
  baslik: "Kullanım kılavuzu",
  alt: "MED LIVE TRANSLATE",
  icindekiler: "İçindekiler",
  bolumler: [
    {
      id: "giris", baslik: "Program ne yapar?",
      icerik: `
<p>MED LIVE TRANSLATE, bilgisayarınızdan çıkan sesi (Zoom, Teams, Google Meet, YouTube, canlı yayın…) dinler,
konuşmayı yazıya döker, seçtiğiniz dile çevirir ve ekranın üstünde <b>altyazı</b> olarak gösterir.</p>
<ul>
  <li>Diller: <b>Kurmancî, Türkçe, İngilizce, Arapça</b> — hepsi birbirine çevrilir.</li>
  <li>Mikrofon kullanılmaz: yalnızca hoparlörden / kulaklıktan gelen ses dinlenir.</li>
  <li>Ekran kartı gerekmez; ortalama bir bilgisayarda çalışır. Ekran kartınız varsa program bunu fark eder ve hızlandırma önerir.</li>
</ul>
<p class="not">Program <b>Türkçe, English ve Kurmancî</b> kullanılabilir: <b>Ayarlar → Program dili</b> bölümünden
(ya da ilk kurulum ekranının sağ üstünden) seçin. Bu kılavuz da aynı dilde açılır.</p>`,
    },
    {
      id: "kurulum", baslik: "İlk açılış: bileşenlerin indirilmesi",
      icerik: `
<p>Kurulum dosyası birkaç saniyede biter. Programı <b>ilk açtığınızda</b> konuşma tanıma ve çeviri modelleri
indirilir (toplam ~1,8 GB, yalnızca bir kez). Ekranda yüzde, inen miktar, hız ve kalan süre görünür.</p>
${resim("kurulum", "İlk açılışta indirme ekranı")}
<ul>
  <li>Programı kapatırsanız indirme bir sonraki açılışta <b>kaldığı yerden</b> devam eder.</li>
  <li>İnternet kesilirse <b>Yeniden dene</b> düğmesi çıkar; inen kısım silinmez.</li>
  <li>Ekranın sağ üstündeki <b>Türkçe / English / Kurmancî</b> düğmeleriyle program dilini daha ilk açılışta seçebilirsiniz.</li>
  <li>İndirme bittikten sonra program internet olmadan da çalışabilir (bkz. “Çeviri”).</li>
</ul>`,
    },
    {
      id: "ana", baslik: "Ana pencere",
      icerik: `
${resim("ana", "Ana pencere — numaralar aşağıda açıklanmıştır")}
<ol class="isaretli">
  <li>${no(1)} <b>Konuşulan dil:</b> videoda / toplantıda konuşulan dil.</li>
  <li>${no(2)} <b>⇄</b> konuşulan dil ile altyazı dilini yer değiştirir.</li>
  <li>${no(3)} <b>Altyazı dili:</b> çevirinin yazılacağı dil.</li>
  <li>${no(4)} <b>Kürtçe Büyük modeli</b> (yalnızca Kurmancî seçiliyken görünür, isteğe bağlı).</li>
  <li>${no(5)} <b>Başlat / Durdur</b> düğmesi. Çalışırken kırmızı olur ve “Durdur” yazar.</li>
  <li>${no(6)} <b>Durum satırı:</b> programın ne yaptığını söyler (hazır, dinleniyor, uyarılar).</li>
  <li>${no(7)} Hızlı düğmeler: <b>Altyazı şeridi</b> (göster/gizle), <b>Tıklama geçir</b>, <b>Duraklat</b>,
      <b>Çevrimdışı çeviri</b> (bkz. “Çeviri ve internet”).</li>
  <li>${no(8)} <b>Son altyazı:</b> en son çevrilen cümle ve altında orijinali.</li>
  <li>${no(9)} <b>Konuşma geçmişi:</b> bu oturumda söylenen her şey saatiyle birikir; kaçırdığınız cümleleri buradan okuyun.
      Önceki oturumlar <b>Geçmiş</b> düğmesindedir (13).</li>
  <li>${no(10)} Geçmişi <b>kopyala</b>, <b>metin dosyası olarak kaydet</b> (klasörü siz seçersiniz) ya da <b>temizle</b>.</li>
  <li>${no(11)} <b>Ana sayfa:</b> açık olan ekranı (Öneriler, Geçmiş, Kılavuz, Ayarlar) kapatıp ana ekrana döner (${tus("Esc")}). Açık ekranın düğmesi vurgulu görünür.</li>
  <li>${no(12)} <b>Öneriler:</b> bilgisayarınıza uygun ek paketler (ekran kartı hızlandırması, Kürtçe Büyük). Sarı nokta: önerilen bir paket kurulmamış.</li>
  <li>${no(13)} <b>Geçmiş:</b> eski konuşmaları açar; okuyun, indirin ya da silin (bkz. “Eski konuşmalar”).</li>
  <li>${no(14)} <b>Kılavuz:</b> bu kılavuzu açar (${tus("F1")}).</li>
  <li>${no(15)} <b>Kontrol çubuğu:</b> küçük kontrol çubuğunu açar / kapatır (${tus("Ctrl", "Alt", "M")}).</li>
  <li>${no(16)} <b>Ayarlar:</b> program dili, tema, altyazı görünümü, kısayollar.</li>
</ol>
<p>Ses ölçeri (dil seçimlerinin üstündeki ince çubuk) bilgisayardan ses geldikçe hareket eder.
Hareket etmiyorsa program ses duymuyordur (bkz. “Sorun giderme”).</p>`,
    },
    {
      id: "adimlar", baslik: "Adım adım kullanım",
      icerik: `
<ol class="adim">
  <li><b>Konuşulan dili</b> ve <b>altyazı dilini</b> seçin. Örnek: konuşma Kurmancî, altyazı Türkçe.</li>
  <li>Durum satırında “Hazır — Başlat'a basın” yazmasını bekleyin (ilk seferde model birkaç saniyede yüklenir).</li>
  <li><b>Başlat</b>'a basın.</li>
  <li>Toplantıyı, videoyu ya da yayını açın. Konuşma geldikçe altyazı şeridinde çeviri belirir.</li>
  <li>Bitince <b>Durdur</b>'a basın. İsterseniz geçmişi indirip saklayın.</li>
</ol>
<p class="not">Altyazı, konuşmacı cümlesini bitirdikten kısa süre sonra gelir. Yavaş bilgisayarlarda gecikme biraz artar
ama altyazı geride kalmaz: bekleyen cümleler birlikte işlenir.</p>`,
    },
    {
      id: "serit", baslik: "Altyazı şeridi",
      icerik: `
<p>Ekran kalabalık olmasın diye şerit program açılınca <b>kapalıdır</b>. Ana penceredeki <b>Altyazı şeridi</b> düğmesiyle
(ya da ${tus("Ctrl", "Alt", "H")}) açın. Dinlerken şerit kapalıysa bu düğme yanıp sönerek hatırlatır.</p>
${resim("serit", "Altyazı şeridi: üstte çeviri, altta orijinal metin")}
<ul>
  <li>Şerit her pencerenin üstünde durur. <b>Sürükleyerek</b> istediğiniz yere taşıyın.</li>
  <li><b>Sağ kenarından</b> tutup genişliğini değiştirin.</li>
  <li><b>Sağ tık</b> menüsü: yazıyı büyüt/küçült, temizle, tıklamaları geçir, gizle.</li>
  <li><b>Tıklama geçir</b> açıkken fare şeridin “içinden geçer”; altındaki toplantı düğmelerine tıklayabilirsiniz.
      Kapatmak için ${tus("Ctrl", "Alt", "K")}.</li>
  <li>Şeridi gizlemek / geri getirmek için ${tus("Ctrl", "Alt", "H")}.</li>
</ul>`,
    },
    {
      id: "kontrol", baslik: "Küçük kontrol çubuğu",
      icerik: `
<p>Toplantı tam ekrandayken ana pencereye dönmek zorunda kalmamak için ${tus("Ctrl", "Alt", "M")} ile küçük
çubuğu açın. Çubuk her şeyin üstünde durur.</p>
${resim("kontrol", "Küçük kontrol çubuğu")}
<p class="not">Çubuk program açılınca kapalıdır; üstteki <b>Kontrol çubuğu</b> düğmesiyle ya da ${tus("Ctrl", "Alt", "M")} ile açılır.</p>
<ol class="isaretli">
  <li>${no(1)} Tutamak: sürükleyerek taşıyın.</li>
  <li>${no(2)} Başlat / Durdur.</li>
  <li>${no(3)} Konuşulan dil, ⇄, altyazı dili.</li>
  <li>${no(4)} Duraklat / devam.</li>
  <li>${no(5)} Altyazı şeridini göster / gizle.</li>
  <li>${no(6)} Ana pencereyi aç.</li>
  <li>${no(7)} Çubuğu gizle (${tus("Ctrl", "Alt", "M")} ile geri gelir).</li>
</ol>`,
    },
    {
      id: "kurmanci", baslik: "Kürtçe modelleri (Küçük / Orta / Büyük)",
      icerik: `
<p>Kurmancî seçiliyken ana penceredeki <b>Kürtçe modeli</b> listesinden model seçilir:</p>
<ul>
  <li><b>Kürtçe Küçük:</b> programla gelir, hızlıdır.</li>
  <li><b>Kürtçe Orta</b> (Meta Omnilingual, isteğe bağlı, ~0,8 GB): Küçük'ten daha doğrudur, uydurma
      cümle ve tekrar yapmaz, noktalama koymaz. İşlemcide çalışır. Kurulu değilse ana pencerede <b>İndir</b> düğmesi görünür.</li>
  <li><b>Kürtçe Büyük</b> (Whisper large, isteğe bağlı, ~2 GB): kurulu değilse ana pencerede <b>İndir</b> düğmesi görünür.</li>
</ul>
<p>Kürtçe Büyük modeli:</p>
<ul>
  <li><b>NVIDIA ekran kartı varsa:</b> paket kartta çalışır, gecikme yaklaşık 1–2 saniyedir. Önerilir.</li>
  <li><b>Ekran kartı yoksa:</b> işlemcide çalışır; daha doğrudur ama altyazı 10–20 saniye geç gelir.</li>
  <li><b>AMD / Intel ekran kartı varsa:</b> Öneriler ekranındaki <b>Kürtçe Büyük — ekran kartı</b> paketiyle (~1,1 GB)
      kartta çalışır, gecikme yaklaşık 3 saniyeye iner. Kartınız uygun değilse bu paket önerilmez.</li>
</ul>`,
    },
    {
      id: "oneriler", baslik: "Bilgisayarınıza göre öneriler ve ekran kartı",
      icerik: `
<p>İlk kurulum bittikten sonra program bilgisayarınızı (işlemci, bellek, ekran kartı) inceler ve
<b>Bu bilgisayar için öneriler</b> ekranını bir kez kendiliğinden açar. Bu ekranı istediğiniz zaman üstteki
<b>Öneriler</b> düğmesiyle yeniden açabilirsiniz. Önerilen ama kurulmamış bir paket varsa düğmenin köşesinde sarı bir nokta görünür.</p>
${resim("oneriler", "Bu bilgisayar için öneriler")}
<ul>
  <li><b>Önerilir</b> (yeşil): bu bilgisayarda fark yaratır; <b>İndir</b>'e basın.</li>
  <li><b>Faydalı olabilir:</b> ekran kartınız orta güçte; program kartı dener, yavaş çıkarsa kendiliğinden işlemciye döner.</li>
  <li><b>İsteğe bağlı:</b> çalışır ama bir bedeli vardır (ör. gecikme); açıklamayı okuyup karar verin.</li>
  <li><b>Gerek yok / Uygun değil:</b> bu bilgisayarda fayda sağlamaz; indirme düğmesi gösterilmez, boşuna indirme yapılmaz.</li>
</ul>
<p><b>Ekran kartı hızlandırması</b> (~0,6 GB): AMD, Intel ya da NVIDIA ekran kartında Türkçe, İngilizce ve Arapça altyazı
3–4 kat daha hızlı gelir (cümle başına ~6 sn yerine ~1,5–2 sn). <b>Kürtçe Büyük — ekran kartı</b> (~1,1 GB): Kürtçe Büyük
Kurmancî modelini kartta yaklaşık 3 kat hızlı çalıştırır.</p>
<p class="not">Ekran kartı kullanılırken durum satırında “· ekran kartı” yazar. Kart herhangi bir nedenle sorun çıkarırsa
program altyazıyı kesmeden işlemciye geçer.</p>`,
    },
    {
      id: "ceviri", baslik: "Çeviri ve internet",
      icerik: `
<ul>
  <li>Çeviri normalde <b>Google Çeviri</b> ile yapılır (internet gerekir).</li>
  <li>Google'a ulaşılamazsa (internet yok, geçici kısıtlama) çeviri kendiliğinden bilgisayardaki
      <b>çevrimdışı modelle</b> sürer. Durum satırında “çevrimdışı çeviri kullanılıyor” yazar. Kalitesi biraz daha düşüktür.</li>
  <li>Google yeniden erişilebilir olunca program kendiliğinden ona döner.</li>
  <li><b>Çevrimdışı çeviri</b> düğmesi: internetinizi kapatmanıza gerek kalmadan çeviriyi bilgisayarınızda yaptırır;
      <b>hiçbir metin internete gönderilmez</b> (gizli toplantılar için). Kalitesi Google'dan biraz düşüktür.
      Açıkken düğme renklenir ve “Çevrimdışı: açık” yazar; yeniden basınca Google'a dönülür.</li>
  <li>Konuşma tanıma her zaman bilgisayarınızda yapılır; internet gerektirmez.</li>
</ul>`,
    },
    {
      id: "ayarlar", baslik: "Ayarlar ve kısayollar",
      icerik: `
${resim("ayarlar", "Ayarlar paneli")}
<ol class="isaretli">
  <li>${no(1)} <b>Program dili:</b> Türkçe, English ya da Kurmancî. Menüler, düğmeler, mesajlar, küçük kontrol çubuğu,
      altyazı şeridinin menüsü ve bu kılavuz hemen o dile geçer. Altyazı dilleri bundan ayrıdır; ana ekrandan seçilir.</li>
  <li>${no(2)} <b>Tema:</b> programın ve altyazının renkleri.</li>
  <li>${no(3)} <b>Altyazı görünümü:</b> yazı boyutu, aynı anda gösterilen cümle sayısı, zemin opaklığı,
      orijinal metni gösterme.</li>
  <li>${no(4)} <b>Konuşma geçmişi:</b> “Konuşmaları bu bilgisayarda sakla” açıkken her konuşma Geçmiş ekranına kaydedilir.</li>
  <li>${no(5)} <b>Kısayollar</b> (program arka plandayken de çalışır):</li>
</ol>
<table class="kisayol-tablo">
  <tr><td>${tus("Ctrl", "Alt", "H")}</td><td>Altyazıyı göster / gizle</td></tr>
  <tr><td>${tus("Ctrl", "Alt", "P")}</td><td>Duraklat / devam</td></tr>
  <tr><td>${tus("Ctrl", "Alt", "M")}</td><td>Küçük kontrol çubuğu</td></tr>
  <tr><td>${tus("Ctrl", "Alt", "K")}</td><td>Tıklamaları geçir</td></tr>
  <tr><td>${tus("Ctrl", "Alt", "↑")} ${tus("↓")}</td><td>Yazı boyutu</td></tr>
</table>`,
    },
    {
      id: "gecmis", baslik: "Eski konuşmalar (Geçmiş)",
      icerik: `
<p>Başlat'a her bastığınızda konuşma, Durdur'a basana kadar ayrı bir kayıt olarak bilgisayarınızda saklanır.
Üst çubuktaki <b>Geçmiş</b> düğmesi bu kayıtları açar.</p>
${resim("gecmis", "Geçmiş ekranı")}
<ol class="isaretli">
  <li>${no(1)} <b>Konuşma listesi</b> (en yenisi üstte). Soldaki listede kayıtlar güne göre sıralanır: saat aralığı, diller ve cümle sayısı görünür. Birine tıklayınca bütün cümleler
      saatiyle ve çevirisiyle sağda açılır. Dinleme sürerken kaydedilen konuşma <b>sürüyor</b> etiketiyle gösterilir.</li>
  <li>${no(2)} <b>İndir</b>: seçili konuşmayı metin dosyası olarak kaydeder (klasörü siz seçersiniz).</li>
  <li>${no(3)} <b>Sil</b>: seçili konuşmayı siler.</li>
  <li>${no(4)} <b>Tümünü indir</b>: bütün konuşmaları tek bir metin dosyasında kaydeder.</li>
  <li>${no(5)} <b>Tümünü sil</b>: bütün kayıtları siler.</li>
</ol>
<p>Silmeden önce onay sorulur; silinen kayıt geri getirilemez. Kaydetmeyi Ayarlar'daki
<b>Konuşmaları bu bilgisayarda sakla</b> kutusuyla kapatabilirsiniz.</p>`,
    },
    {
      id: "gizlilik", baslik: "Gizlilik",
      icerik: `
<ul>
  <li><b>Ses kaydedilmez</b> ve hiçbir yere gönderilmez; bilgisayarınızda yazıya dökülür.</li>
  <li><b>Çeviri için</b> yazıya dökülen cümleler Google Çeviri'ye gönderilir. Çevrimdışı çeviride hiçbir şey gönderilmez.</li>
  <li><b>Konuşmalar</b> (yazıya dökülen metin ve çevirisi) yalnızca bu bilgisayarda, <b>Geçmiş</b> ekranında saklanır; hiçbir yere gönderilmez.
      İstemiyorsanız Ayarlar'dan <b>Konuşmaları bu bilgisayarda sakla</b> kutusunu kapatın; eski kayıtları Geçmiş ekranından silebilirsiniz.</li>
  <li>Program hesap, e-posta ya da kişisel bilgi istemez; kullanım istatistiği toplamaz.</li>
  <li>Günlük dosyaları (motor_gunluk.txt, kurulum_gunluk.txt) konuşma metni içermez.</li>
</ul>
<p class="not"><b>Gizli bir toplantı mı?</b> Ana ekrandaki <b>Çevrimdışı çeviri</b> düğmesine basın: internetinizi kapatmanıza
gerek kalmadan hiçbir metin Google'a gönderilmez, çeviri bilgisayarınızda yapılır.</p>`,
    },
    {
      id: "sorun", baslik: "Sorun giderme",
      icerik: `
<dl>
  <dt>Altyazı çıkmıyor, ses ölçeri hareket etmiyor</dt>
  <dd>Ses gerçekten bilgisayardan çalıyor mu? Program sistemin <b>varsayılan</b> hoparlörünü / kulaklığını dinler.
      Toplantı programında sesi başka bir aygıta yönlendirdiyseniz ses ayarlarında (Windows ses ayarları; Linux'ta Ses ayarları) o aygıtı varsayılan yapın.</dd>
  <dt>Çeviri yerine orijinal metin görünüyor</dt>
  <dd>Google'a ulaşılamıyor ve çevrimdışı model henüz inmemiş olabilir. İnternet bağlantınızı kontrol edin;
      bağlantı gelince çeviri kendiliğinden döner.</dd>
  <dt>Altyazı geç geliyor</dt>
  <dd>Diğer ağır programları kapatın. Kurmancî'de “Kürtçe Büyük” işlemcide çalışıyorsa “Kürtçe Küçük” ya da “Kürtçe Orta”yı seçin.</dd>
  <dt>Yanlış dil yazılıyor</dt>
  <dd><b>Konuşulan dil</b> seçimini kontrol edin; program dili kendisi tahmin etmez.</dd>
  <dt>İlk kurulum yarıda kaldı</dt>
  <dd>Programı yeniden açın ya da <b>Yeniden dene</b>'ye basın; indirme kaldığı yerden sürer.</dd>
</dl>`,
    },
    {
      id: "iletisim", baslik: "İletişim",
      icerik: `
<p>Program ile ilgili iletişim için X'te <b>@seyfi_polat21</b> (x.com/seyfi_polat21) hesabından bize ulaşabilirsiniz.
Bir hata bildirirken ne yaptığınızı ve ne olduğunu yazın. Diğer hesaplarımız <b>Hakkında</b> bölümündedir.</p>
<p>Gerekirse şu klasördeki <b>motor_gunluk.txt</b> ve <b>kurulum_gunluk.txt</b> dosyalarını ekleyebilirsiniz
(konuşma metni içermezler): <code>%LOCALAPPDATA%\\MED LIVE TRANSLATE</code> — Windows gezgininin adres çubuğuna yazıp Enter'a basın.
Linux'ta: <code>~/.local/share/med-live-translate</code></p>
<p class="kucuk-not">Kullanılan modeller: OpenAI Whisper (MIT), samil24 Kurmancî Whisper modelleri, Meta NLLB-200
(CC-BY-NC-4.0, yalnızca ticari olmayan kullanım).</p>`,
    },
  ],
};

// ---------------------------------------------------------------- English
const EN: Kilavuz = {
  sekme: "English",
  baslik: "User guide",
  alt: "MED LIVE TRANSLATE",
  icindekiler: "Contents",
  bolumler: [
    {
      id: "giris", baslik: "What does the program do?",
      icerik: `
<p>MED LIVE TRANSLATE listens to the sound coming out of your computer (Zoom, Teams, Google Meet, YouTube, live streams…),
turns speech into text, translates it into the language you choose and shows it on top of the screen as <b>subtitles</b>.</p>
<ul>
  <li>Languages: <b>Kurmanji (Kurdish), Turkish, English, Arabic</b> — any of them into any other.</li>
  <li>No microphone is used: only the sound going to your speakers / headphones is captured.</li>
  <li>No graphics card is required; it runs on an average computer. If you have one, the program detects it and offers acceleration.</li>
</ul>
<p class="not">The program can be used in <b>English, Turkish or Kurmanji</b>: choose it under <b>Settings → Program language</b>
(or at the top right of the first setup screen). This guide opens in the same language.</p>`,
    },
    {
      id: "kurulum", baslik: "First start: downloading components",
      icerik: `
<p>The installer finishes in a few seconds. The <b>first time you open</b> the program it downloads the speech recognition
and translation models (about 1.8 GB in total, only once). Percentage, downloaded size, speed and remaining time are shown.</p>
${resim("kurulum", "Download screen on first start")}
<ul>
  <li>If you close the program, the download <b>resumes</b> where it stopped next time.</li>
  <li>If the connection drops, a <b>Try again</b> button appears; nothing already downloaded is lost.</li>
  <li>With the <b>Türkçe / English / Kurmancî</b> buttons at the top right of this screen you can choose the program language right away.</li>
  <li>After the download the program can also work without internet (see “Translation”).</li>
</ul>`,
    },
    {
      id: "ana", baslik: "Main window",
      icerik: `
${resim("ana", "Main window — the numbers are explained below")}
<ol class="isaretli">
  <li>${no(1)} <b>Spoken language:</b> the language spoken in the video / meeting.</li>
  <li>${no(2)} <b>⇄</b> swaps the spoken and subtitle languages.</li>
  <li>${no(3)} <b>Subtitle language:</b> the language of the translation.</li>
  <li>${no(4)} <b>Kurdish Large model</b> — only shown when Kurmanji is selected, optional.</li>
  <li>${no(5)} <b>Start / Stop</b>. It turns red and reads “Stop” while running.</li>
  <li>${no(6)} <b>Status line:</b> tells you what the program is doing (ready, listening, warnings).</li>
  <li>${no(7)} Quick buttons: <b>Subtitle bar</b> (show/hide), <b>Click-through</b>, <b>Pause</b>,
      <b>Offline translation</b> (see “Translation and internet”).</li>
  <li>${no(8)} <b>Last subtitle:</b> the latest translated sentence with the original below it.</li>
  <li>${no(9)} <b>Conversation history:</b> everything said in this session, with the time. Read missed sentences here.
      Earlier sessions are under the <b>History</b> button (13).</li>
  <li>${no(10)} <b>Copy</b>, <b>save as a text file</b> (you choose the folder) or <b>clear</b> the transcript.</li>
  <li>${no(11)} <b>Home:</b> closes the open screen (Suggestions, History, Guide, Settings) and returns to the main screen (${tus("Esc")}). The button of the open screen is highlighted.</li>
  <li>${no(12)} <b>Suggestions:</b> extra packs that suit your computer (graphics card acceleration, Kurdish Large). A yellow dot means a recommended pack is not installed yet.</li>
  <li>${no(13)} <b>History:</b> opens past conversations to read, download or delete them (see “Past conversations”).</li>
  <li>${no(14)} <b>Guide:</b> opens this guide (${tus("F1")}).</li>
  <li>${no(15)} <b>Control bar:</b> shows / hides the mini control bar (${tus("Ctrl", "Alt", "M")}).</li>
  <li>${no(16)} <b>Settings:</b> program language, theme, subtitle appearance, shortcuts.</li>
</ol>
<p>The level meter (the thin bar above the language selectors) moves when sound is playing.
If it does not move, the program hears nothing (see “Troubleshooting”).</p>`,
    },
    {
      id: "adimlar", baslik: "Step by step",
      icerik: `
<ol class="adim">
  <li>Choose the <b>spoken language</b> and the <b>subtitle language</b>. Example: speech in Kurmanji, subtitles in English.</li>
  <li>Wait until the status line reads “Ready — press Start”. The first time, the model loads in a few seconds.</li>
  <li>Press <b>Start</b>.</li>
  <li>Open the meeting, video or stream. Translations appear on the subtitle bar as people speak.</li>
  <li>When you are done press <b>Stop</b>. Save the transcript if you want to keep it.</li>
</ol>
<p class="not">A subtitle appears shortly after the speaker finishes a sentence. On slow computers the delay is a little
longer, but subtitles never fall behind: waiting sentences are processed together.</p>`,
    },
    {
      id: "serit", baslik: "Subtitle bar",
      icerik: `
<p>To keep the screen tidy, the bar is <b>closed</b> when the program starts. Open it with the <b>Subtitle bar</b> button in the
main window (or ${tus("Ctrl", "Alt", "H")}). While listening, the button blinks as a reminder if the bar is closed.</p>
${resim("serit", "Subtitle bar: translation on top, original text below")}
<ul>
  <li>The bar stays on top of all windows. <b>Drag</b> it wherever you like.</li>
  <li>Drag its <b>right edge</b> to change the width.</li>
  <li><b>Right-click</b> menu: bigger/smaller text, clear, click-through, hide.</li>
  <li>With <b>click-through</b> on, the mouse passes through the bar so you can click meeting buttons underneath.
      Turn it off with ${tus("Ctrl", "Alt", "K")}.</li>
  <li>Hide / show the bar with ${tus("Ctrl", "Alt", "H")}.</li>
</ul>`,
    },
    {
      id: "kontrol", baslik: "Mini control bar",
      icerik: `
<p>When a meeting is full screen, press ${tus("Ctrl", "Alt", "M")} to open the mini bar instead of switching to the main window.
It stays on top of everything.</p>
${resim("kontrol", "Mini control bar")}
<p class="not">The bar is closed when the program starts; open it with the <b>Control bar</b> button at the top or ${tus("Ctrl", "Alt", "M")}.</p>
<ol class="isaretli">
  <li>${no(1)} Handle: drag to move.</li>
  <li>${no(2)} Start / Stop.</li>
  <li>${no(3)} Spoken language, ⇄, subtitle language.</li>
  <li>${no(4)} Pause / resume.</li>
  <li>${no(5)} Show / hide the subtitle bar.</li>
  <li>${no(6)} Open the main window.</li>
  <li>${no(7)} Hide the mini bar (bring it back with ${tus("Ctrl", "Alt", "M")}).</li>
</ol>`,
    },
    {
      id: "kurmanci", baslik: "Kurdish models (Small / Medium / Large)",
      icerik: `
<p>While Kurmanji is selected, choose the model in the <b>Kurdish model</b> list of the main window:</p>
<ul>
  <li><b>Kurdish Small:</b> comes with the program, fast.</li>
  <li><b>Kurdish Medium</b> (Meta Omnilingual, optional, ~0.8 GB): more accurate than Small, no invented
      sentences or repeats, no punctuation. Runs on the processor. If not installed, a <b>Download</b> button appears.</li>
  <li><b>Kurdish Large</b> (Whisper large, optional, ~2 GB): if not installed, a <b>Download</b> button appears.</li>
</ul>
<p>Kurdish Large model:</p>
<ul>
  <li><b>With an NVIDIA graphics card:</b> the pack runs on the card with about 1–2 seconds of delay. Recommended.</li>
  <li><b>Without a graphics card:</b> it runs on the processor; more accurate, but subtitles arrive 10–20 seconds late.</li>
  <li><b>With an AMD / Intel graphics card:</b> the <b>Kurdish Large — graphics card</b> pack (~1.1 GB) in the Suggestions
      screen runs it on the card, cutting the delay to about 3 seconds. It is not offered if your card is not suitable.</li>
</ul>`,
    },
    {
      id: "oneriler", baslik: "Suggestions for your computer and the graphics card",
      icerik: `
<p>After the first setup, the program examines your computer (processor, memory, graphics card) and opens the
<b>Suggestions for this computer</b> screen once by itself. You can reopen it at any time with the
<b>Suggestions</b> button at the top. A yellow dot on the button means a recommended pack is not installed yet.</p>
${resim("oneriler", "Suggestions for this computer")}
<ul>
  <li><b>Recommended</b> (green): makes a difference on this computer; press <b>Download</b>.</li>
  <li><b>May help:</b> your card is mid-range; the program tries it and falls back to the processor if it is slow.</li>
  <li><b>Optional:</b> works but has a cost (e.g. delay); read the explanation and decide.</li>
  <li><b>Not needed / Not suitable:</b> no benefit on this computer; no download button is shown, so nothing is downloaded for nothing.</li>
</ul>
<p><b>Graphics card acceleration</b> (~0.6 GB): on an AMD, Intel or NVIDIA graphics card, Turkish,
English and Arabic subtitles arrive 3–4 times faster (~1.5–2 s per sentence instead of ~6 s). <b>Kurdish Large — graphics card</b>
(~1.1 GB): runs the Kurdish Large model on the card about 3 times faster.</p>
<p class="not">While the graphics card is used, the status line shows “· graphics card”. If the card causes any problem, the program
switches to the processor without interrupting the subtitles.</p>`,
    },
    {
      id: "ceviri", baslik: "Translation and internet",
      icerik: `
<ul>
  <li>Translation is normally done by <b>Google Translate</b> (internet required).</li>
  <li>If Google cannot be reached (no internet, temporary limit), translation automatically continues with the
      <b>offline model</b> on your computer. The status line says “using offline translation”.
      Quality is slightly lower.</li>
  <li>When Google is reachable again, the program switches back automatically.</li>
  <li><b>Offline translation</b> button: translation is done on your computer without turning off your
      internet; <b>no text is sent to the internet</b> (for confidential meetings). Quality is slightly lower than Google.
      While on, the button is highlighted and reads “Offline: on”; press again to return to Google.</li>
  <li>Speech recognition always runs on your computer and needs no internet.</li>
</ul>`,
    },
    {
      id: "ayarlar", baslik: "Settings and shortcuts",
      icerik: `
${resim("ayarlar", "Settings panel")}
<ol class="isaretli">
  <li>${no(1)} <b>Program language:</b> English, Türkçe or Kurmancî. Menus, buttons, messages, the mini control bar,
      the subtitle bar menu and this guide switch immediately. Subtitle languages are separate; choose them on the main screen.</li>
  <li>${no(2)} <b>Theme:</b> colours of the program and subtitles.</li>
  <li>${no(3)} <b>Subtitle appearance:</b> text size, number of sentences shown, background opacity,
      show the original text.</li>
  <li>${no(4)} <b>Conversation history:</b> while “Keep conversations on this computer” is on, every conversation is saved to the History screen.</li>
  <li>${no(5)} <b>Shortcuts</b> — they also work while the program is in the background:</li>
</ol>
<table class="kisayol-tablo">
  <tr><td>${tus("Ctrl", "Alt", "H")}</td><td>Show / hide subtitles</td></tr>
  <tr><td>${tus("Ctrl", "Alt", "P")}</td><td>Pause / resume</td></tr>
  <tr><td>${tus("Ctrl", "Alt", "M")}</td><td>Mini control bar</td></tr>
  <tr><td>${tus("Ctrl", "Alt", "K")}</td><td>Click-through</td></tr>
  <tr><td>${tus("Ctrl", "Alt", "↑")} ${tus("↓")}</td><td>Text size</td></tr>
</table>`,
    },
    {
      id: "gecmis", baslik: "Past conversations (History)",
      icerik: `
<p>Each time you press Start, the conversation is stored on your computer as a separate record until you press Stop.
The <b>History</b> button in the top bar opens these records.</p>
${resim("gecmis", "History screen")}
<ol class="isaretli">
  <li>${no(1)} <b>Conversation list</b> (newest first). The list on the left is grouped by day and shows the time range, the languages and the number of sentences. Click one to open
      every sentence with its time and translation on the right. The conversation being recorded right now is marked <b>in progress</b>.</li>
  <li>${no(2)} <b>Download</b> saves the selected conversation as a text file (you choose the folder).</li>
  <li>${no(3)} <b>Delete</b> removes the selected conversation.</li>
  <li>${no(4)} <b>Download all</b> saves every conversation in a single text file.</li>
  <li>${no(5)} <b>Delete all</b> removes every record.</li>
</ol>
<p>You are asked to confirm before deleting; deleted records cannot be restored. You can turn saving off with
<b>Keep conversations on this computer</b> in Settings.</p>`,
    },
    {
      id: "gizlilik", baslik: "Privacy",
      icerik: `
<ul>
  <li><b>Audio is never recorded</b> or sent anywhere; it is transcribed on your computer.</li>
  <li><b>For translation</b>, the transcribed sentences are sent to Google Translate. Nothing is sent during offline translation.</li>
  <li><b>Conversations</b> (the transcribed text and its translation) are stored only on this computer, in the <b>History</b> screen, and are never sent anywhere.
      If you don't want this, turn off <b>Keep conversations on this computer</b> in Settings; delete old records from the History screen.</li>
  <li>The program asks for no account, e-mail or personal data and collects no usage statistics.</li>
  <li>Log files (motor_gunluk.txt, kurulum_gunluk.txt) contain no spoken text.</li>
</ul>
<p class="not"><b>Confidential meeting?</b> Press <b>Offline translation</b> on the main screen: no text is sent to Google and
translation is done on your computer — you don't need to turn off your internet.</p>`,
    },
    {
      id: "sorun", baslik: "Troubleshooting",
      icerik: `
<dl>
  <dt>No subtitles, the level meter does not move</dt>
  <dd>Is sound really playing on the computer? The program listens to the system's <b>default</b> speakers / headphones.
      If your meeting app sends sound to another device, make that device the default in the sound settings (Windows sound settings; on Linux the Sound settings).</dd>
  <dt>The original text appears instead of a translation</dt>
  <dd>Google cannot be reached and the offline model may not be downloaded yet. Check your connection;
      translation returns automatically.</dd>
  <dt>Subtitles are late</dt>
  <dd>Close other heavy programs. For Kurmanji, choose “Kurdish Small” or “Kurdish Medium” if “Kurdish Large” runs on the processor.</dd>
  <dt>Wrong language is written</dt>
  <dd>Check the <b>spoken language</b> setting; the program does not guess the language.</dd>
  <dt>The first download stopped</dt>
  <dd>Reopen the program or press <b>Try again</b>; the download resumes.</dd>
</dl>`,
    },
    {
      id: "iletisim", baslik: "Contact",
      icerik: `
<p>For anything about the program, you can reach us on X at <b>@seyfi_polat21</b> (x.com/seyfi_polat21).
When reporting a problem, describe what you did and what happened. Our other accounts are in the <b>About</b> section.</p>
<p>If needed, attach <b>motor_gunluk.txt</b> and <b>kurulum_gunluk.txt</b> (they contain no spoken text) from:
<code>%LOCALAPPDATA%\\MED LIVE TRANSLATE</code> — type it into the address bar of File Explorer and press Enter.
On Linux: <code>~/.local/share/med-live-translate</code></p>
<p class="kucuk-not">Models used: OpenAI Whisper (MIT), samil24 Kurmanji Whisper models, Meta NLLB-200
(CC-BY-NC-4.0, non-commercial use only).</p>`,
    },
  ],
};

// ---------------------------------------------------------------- Kurmancî
const KMR: Kilavuz = {
  sekme: "Kurmancî",
  baslik: "Rêbera bikaranînê",
  alt: "MED LIVE TRANSLATE",
  icindekiler: "Naverok",
  bolumler: [
    {
      id: "giris", baslik: "Bername çi dike?",
      icerik: `
<p>MED LIVE TRANSLATE guh dide dengê ku ji komputera we derdikeve (Zoom, Teams, Google Meet, YouTube, weşanên zindî…),
axaftinê dike nivîs, wê werdigerîne zimanê ku hûn hilbijêrin û li ser ekranê wekî <b>binnivîs</b> nîşan dide.</p>
<ul>
  <li>Ziman: <b>Kurmancî, Tirkî, Îngilîzî, Erebî</b> — her yek ji bo yên din tê wergerandin.</li>
  <li>Mîkrofon nayê bikaranîn: tenê dengê ku diçe hoparlor / guhkan tê girtin.</li>
  <li>Karta grafîkê ne pêwîst e; li ser komputereke navîn dixebite. Heke karta we hebe, bername wê dibîne û lezkirinê pêşniyar dike.</li>
</ul>
<p class="not">Bername bi <b>Kurmancî, Tirkî an Îngilîzî</b> tê bikaranîn: di <b>Mîheng → Zimanê bernameyê</b> de
(an li jor rastê ya ekrana sazkirina yekem) hilbijêrin. Ev rêber jî bi heman zimanî vedibe.</p>`,
    },
    {
      id: "kurulum", baslik: "Vekirina yekem: daxistina pêkhateyan",
      icerik: `
<p>Pelê sazkirinê di çend çirkeyan de diqede. Gava ku hûn bernameyê <b>cara yekem vekin</b>, modelên naskirina axaftinê
û wergerê tên daxistin (bi giştî ~1,8 GB, tenê carekê). Rêjeya sedî, mezinahiya daxistî, lez û dema mayî tên nîşandan.</p>
${resim("kurulum", "Ekrana daxistinê di vekirina yekem de")}
<ul>
  <li>Heke hûn bernameyê bigirin, daxistin cara din <b>ji cihê ku mabû</b> berdewam dike.</li>
  <li>Heke înternet qut bibe, bişkoka <b>Dîsa biceribîne</b> derdikeve; tiştê daxistî winda nabe.</li>
  <li>Bi bişkokên <b>Türkçe / English / Kurmancî</b> yên li jor rastê ya vê ekranê hûn dikarin zimanê bernameyê ji destpêkê ve hilbijêrin.</li>
  <li>Piştî daxistinê bername bê înternetê jî dikare bixebite (li beşa “Werger” binêrin).</li>
</ul>`,
    },
    {
      id: "ana", baslik: "Pencereya sereke",
      icerik: `
${resim("ana", "Pencereya sereke — hejmar li jêr hatine şirovekirin")}
<ol class="isaretli">
  <li>${no(1)} <b>Zimanê axaftinê:</b> zimanê ku di vîdyoyê / civînê de tê axaftin.</li>
  <li>${no(2)} <b>⇄</b> zimanê axaftinê û zimanê binnivîsê cih diguhêre.</li>
  <li>${no(3)} <b>Zimanê binnivîsê:</b> zimanê wergerê.</li>
  <li>${no(4)} <b>Modela Kurdî ya Mezin</b> — tenê dema Kurmancî hilbijartî be xuya dibe, bijarte ye.</li>
  <li>${no(5)} <b>Dest pê bike / Rawestîne</b>. Dema dixebite sor dibe û “Rawestîne” dinivîse.</li>
  <li>${no(6)} <b>Rêza rewşê:</b> dibêje bername çi dike (amade, guhdarî dike, hişyarî).</li>
  <li>${no(7)} Bişkokên bilez: <b>Xêza binnivîsê</b> (nîşan bide/veşêre), <b>Klîk derbas bibe</b>, <b>Bisekinîne</b>,
      <b>Bê înternet</b> (wergera bê înternet, binêre “Werger û înternet”).</li>
  <li>${no(8)} <b>Binnivîsa dawî:</b> hevoka herî dawî ya wergerandî û li binê wê ya orîjînal.</li>
  <li>${no(9)} <b>Dîroka axaftinê:</b> her tiştê di vê rûniştinê de hatî gotin bi demjimêrê; hevokên ku we ji dest dane li vir bixwînin.
      Rûniştinên berê di bişkoka <b>Dîrok</b> de ne (13).</li>
  <li>${no(10)} Dîrokê <b>kopî bikin</b>, wekî <b>pelê nivîsê tomar bikin</b> (hûn peldankê hilbijêrin) an <b>paqij bikin</b>.</li>
  <li>${no(11)} <b>Destpêk:</b> ekrana vekirî (Pêşniyar, Dîrok, Rêber, Mîheng) digire û vedigere ekrana sereke (${tus("Esc")}). Bişkoka ekrana vekirî ronîkirî xuya dike.</li>
  <li>${no(12)} <b>Pêşniyar:</b> pakêtên zêde yên li gorî komputera we (lezkirina karta grafîkê, Kurdiya Mezin). Xala zer: pakêteke pêşniyarkirî hê nehatiye sazkirin.</li>
  <li>${no(13)} <b>Dîrok:</b> axaftinên berê vedike; bixwînin, daxin an jê bibin (binêre “Axaftinên berê”).</li>
  <li>${no(14)} <b>Rêber:</b> vê rêberê vedike (${tus("F1")}).</li>
  <li>${no(15)} <b>Darika kontrolê:</b> darika kontrolê ya biçûk vedike / digire (${tus("Ctrl", "Alt", "M")}).</li>
  <li>${no(16)} <b>Mîheng:</b> zimanê bernameyê, tema, xuyabûna binnivîsê, kurterê.</li>
</ol>
<p>Pîvera dengê (xêza zirav a li ser hilbijartina zimanan) dema deng tê dilive.
Heke nelive, bername tu dengî nabihîze (li beşa “Çareserkirina pirsgirêkan” binêrin).</p>`,
    },
    {
      id: "adimlar", baslik: "Gav bi gav",
      icerik: `
<ol class="adim">
  <li><b>Zimanê axaftinê</b> û <b>zimanê binnivîsê</b> hilbijêrin. Mînak: axaftin Kurmancî, binnivîs Tirkî.</li>
  <li>Li bendê bin heta ku rêza rewşê “Amade ye — Dest pê bike bitikînin” binivîse. Cara yekem model di çend çirkeyan de bar dibe.</li>
  <li><b>Dest pê bike</b> bitikînin.</li>
  <li>Civîn, vîdyo an weşanê vekin. Dema mirov diaxivin, werger li ser xêza binnivîsê xuya dibe.</li>
  <li>Dema we qedand, <b>Rawestîne</b> bitikînin. Heke hûn bixwazin dîrokê tomar bikin û biparêzin.</li>
</ol>
<p class="not">Binnivîs piştî ku axêver hevoka xwe diqedîne bi demeke kurt tê. Li ser komputerên hêdî dereng hinekî zêde dibe,
lê binnivîs li paş namîne: hevokên li bendê bi hev re tên kirin.</p>`,
    },
    {
      id: "serit", baslik: "Xêza binnivîsê",
      icerik: `
<p>Da ku ekran qerebalix nebe, dema bername vedibe xêz <b>girtî</b> ye. Bi bişkoka <b>Xêza binnivîsê</b> ya di pencereya sereke de
(an ${tus("Ctrl", "Alt", "H")}) vekin. Dema guhdarî dike û xêz girtî be, bişkok dibiriqe da ku bîne bîra we.</p>
${resim("serit", "Xêza binnivîsê: li jor werger, li jêr nivîsa orîjînal")}
<ul>
  <li>Xêz li ser hemû pencereyan dimîne. Bi <b>kişandinê</b> wê bibin cihê ku hûn dixwazin.</li>
  <li>Ji <b>aliyê rastê</b> bigirin û firehiya wê biguherînin.</li>
  <li>Menûya <b>klîka rastê</b>: nivîsê mezin/biçûk bike, paqij bike, klîk derbas bibe, veşêre.</li>
  <li>Dema <b>klîk derbas bibe</b> vekirî be, mişk di nav xêzê re derbas dibe; hûn dikarin bişkokên civînê yên li binê wê bitikînin.
      Ji bo girtinê ${tus("Ctrl", "Alt", "K")}.</li>
  <li>Ji bo veşartin / vegerandina xêzê ${tus("Ctrl", "Alt", "H")}.</li>
</ul>`,
    },
    {
      id: "kontrol", baslik: "Darika kontrolê ya biçûk",
      icerik: `
<p>Dema civîn li ser tevahiya ekranê be, li şûna vegerîna pencereya sereke bi ${tus("Ctrl", "Alt", "M")} darika biçûk vekin.
Ew li ser her tiştî dimîne.</p>
${resim("kontrol", "Darika kontrolê ya biçûk")}
<p class="not">Dema bername vedibe darik girtî ye; bi bişkoka <b>Darika kontrolê</b> ya li jor an bi ${tus("Ctrl", "Alt", "M")} vedibe.</p>
<ol class="isaretli">
  <li>${no(1)} Destik: bi kişandinê bibin.</li>
  <li>${no(2)} Dest pê bike / Rawestîne.</li>
  <li>${no(3)} Zimanê axaftinê, ⇄, zimanê binnivîsê.</li>
  <li>${no(4)} Bisekinîne / berdewam bike.</li>
  <li>${no(5)} Xêza binnivîsê nîşan bide / veşêre.</li>
  <li>${no(6)} Pencereya sereke veke.</li>
  <li>${no(7)} Darikê veşêre (bi ${tus("Ctrl", "Alt", "M")} vedigere).</li>
</ol>`,
    },
    {
      id: "kurmanci", baslik: "Modelên Kurdî (Biçûk / Navîn / Mezin)",
      icerik: `
<p>Dema Kurmancî hilbijartî be, model ji lîsteya <b>Modela Kurdî</b> ya pencereya sereke tê hilbijartin:</p>
<ul>
  <li><b>Kurdiya Biçûk:</b> bi bernameyê re tê, bilez e.</li>
  <li><b>Kurdiya Navîn</b> (Meta Omnilingual, bijarte, ~0,8 GB): ji Biçûk rasttir e, hevokên çêkirî û
      dubarekirin tune, xalbendî tune. Li ser pêvajoyê dixebite. Heke sazkirî nebe, bişkoka <b>Daxe</b> xuya dibe.</li>
  <li><b>Kurdiya Mezin</b> (Whisper large, bijarte, ~2 GB): heke sazkirî nebe, bişkoka <b>Daxe</b> xuya dibe.</li>
</ul>
<p>Modela Kurdî ya Mezin:</p>
<ul>
  <li><b>Heke karta grafîkê ya NVIDIA hebe:</b> pakêt li ser kartê dixebite, dereng nêzî 1–2 çirkeyan e. Tê pêşniyarkirin.</li>
  <li><b>Heke karta grafîkê tune be:</b> li ser pêvajoyê dixebite; rasttir e, lê binnivîs 10–20 çirkeyan dereng tê.</li>
  <li><b>Heke karta grafîkê ya AMD / Intel hebe:</b> bi pakêta <b>Kurdiya Mezin — karta grafîkê</b> (~1,1 GB) ya di ekrana
      Pêşniyaran de li ser kartê dixebite, dereng dikeve nêzî 3 çirkeyan. Heke karta we ne guncav be, ev pakêt nayê pêşniyarkirin.</li>
</ul>`,
    },
    {
      id: "oneriler", baslik: "Pêşniyarên li gorî komputera we û karta grafîkê",
      icerik: `
<p>Piştî sazkirina yekem, bername komputera we (pêvajo, bîr, karta grafîkê) dinirxîne û ekrana
<b>Pêşniyarên ji bo vê komputerê</b> carekê bi xwe vedike. Hûn dikarin wê her dem bi bişkoka
<b>Pêşniyar</b> ya li jor dîsa vekin. Xala zer li ser bişkokê tê wê wateyê ku pakêteke pêşniyarkirî hê nehatiye sazkirin.</p>
${resim("oneriler", "Pêşniyarên ji bo vê komputerê")}
<ul>
  <li><b>Tê pêşniyarkirin</b> (kesk): li ser vê komputerê ferq dike; <b>Daxe</b> bitikînin.</li>
  <li><b>Dibe ku bi kêr were:</b> karta we ya navîn e; bername wê diceribîne, heke hêdî be bi xwe vedigere pêvajoyê.</li>
  <li><b>Bi dilê we:</b> dixebite lê bihayekî wê heye (mînak dereng); şiroveyê bixwînin û biryar bidin.</li>
  <li><b>Ne pêwîst e / Ne guncav e:</b> li ser vê komputerê feyde nade; bişkoka daxistinê nayê nîşandin, tiştek bê feyde nayê daxistin.</li>
</ul>
<p><b>Lezkirina karta grafîkê</b> (~0,6 GB): li ser karta grafîkê ya AMD, Intel an NVIDIA, binnivîsên
Tirkî, Îngilîzî û Erebî 3–4 caran zûtir tên (ji bo her hevokê ~1,5–2 çirke li şûna ~6 çirkeyan). <b>Kurdiya Mezin — karta grafîkê</b>
(~1,1 GB): modela Kurdiya Mezin li ser kartê nêzî 3 caran zûtir dixebitîne.</p>
<p class="not">Dema karta grafîkê tê bikaranîn, di rêza rewşê de “· karta grafîkê” tê nivîsîn. Heke kart pirsgirêkekê derxe,
bername bêyî ku binnivîs bisekine derbasî pêvajoyê dibe.</p>`,
    },
    {
      id: "ceviri", baslik: "Werger û înternet",
      icerik: `
<ul>
  <li>Werger bi gelemperî bi <b>Google Translate</b> tê kirin (înternet pêwîst e).</li>
  <li>Heke negihîje Google (înternet tune, sînorkirina demkî), werger bi xwe bi <b>modela offline</b> a li ser komputera we
      berdewam dike. Rêza rewşê dinivîse “wergera bê înternet tê bikaranîn”. Kalîte hinekî kêmtir e.</li>
  <li>Dema Google dîsa bigihîje, bername bi xwe vedigere wê.</li>
  <li>Bişkoka <b>Bê înternet</b> (wergera bê înternet): bêyî ku hûn înterneta xwe bigirin, werger li ser komputera we tê kirin;
      <b>tu nivîs ji înternetê re nayê şandin</b> (ji bo civînên nepenî). Kalîteya wê hinekî ji Google kêmtir e.
      Dema vekirî be bişkok rengîn dibe û “Bê înternet: vekirî” dinivîse; dîsa bitikînin da ku vegere Google.</li>
  <li>Naskirina axaftinê her tim li ser komputera we tê kirin û înternet ne pêwîst e.</li>
</ul>`,
    },
    {
      id: "ayarlar", baslik: "Mîheng û kurterê",
      icerik: `
${resim("ayarlar", "Panela mîhengan")}
<ol class="isaretli">
  <li>${no(1)} <b>Zimanê bernameyê:</b> Kurmancî, Türkçe an English. Menû, bişkok, peyam, darika kontrolê ya biçûk,
      menûya xêza binnivîsê û ev rêber yekser derbasî wî zimanî dibin. Zimanên binnivîsê cuda ne; li ser ekrana sereke tên hilbijartin.</li>
  <li>${no(2)} <b>Tema:</b> rengên bernameyê û binnivîsê.</li>
  <li>${no(3)} <b>Xuyabûna binnivîsê:</b> mezinahiya nivîsê, hejmara hevokên ku bi hev re tên nîşandan,
      zelalbûna paşxanê, nîşandana nivîsa orîjînal.</li>
  <li>${no(4)} <b>Dîroka axaftinê:</b> dema “Axaftinan li ser vê komputerê biparêze” vekirî be, her axaftin di ekrana Dîrokê de tê tomarkirin.</li>
  <li>${no(5)} <b>Kurterê</b> — dema bername li paşxanê be jî dixebitin:</li>
</ol>
<table class="kisayol-tablo">
  <tr><td>${tus("Ctrl", "Alt", "H")}</td><td>Binnivîsê nîşan bide / veşêre</td></tr>
  <tr><td>${tus("Ctrl", "Alt", "P")}</td><td>Bisekinîne / berdewam bike</td></tr>
  <tr><td>${tus("Ctrl", "Alt", "M")}</td><td>Darika kontrolê ya biçûk</td></tr>
  <tr><td>${tus("Ctrl", "Alt", "K")}</td><td>Klîk derbas bibe</td></tr>
  <tr><td>${tus("Ctrl", "Alt", "↑")} ${tus("↓")}</td><td>Mezinahiya nivîsê</td></tr>
</table>`,
    },
    {
      id: "gecmis", baslik: "Axaftinên berê (Dîrok)",
      icerik: `
<p>Her carê ku hûn Dest pê bike bitikînin, axaftin heta ku hûn Rawestîne bitikînin wek tomareke cuda li ser komputera we tê parastin.
Bişkoka <b>Dîrok</b> a li darika jorîn van tomaran vedike.</p>
${resim("gecmis", "Ekrana Dîrokê")}
<ol class="isaretli">
  <li>${no(1)} <b>Lîsteya axaftinan</b> (ya herî nû li jor). Di lîsteya çepê de tomar li gorî rojê ne: navbera demê, ziman û hejmara hevokan xuya dibin. Gava hûn li yekê bitikînin,
      hemû hevok bi dem û wergerê xwe li milê rastê vedibin. Axaftina ku niha tê tomarkirin bi nîşana <b>berdewam e</b> tê nîşandan.</li>
  <li>${no(2)} <b>Daxe</b>: axaftina hilbijartî wek pelê nivîsê tomar dike (hûn peldankê hilbijêrin).</li>
  <li>${no(3)} <b>Jê bibe</b>: axaftina hilbijartî jê dibe.</li>
  <li>${no(4)} <b>Hemûyî daxe</b>: hemû axaftinan di pelekî nivîsê de tomar dike.</li>
  <li>${no(5)} <b>Hemûyî jê bibe</b>: hemû tomaran jê dibe.</li>
</ol>
<p>Berî jêbirinê pejirandin tê xwestin; tomara jêbirî venagere. Hûn dikarin tomarkirinê di Mîhengan de bi
<b>Axaftinan li ser vê komputerê biparêze</b> bigirin.</p>`,
    },
    {
      id: "gizlilik", baslik: "Nepenî",
      icerik: `
<ul>
  <li><b>Deng nayê tomarkirin</b> û nayê şandin; li ser komputera we dibe nivîs.</li>
  <li><b>Ji bo wergerê</b> hevokên nivîsandî ji Google Translate re tên şandin. Di wergera offline de tiştek nayê şandin.</li>
  <li><b>Axaftin</b> (nivîsa hatî nivîsandin û wergera wê) tenê li ser vê komputerê, di ekrana <b>Dîrokê</b> de tên parastin û naçin tu derê.
      Heke hûn naxwazin, di Mîhengan de <b>Axaftinan li ser vê komputerê biparêze</b> bigirin; tomarên berê ji ekrana Dîrokê jê bibin.</li>
  <li>Bername hesab, e-name an agahiyên kesane naxwaze; statîstîkên bikaranînê kom nake.</li>
  <li>Pelên tomarê (motor_gunluk.txt, kurulum_gunluk.txt) nivîsa axaftinê tê de tune.</li>
</ul>
<p class="not"><b>Civîneke veşartî ye?</b> Li ser ekrana sereke <b>Bê înternet</b> bitikînin: tu nivîs naçe Google û
werger li ser komputera we tê kirin — ne pêwîst e hûn înternetê bigirin.</p>`,
    },
    {
      id: "sorun", baslik: "Çareserkirina pirsgirêkan",
      icerik: `
<dl>
  <dt>Binnivîs dernakeve, pîvera dengê nalive</dt>
  <dd>Gelo deng bi rastî ji komputerê tê? Bername guh dide hoparlor / guhkên <b>standard</b> ên pergalê.
      Heke bernameya civînê deng dişîne amûreke din, di mîhengên dengê de (Windows; li Linuxê mîhengên Deng) wê amûrê bikin standard.</dd>
  <dt>Li şûna wergerê nivîsa orîjînal xuya dibe</dt>
  <dd>Negihîştina Google heye û dibe ku modela offline hê nehatibe daxistin. Girêdana înternetê kontrol bikin;
      werger bi xwe vedigere.</dd>
  <dt>Binnivîs dereng tê</dt>
  <dd>Bernameyên din ên giran bigirin. Ji bo Kurmancî, heke “Kurdiya Mezin” li ser pêvajoyê dixebite, “Kurdiya Biçûk” an “Kurdiya Navîn” hilbijêrin.</dd>
  <dt>Zimanê şaş tê nivîsandin</dt>
  <dd>Hilbijartina <b>zimanê axaftinê</b> kontrol bikin; bername zimên bi xwe texmîn nake.</dd>
  <dt>Daxistina yekem nîvco ma</dt>
  <dd>Bernameyê dîsa vekin an <b>Dîsa biceribîne</b> bitikînin; daxistin ji cihê xwe berdewam dike.</dd>
</dl>`,
    },
    {
      id: "iletisim", baslik: "Têkilî",
      icerik: `
<p>Ji bo her tiştê derbarê bernameyê de, hûn dikarin li ser X bi hesabê <b>@seyfi_polat21</b> (x.com/seyfi_polat21) bi me re têkilî daynin.
Dema pirsgirêkekê radigihînin, binivîsin we çi kir û çi qewimî. Hesabên me yên din di beşa <b>Derbar</b> de ne.</p>
<p>Heke pêwîst be, pelên <b>motor_gunluk.txt</b> û <b>kurulum_gunluk.txt</b> (nivîsa axaftinê tê de tune) ji vê peldankê zêde bikin:
<code>%LOCALAPPDATA%\\MED LIVE TRANSLATE</code> — di darika navnîşanê ya File Explorer de binivîsin û Enter bitikînin.
Li Linuxê: <code>~/.local/share/med-live-translate</code></p>
<p class="kucuk-not">Modelên hatine bikaranîn: OpenAI Whisper (MIT), modelên Whisper ên Kurmancî yên samil24, Meta NLLB-200
(CC-BY-NC-4.0, tenê ji bo bikaranîna ne-bazirganî).</p>`,
    },
  ],
};

export const KILAVUZLAR: Record<KDil, Kilavuz> = { tr: TR, en: EN, kmr: KMR };
const DIL_ANAHTARI = "mlt-kilavuz-dili";
const GORULDU_ANAHTARI = "mlt-kilavuz-goruldu-v1";

const $ = (id: string) => document.getElementById(id) as HTMLElement;

function kayitliDil(): KDil {
  try {
    const d = localStorage.getItem(DIL_ANAHTARI) as KDil | null;
    if (d && d in KILAVUZLAR) return d;
  } catch { /* depolama kapalı */ }
  return "tr";
}

/** Program dili değişince kılavuz da o dilde açılır (açıksa hemen yeniden çizilir). */
export function kilavuzDiliAyarla(dil: KDil) {
  try { localStorage.setItem(DIL_ANAHTARI, dil); } catch { /* yok say */ }
  if (!$("kilavuz").hidden) ciz(dil);
}

function ciz(dil: KDil) {
  const k = KILAVUZLAR[dil];
  try { localStorage.setItem(DIL_ANAHTARI, dil); } catch { /* yok say */ }
  const kok = $("kilavuz");
  kok.lang = dil === "kmr" ? "ku" : dil;
  $("kv-baslik").textContent = k.baslik;
  $("kv-alt").textContent = k.alt;
  kok.querySelectorAll<HTMLButtonElement>(".kv-sekme").forEach((b) => {
    b.classList.toggle("secili", b.dataset.dil === dil);
    b.setAttribute("aria-selected", String(b.dataset.dil === dil));
  });
  const nav = $("kv-nav");
  nav.innerHTML = `<div class="kv-nav-baslik">${k.icindekiler}</div>`;
  const metin = $("kv-metin");
  // ekran görüntüleri her dil için ayrı alınmıştır: /kilavuz/<dil>/<ad>.webp
  metin.innerHTML = k.bolumler.map((b) => `<section id="kv-${b.id}"><h2>${b.baslik}</h2>${b.icerik}</section>`).join("")
    .split('src="/kilavuz/').join(`src="/kilavuz/${dil}/`);
  for (const b of k.bolumler) {
    const a = document.createElement("button");
    a.className = "kv-bag";
    a.textContent = b.baslik;
    a.onclick = () => document.getElementById(`kv-${b.id}`)?.scrollIntoView({ behavior: "smooth", block: "start" });
    nav.appendChild(a);
  }
  metin.scrollTop = 0;
  // resim büyütme: tıklayınca tam boy
  metin.querySelectorAll("img").forEach((img) => {
    img.onclick = () => {
      const b = $("kv-buyuk");
      (b.querySelector("img") as HTMLImageElement).src = img.src;
      b.hidden = false;
    };
  });
}

export function kilavuzAc(dil: KDil = kayitliDil()) {
  ciz(dil);
  $("kilavuz").hidden = false;
  try { localStorage.setItem(GORULDU_ANAHTARI, "1"); } catch { /* yok say */ }
}

export function kilavuzKapat() {
  $("kilavuz").hidden = true;
  $("kv-buyuk").hidden = true;
}

export const kilavuzAcikMi = () => !$("kilavuz").hidden;

/** İlk kullanımda kılavuzu bir kez kendiliğinden açar. */
export function kilavuzIlkKez() {
  let goruldu = false;
  try { goruldu = localStorage.getItem(GORULDU_ANAHTARI) === "1"; } catch { goruldu = true; }
  if (!goruldu) kilavuzAc();
}

export function kilavuzKur() {
  $("kilavuz").querySelectorAll<HTMLButtonElement>(".kv-sekme").forEach((b) => {
    b.onclick = () => ciz(b.dataset.dil as KDil);
  });
  $("kv-kapat").onclick = kilavuzKapat;
  $("kv-buyuk").onclick = () => ($("kv-buyuk").hidden = true);
}
