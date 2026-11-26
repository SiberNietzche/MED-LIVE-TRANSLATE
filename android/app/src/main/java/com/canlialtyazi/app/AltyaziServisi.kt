package com.canlialtyazi.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/** Servis ile ekranlar (uygulama, kontrol çubuğu) arasındaki olaylar. Ekran açılınca son durumu alır. */
object Canli {
    data class Satir(val zaman: Long, val dil: String, val hedef: String, val orijinal: String, val ceviri: String?)

    interface Dinleyici {
        fun durum(metin: String, calisiyor: Boolean) {}
        fun altyazi(s: Satir) {}
        fun seviye(db: Double) {}
        fun duraklama(duraklatildi: Boolean) {}
    }

    private val ana = Handler(Looper.getMainLooper())
    private val dinleyiciler = mutableListOf<Dinleyici>()
    val gecmis = mutableListOf<Satir>()
    var sonDurum = ""; private set
    var calisiyor = false; private set
    var duraklatildi = false; private set
    /** Uygulamanın ekranı önde mi: öndeyse şerit ve kontrol çubuğu gizlenir (uygulamada zaten aynı düğmeler var). */
    var uygulamaOnde = false
        set(v) { field = v; onPlanDinleyici?.invoke() }
    var onPlanDinleyici: (() -> Unit)? = null

    fun ekle(d: Dinleyici) { dinleyiciler.add(d); d.durum(sonDurum, calisiyor); d.duraklama(duraklatildi) }
    fun cikar(d: Dinleyici) { dinleyiciler.remove(d) }
    fun durum(metin: String, calisiyor: Boolean) = ana.post {
        sonDurum = metin; this.calisiyor = calisiyor
        if (!calisiyor) duraklatildi = false
        dinleyiciler.toList().forEach { it.durum(metin, calisiyor) }
    }
    fun duraklama(d: Boolean) = ana.post { duraklatildi = d; dinleyiciler.toList().forEach { it.duraklama(d) } }
    fun altyazi(s: Satir) = ana.post {
        gecmis.add(s); if (gecmis.size > 2000) gecmis.removeAt(0)
        dinleyiciler.toList().forEach { it.altyazi(s) }
    }
    fun seviye(db: Double) = ana.post { dinleyiciler.toList().forEach { it.seviye(db) } }
}

/**
 * Dinleme servisi: sesi yakalar (medya sesi ya da seçilen mikrofon), konuşmayı bölümlere ayırır,
 * whisper.cpp ile yazıya döker, çevirir; sonucu uygulamaya, altyazı şeridine ve kontrol çubuğuna gönderir.
 * Kontrol çubuğu açıksa dinleme durunca da servis ayakta kalır (çubuktan yeniden başlatılabilsin).
 */
class AltyaziServisi : Service() {
    companion object {
        const val BASLAT = "baslat"
        const val DURDUR = "durdur"
        const val CUBUK_AC = "cubuk_ac"
        const val CUBUK_KAPAT = "cubuk_kapat"
        const val DIL = "dil"              // ayarlardaki dillere geç (dinleme sürerken ses izni yeniden istenmez)
        const val DURAKLAT = "duraklat"    // aç / kapat
        const val SERIT = "serit"          // altyazı şeridini göster / gizle
        const val KAPAT = "kapat"          // dinleme durmuşken tutulan ekran paylaşımını da bırak, servisi kapat
        /** Ekran paylaşımı (ses yakalama onayı) tutuluyor mu: Başlat yeniden onay istemesin. */
        @Volatile var projeksiyonHazir = false
            private set
        const val E_KOD = "projeksiyon_kod"
        const val E_VERI = "projeksiyon_veri"
        private const val KANAL = "canli"
        private const val NO = 1
        private const val ETIKET = "AltyaziServisi"
        private const val KUYRUK_SN = 20.0   // geride kalırsa en eski konuşma atlanır: altyazı gerçek zamandan kopmasın

        fun gonder(c: Context, eylem: String) =
            c.startService(Intent(c, AltyaziServisi::class.java).setAction(eylem))
    }

    private lateinit var ayarlar: Ayarlar
    private val ana = Handler(Looper.getMainLooper())
    private var projeksiyon: MediaProjection? = null
    private var kayit: AudioRecord? = null
    private var kaynak = Kaynak.MEDYA
    @Volatile private var dinliyor = false
    @Volatile private var duraklat = false
    // her başlatma / dil değişimi yeni bir tur: eski iş parçacıkları yeni turun sesini işlemesin
    @Volatile private var tur = 0
    @Volatile private var bolucu: Bolucu? = null
    // görüşme sırasında Android mikrofonu bize sessiz verebilir (bkz. AltyaziErisilebilirlik)
    private var kayitIzleyici: AudioManager.AudioRecordingCallback? = null
    private var susturuldu = false
    @Volatile private var kuyruk = LinkedBlockingDeque<FloatArray>()
    private var okuyucu: Thread? = null
    private val cevirmenIs = Executors.newSingleThreadExecutor()
    private val cevirmen = Cevirmen(this)
    private var serit: Serit? = null
    private var cubuk: KontrolCubugu? = null
    private var btAcik = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ayarlar = Ayarlar(this)
        Metinler.dil = ayarlar.arayuzDili
        Canli.onPlanDinleyici = { ana.post { pencereleriGuncelle() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Metinler.dil = ayarlar.arayuzDili
        when (intent?.action) {
            BASLAT -> baslat(intent)
            DURDUR -> { dinlemeyiDurdur(); bitirGerekirse() }
            KAPAT -> { dinlemeyiDurdur(); projeksiyonuBirak(); bitirGerekirse() }
            CUBUK_AC -> { if (cubuk == null && Serit.izinVar(this)) cubuk = KontrolCubugu(this, ayarlar); onPlanaGec(); pencereleriGuncelle() }
            CUBUK_KAPAT -> { cubuk?.kaldir(); cubuk = null; bitirGerekirse() }
            DIL -> if (dinliyor) cozucuBaslat() else cubuk?.yenile()
            DURAKLAT -> if (dinliyor) {
                duraklat = !duraklat
                bolucu?.sifirla(); kuyruk.clear()
                Canli.duraklama(duraklat)
                Canli.durum(Metinler.t(if (duraklat) "durum.duraklatildi" else "durum.dinleniyor"), true)
            }
            SERIT -> { ayarlar.seritAcik = !ayarlar.seritAcik; pencereleriGuncelle() }
        }
        if (!dinliyor && cubuk == null && projeksiyon == null) stopSelf()
        return START_NOT_STICKY
    }

    /** Ön plan bildirimi ve türü: dinlerken ses yakalama türü, yalnızca çubuk açıkken "özel kullanım". */
    private fun onPlanaGec() {
        val tur = when {
            // ekran paylaşımı dinleme durunca da tutulur (onay bir kez): servis bu türde ayakta kalmalı
            (dinliyor && kaynak == Kaynak.MEDYA) || projeksiyon != null -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            dinliyor -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            else -> 0
        }
        startForeground(NO, bildirim(), tur)
    }

    private fun bitirGerekirse() {
        if (dinliyor) return
        if (cubuk != null || projeksiyon != null) onPlanaGec() else { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    }

    // ------------------------------------------------------------------ başlatma / durdurma
    private fun baslat(intent: Intent) {
        dinlemeyiDurdur()
        kaynak = ayarlar.kaynak
        if (kaynak != Kaynak.MEDYA) projeksiyonuBirak()  // mikrofona geçildi: paylaşım gereksiz
        dinliyor = true
        onPlanaGec()
        try {
            kayit = sesKaynagiAc(kaynak, intent)
        } catch (e: Exception) {
            Log.w(ETIKET, "ses kaynağı açılamadı", e)
            dinliyor = false
            dinlemeyiDurdur()
            projeksiyonuBirak()  // bozuk / süresi dolmuş paylaşım: sonraki Başlat yeniden onay alsın
            Canli.durum(e.message ?: e.javaClass.simpleName, false)
            bitirGerekirse()
            return
        }
        duraklat = false
        Canli.duraklama(false)
        val r = kayit!!
        if (kaynak != Kaynak.MEDYA) susturmaIzle(r)
        okuyucu = Thread({ oku(r) }, "ses").apply { start() }
        cozucuBaslat()
        pencereleriGuncelle()
    }

    /** Yeni tur: ayarlardaki dillerle yeni bölücü + çözücü. Ses yakalama sürer (izin yeniden istenmez). */
    private fun cozucuBaslat() {
        val t = ++tur
        val konusma = ayarlar.konusma
        val hedef = ayarlar.hedef
        val q = LinkedBlockingDeque<FloatArray>()
        kuyruk = q
        bolucu = Bolucu(konusma == "kmr") { bolum -> kuyrugaEkle(q, bolum) }
        Thread({ coz(konusma, hedef, q, t) }, "cozucu").start()
        onPlanaGec()  // bildirimdeki diller güncellensin
        cubuk?.yenile()
    }

    /**
     * Android "susturdu" mu? Görüşme uygulaması mikrofonu kullanırken sıradan uygulamaya sessizlik verilir.
     * Durum satırında kullanıcıya erişilebilirlik hizmetini açması söylenir.
     */
    private fun susturmaIzle(r: AudioRecord) {
        val am = getSystemService(AudioManager::class.java)
        val oturum = r.audioSessionId
        susturuldu = false
        fun bak(liste: List<AudioRecordingConfiguration>) {
            val benim = liste.firstOrNull { it.clientAudioSessionId == oturum } ?: return
            val sessiz = benim.isClientSilenced
            if (sessiz == susturuldu || !dinliyor) return
            susturuldu = sessiz
            Log.i(ETIKET, "mikrofon susturuldu: $sessiz (erişilebilirlik hizmeti açık: ${AltyaziErisilebilirlik.acikMi(this)})")
            if (sessiz) Canli.durum(Metinler.t("durum.susturuldu"), true)
            else if (Canli.sonDurum == Metinler.t("durum.susturuldu")) Canli.durum(Metinler.t("durum.dinleniyor"), true)
        }
        val izleyici = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) = bak(configs)
        }
        am.registerAudioRecordingCallback(izleyici, ana)
        kayitIzleyici = izleyici
        ana.postDelayed({ if (kayitIzleyici === izleyici) bak(am.activeRecordingConfigurations) }, 1500)
    }

    private fun dinlemeyiDurdur() {
        val calisiyordu = dinliyor || kayit != null
        dinliyor = false
        tur++
        kayitIzleyici?.let { runCatching { getSystemService(AudioManager::class.java).unregisterAudioRecordingCallback(it) } }
        kayitIzleyici = null; susturuldu = false
        runCatching { kayit?.stop() }
        runCatching { kayit?.release() }
        kayit = null
        okuyucu?.join(1000); okuyucu = null
        bolucu = null
        // ekran paylaşımı (projeksiyon) burada bırakılmaz: sonraki Başlat'ta Android yeniden onay sormasın
        bluetoothKapat()
        serit?.kaldir(); serit = null
        // çeviri modeli ~0,7 GB bellek tutar: dinleme bitince bırakılır (sıradaki çeviriler bittikten sonra)
        runCatching { cevirmenIs.execute { Nllb.kapat() } }
        if (calisiyordu) Canli.durum(Metinler.t("durum.durdu"), false)
        cubuk?.yenile()
    }

    private fun projeksiyonuBirak() {
        val p = projeksiyon ?: return
        projeksiyon = null
        projeksiyonHazir = false
        runCatching { p.stop() }
    }

    /** Şerit ve çubuk: uygulama öndeyken gizli (pencereden tamamen kaldırılır: dokunmaları yakalamasın). */
    private fun pencereleriGuncelle() {
        val onde = Canli.uygulamaOnde
        if (dinliyor && ayarlar.seritAcik && Serit.izinVar(this)) {
            if (serit == null) serit = Serit(this, ayarlar)
            if (onde) serit?.kaldir() else serit?.goster()
        } else { serit?.kaldir(); serit = null }
        cubuk?.let { if (onde) it.kaldir() else it.goster() }
    }

    // ------------------------------------------------------------------ ses kaynağı
    private fun sesKaynagiAc(kaynak: Kaynak, intent: Intent): AudioRecord {
        val bicim = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(ORNEKLEME)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val tampon = maxOf(AudioRecord.getMinBufferSize(ORNEKLEME, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT), ORNEKLEME * 4)
        val am = getSystemService(AudioManager::class.java)
        val r: AudioRecord = when (kaynak) {
            Kaynak.MEDYA -> {
                @Suppress("DEPRECATION")
                val veri: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(E_VERI, Intent::class.java)
                    else intent.getParcelableExtra(E_VERI)
                // önceki onayla açılmış paylaşım varsa o kullanılır (Android'in onay ekranı bir kez görünür)
                val p = if (veri == null) projeksiyon ?: throw IllegalStateException(Metinler.t("hata.izin_ekran")) else {
                    projeksiyonuBirak()
                    val yeni = getSystemService(MediaProjectionManager::class.java).getMediaProjection(intent.getIntExtra(E_KOD, 0), veri)
                        ?: throw IllegalStateException(Metinler.t("hata.izin_ekran"))
                    // kullanıcı paylaşımı sistemden durdurursa dinleme de biter, sonraki Başlat yeniden onay ister
                    yeni.registerCallback(object : MediaProjection.Callback() {
                        override fun onStop() { ana.post { if (projeksiyon === yeni) { dinlemeyiDurdur(); projeksiyonuBirak(); bitirGerekirse() } } }
                    }, ana)
                    projeksiyon = yeni
                    projeksiyonHazir = true
                    yeni
                }
                val ayar = AudioPlaybackCaptureConfiguration.Builder(p)
                    .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(android.media.AudioAttributes.USAGE_UNKNOWN)
                    .build()
                AudioRecord.Builder().setAudioFormat(bicim).setBufferSizeInBytes(tampon)
                    .setAudioPlaybackCaptureConfig(ayar).build()
            }
            Kaynak.BLUETOOTH_MIK -> {
                if (!Aygitlar.bluetoothBagli(am)) throw IllegalStateException(Metinler.t("hata.bt_yok"))
                bluetoothAc(am)
                // görüşme yolu açıldıktan sonra kulaklığın mikrofonu giriş olarak görünür
                AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                    .setAudioFormat(bicim).setBufferSizeInBytes(tampon).build()
                    .also { r -> Aygitlar.bluetoothGiris(am)?.let { r.preferredDevice = it } }
            }
            Kaynak.KABLOLU_MIK -> {
                val aygit = Aygitlar.kabloluGiris(am) ?: throw IllegalStateException(Metinler.t("hata.kablolu_yok"))
                AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(bicim).setBufferSizeInBytes(tampon).build()
                    .also { it.preferredDevice = aygit }
            }
            Kaynak.TELEFON_MIK -> {
                AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(bicim).setBufferSizeInBytes(tampon).build()
                    .also { r -> Aygitlar.dahiliGiris(am)?.let { r.preferredDevice = it } }
            }
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); throw IllegalStateException(Metinler.t("hata.ses", "AudioRecord")) }
        r.startRecording()
        return r
    }

    /** Bluetooth kulaklığın mikrofonu ancak "görüşme" yolu açılınca kullanılabilir (SCO). */
    @Suppress("DEPRECATION")
    private fun bluetoothAc(am: AudioManager) {
        if (Build.VERSION.SDK_INT >= 31) {
            val aygit = am.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            } ?: throw IllegalStateException(Metinler.t("hata.bt_yok"))
            am.setCommunicationDevice(aygit)
        } else {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.startBluetoothSco()
            am.isBluetoothScoOn = true
            val son = SystemClock.elapsedRealtime() + 4000
            while (!am.isBluetoothScoOn && SystemClock.elapsedRealtime() < son) Thread.sleep(100)
        }
        btAcik = true
    }

    @Suppress("DEPRECATION")
    private fun bluetoothKapat() {
        if (!btAcik) return
        val am = getSystemService(AudioManager::class.java)
        if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
        else { am.isBluetoothScoOn = false; am.stopBluetoothSco(); am.mode = AudioManager.MODE_NORMAL }
        btAcik = false
    }

    // ------------------------------------------------------------------ işleme
    private fun oku(r: AudioRecord) {
        val tampon = FloatArray(1600)
        var sonSeviye = 0L
        while (dinliyor && kayit === r) {
            val n = r.read(tampon, 0, tampon.size, AudioRecord.READ_BLOCKING)
            if (n < 0) {
                if (dinliyor && kayit === r) ana.post { Canli.durum(Metinler.t("hata.ses", "kod $n"), false); dinlemeyiDurdur(); bitirGerekirse() }
                break
            }
            if (n == 0 || duraklat) continue
            val b = bolucu ?: continue
            b.ekle(tampon, n)
            val simdi = SystemClock.elapsedRealtime()
            if (simdi - sonSeviye > 150) { sonSeviye = simdi; Canli.seviye(b.seviyeDb) }
        }
    }

    private fun kuyrugaEkle(q: LinkedBlockingDeque<FloatArray>, bolum: FloatArray) {
        q.addLast(bolum)
        while (q.sumOf { it.size } / ORNEKLEME.toDouble() > KUYRUK_SN && q.size > 1) q.pollFirst()
    }

    private fun suruyor(t: Int) = dinliyor && tur == t

    private fun coz(konusma: String, hedef: String, q: LinkedBlockingDeque<FloatArray>, t: Int) {
        Canli.durum(Metinler.t("durum.yukleniyor"), true)
        Whisper.arkaUclariYukle(applicationInfo.nativeLibraryDir)
        val model = Modeller.dilIcin(konusma)
        val tutamac = Whisper.yukle(Modeller.dosya(this, model).path)
        if (tutamac == 0L) {
            if (suruyor(t)) ana.post { Canli.durum(Metinler.t("hata.model"), false); dinlemeyiDurdur(); bitirGerekirse() }
            return
        }
        if (!suruyor(t)) { Whisper.birak(tutamac); return }
        Log.i(ETIKET, "whisper: " + Whisper.sistemBilgisi())
        val isParcacigi = Whisper.isParcacigi()
        val vad = Whisper.vadYolu(this)
        Log.i(ETIKET, "iş parçacığı: $isParcacigi, konuşma algılayıcı: ${vad.isNotEmpty()}")
        Canli.durum(Metinler.t(if (duraklat) "durum.duraklatildi" else "durum.dinleniyor"), true)
        val dil = Diller.bul(konusma)
        // telefonun hızına göre kademe (hatırlanır): 2 = hafif — daha dar pencere, daha kısa parçalar
        var kademe = ayarlar.hizKademesi
        bolucu?.enUzun = if (kademe == 2) 7.0 else 9.0
        val oranlar = ArrayDeque<Double>()  // işleme süresi / konuşma süresi (son 4 parça)
        var rahat = 0                       // art arda rahat geçen parça sayısı
        // çevrimdışı çeviri: model indirilmişse ve açıksa hiçbir metin internete gitmez; ilk cümle beklemesin diye önceden yüklenir
        val yalnizYerel = ayarlar.cevrimdisi && Nllb.hazir(this)
        if (yalnizYerel && hedef != konusma) cevirmenIs.execute { Nllb.hazirla(this) }
        val kurmanci = konusma == "kmr"
        if (kurmanci) Kurmanci.hazirla(this)  // yazım düzeltme sözlüğü
        // Kürtçe Orta (Omnilingual): seçiliyse ve indirilmişse; yüklenemezse Kürtçe Küçük ile sürer
        val orta = kurmanci && ayarlar.kurmanciOrta && Modeller.omniHazir(this) && Omni.hazirla(this, isParcacigi)
        Log.i(ETIKET, "Kürtçe Orta: $orta")
        try {
            while (suruyor(t)) {
                val bolum = q.pollFirst(300, TimeUnit.MILLISECONDS) ?: continue
                seviyeAyarla(bolum)
                val saniye = bolum.size / ORNEKLEME.toDouble()
                val pencere = Whisper.pencere(bolum.size, if (kademe == 2) 0.5 else 1.0)
                val t0 = SystemClock.elapsedRealtime()
                var metin = if (!orta) Whisper.coz(tutamac, bolum, dil.whisper, isParcacigi, vad, pencere).trim() else {
                    val (m, yedek) = Omni.ayikla(Omni.coz(bolum))
                    // Latin dışı yazdıysa (Arap/Uygur harfi) parça dili sabit Kürtçe Küçük ile yeniden çözülür
                    Omni.buyukBasla(if (!yedek) m else Omni.ayikla(Whisper.coz(tutamac, bolum, dil.whisper, isParcacigi, vad, pencere).trim()).first)
                }
                val gecen = (SystemClock.elapsedRealtime() - t0) / 1000.0
                Log.i(ETIKET, "çözme ${"%.2f".format(gecen)} sn (${"%.1f".format(saniye)} sn ses, pencere $pencere, kademe $kademe)")
                // kendiliğinden ayar: telefon konuşmaya yetişemiyorsa (oran > 0,9) hafif kademeye geç;
                // uzun süre rahatsa (oran < 0,45) normale dön. Sıradaki parça da sayılır (birikme).
                oranlar.addLast(gecen / maxOf(saniye, 1.0)); if (oranlar.size > 4) oranlar.removeFirst()
                val ort = oranlar.average()
                if (kademe == 1 && oranlar.size >= 3 && (ort > 0.9 || q.size >= 3)) {
                    kademe = 2; ayarlar.hizKademesi = 2; bolucu?.enUzun = 7.0; oranlar.clear(); rahat = 0
                    Log.i(ETIKET, "telefon yetişemiyor (oran ${"%.2f".format(ort)}): hafif kademe")
                } else if (kademe == 2) {
                    rahat = if (ort < 0.45 && q.isEmpty()) rahat + 1 else 0
                    if (rahat >= 10) {
                        kademe = 1; ayarlar.hizKademesi = 1; bolucu?.enUzun = 9.0; oranlar.clear(); rahat = 0
                        Log.i(ETIKET, "telefon rahat: normal kademe")
                    }
                }
                metin = Temizle.tekrar(metin)
                if (kurmanci) metin = Kurmanci.duzelt(this, metin)  // sözlükte olmayan kelimeleri düzelt
                if (metin.isEmpty() || Temizle.uydurmaMi(metin) || !suruyor(t)) continue
                val zaman = System.currentTimeMillis()
                cevirmenIs.execute {
                    val ceviri = if (hedef == konusma) metin else cevirmen.cevir(metin, konusma, hedef, yalnizYerel)
                    if (!suruyor(t)) return@execute
                    val uyarilar = listOf(Metinler.t("durum.cevirisiz"), Metinler.t("durum.yerel"))
                    when {
                        ceviri == null && hedef != konusma -> Canli.durum(uyarilar[0], true)
                        // internet yok ama çevrimdışı model çeviriyor (çevrimdışı modda bu zaten beklenen durum)
                        hedef != konusma && cevirmen.sonKaynak == "yerel" && !yalnizYerel -> Canli.durum(uyarilar[1], true)
                        Canli.sonDurum in uyarilar -> Canli.durum(Metinler.t("durum.dinleniyor"), true)
                    }
                    val s = Canli.Satir(zaman, konusma, hedef, metin, ceviri)
                    Canli.altyazi(s)
                    serit?.yaz(s)
                }
            }
        } finally {
            Whisper.birak(tutamac)
            if (orta) Omni.kapat()  // ~1 GB bellek: dinleme bitince bırakılır
        }
    }

    override fun onDestroy() {
        dinlemeyiDurdur()
        projeksiyonuBirak()
        cubuk?.kaldir(); cubuk = null
        Canli.onPlanDinleyici = null
        cevirmenIs.shutdown()
        super.onDestroy()
    }

    private fun bildirim(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(KANAL, Metinler.t("bildirim.kanal"), NotificationManager.IMPORTANCE_LOW))
        val ac = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, KANAL)
            .setSmallIcon(R.drawable.ic_bildirim)
            .setContentIntent(ac)
            .setOngoing(true)
        if (dinliyor) {
            val durdur = PendingIntent.getService(this, 1, Intent(this, AltyaziServisi::class.java).setAction(DURDUR),
                PendingIntent.FLAG_IMMUTABLE)
            b.setContentTitle(Metinler.t("bildirim.baslik"))
                .setContentText(Metinler.t("bildirim.metin", Metinler.dilAdi(ayarlar.konusma), Metinler.dilAdi(ayarlar.hedef)))
                .addAction(Notification.Action.Builder(null, Metinler.t("durdur"), durdur).build())
        } else if (projeksiyon != null) {
            // dinleme durdu ama paylaşım onayı tutuluyor: Başlat yeniden sormaz; "Kapat" bırakır
            val kapat = PendingIntent.getService(this, 3, Intent(this, AltyaziServisi::class.java).setAction(KAPAT),
                PendingIntent.FLAG_IMMUTABLE)
            b.setContentTitle(Metinler.t("bildirim.hazir"))
                .addAction(Notification.Action.Builder(null, Metinler.t("bildirim.kapat"), kapat).build())
        } else {
            b.setContentTitle(Metinler.t("cubuk.bildirim"))
        }
        if (cubuk != null) {
            val kapat = PendingIntent.getService(this, 2, Intent(this, AltyaziServisi::class.java).setAction(CUBUK_KAPAT),
                PendingIntent.FLAG_IMMUTABLE)
            b.addAction(Notification.Action.Builder(null, Metinler.t("cubuk.kapat"), kapat).build())
        }
        return b.build()
    }
}

/** Ses giriş aygıtları: kablolu / Bluetooth kulaklık takılı mı. */
object Aygitlar {
    private fun girisler(am: AudioManager) = am.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()

    fun dahiliGiris(am: AudioManager) = girisler(am).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
    fun kabloluGiris(am: AudioManager) = girisler(am).firstOrNull {
        it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
    }
    fun bluetoothGiris(am: AudioManager) = girisler(am).firstOrNull {
        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || (Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
    }

    /** Bluetooth kulaklık bağlı mı: Android 12+ görüşme aygıtları listesinden (mikrofon ancak görüşme
     *  yolu açılınca giriş listesine düşebiliyor), daha eskilerde giriş listesinden. */
    fun bluetoothBagli(am: AudioManager): Boolean =
        if (Build.VERSION.SDK_INT >= 31) am.availableCommunicationDevices.any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        } else bluetoothGiris(am) != null

    fun bagliMi(c: Context, k: Kaynak): Boolean {
        val am = c.getSystemService(AudioManager::class.java)
        return when (k) {
            Kaynak.MEDYA, Kaynak.TELEFON_MIK -> true
            Kaynak.KABLOLU_MIK -> kabloluGiris(am) != null
            Kaynak.BLUETOOTH_MIK -> bluetoothBagli(am)
        }
    }
}
