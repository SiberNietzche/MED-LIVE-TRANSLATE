package com.canlialtyazi.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * GitHub Releases üzerinden güncelleme (masaüstündeki src-tauri/src/guncelleme.rs ile aynı kurallar):
 * sürüm dosyası adı `_android.apk` ile biter, aynı sürümde `SHA256SUMS.txt` bulunur; özeti uyuşmayan
 * dosya kurulmaz. Denetim: uygulama açılınca (6 saatte bir) ve arka planda JobScheduler ile 6 saatte bir
 * (yalnızca internet varken). Yeni sürüm bulununca her sürüm için bir kez bildirim gönderilir.
 * GitHub'a yalnızca genel program adıyla sıradan bir istek gider; kişisel bilgi gönderilmez.
 * Not: yeni APK aynı imza anahtarıyla (android/imza/mlt.jks) imzalanmış olmalı, yoksa Android kurmaz.
 */
object Guncelleme {
    const val DEPO = "SiberNietzche/MED-LIVE-TRANSLATE"
    private const val KANAL = "guncelleme"
    private const val IS_NO = 2112
    const val ALTI_SAAT = 6L * 60 * 60 * 1000

    data class Surum(val surum: String, val yeni: Boolean, val sayfa: String, val notlar: String,
                     val apkAd: String?, val apkUrl: String?, val apkBoyut: Long, val shaUrl: String?)

    fun simdikiSurum(c: Context): String = c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "0"

    private fun sayilar(s: String) = s.trim().trimStart('v', 'V').split('-', '+')[0].split('.').map { it.toLongOrNull() ?: 0 }

    fun dahaYeni(yeni: String, eski: String): Boolean {
        val a = sayilar(yeni); val b = sayilar(eski)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private const val API = "https://api.github.com/repos/$DEPO/releases/latest"

    /** Yalnızca GitHub adreslerinden (https) indirilir. */
    private fun guvenli(adres: String): Boolean {
        val u = runCatching { Uri.parse(adres) }.getOrNull() ?: return false
        val h = u.host ?: return false
        return u.scheme == "https" && (h == "github.com" || h.endsWith(".githubusercontent.com"))
    }

    private fun baglan(adres: String) = (URL(adres).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15000; readTimeout = 60000; instanceFollowRedirects = true
        setRequestProperty("User-Agent", "MED-LIVE-TRANSLATE")
        setRequestProperty("Accept", "application/vnd.github+json")
    }

    /** Kısa hata kodları Metinler'de "gu.e.<kod>" olarak çevrilir. */
    class Hata(val kod: String) : Exception(kod)

    /** Ağ isteği yapar: ana iş parçacığında çağrılmaz. */
    fun denetle(c: Context): Surum {
        val b = try { baglan(API) } catch (e: Exception) { throw Hata("baglanti") }
        try {
            val kod = try { b.responseCode } catch (e: Exception) { throw Hata("baglanti") }
            when (kod) {
                200 -> {}
                404 -> throw Hata("yok")
                403, 429 -> throw Hata("sinir")
                else -> throw Hata("baglanti")
            }
            val j = JSONObject(b.inputStream.bufferedReader().use { it.readText() })
            val surum = j.optString("tag_name").trimStart('v', 'V')
            if (surum.isBlank()) throw Hata("yok")
            val dosyalar = j.optJSONArray("assets")
            var apk: JSONObject? = null; var sha: String? = null
            for (i in 0 until (dosyalar?.length() ?: 0)) {
                val d = dosyalar!!.getJSONObject(i)
                val ad = d.optString("name")
                if (ad.endsWith("_android.apk")) apk = d
                if (ad == "SHA256SUMS.txt") sha = d.optString("browser_download_url")
            }
            val s = Surum(surum, dahaYeni(surum, simdikiSurum(c)), j.optString("html_url"),
                j.optString("body").take(4000), apk?.optString("name"), apk?.optString("browser_download_url"),
                apk?.optLong("size") ?: 0, sha)
            Ayarlar(c).apply { sonDenetim = System.currentTimeMillis(); sonBulunan = if (s.yeni) s.surum else "" }
            return s
        } finally { b.disconnect() }
    }

    /** Yeni sürüm için (her sürümde bir kez) bildirim gönderir. */
    fun bildir(c: Context, s: Surum) {
        val a = Ayarlar(c)
        if (!s.yeni || a.bildirilenSurum == s.surum) return
        a.bildirilenSurum = s.surum
        Metinler.dil = a.arayuzDili
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(KANAL, Metinler.t("gu.kanal"), NotificationManager.IMPORTANCE_DEFAULT))
        val ac = PendingIntent.getActivity(c, 7, Intent(c, HakkindaEtkinligi::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(c, KANAL)
            .setSmallIcon(R.drawable.ic_bildirim)
            .setContentTitle("MED LIVE TRANSLATE")
            .setContentText(Metinler.t("gu.bildirim", s.surum))
            .setContentIntent(ac).setAutoCancel(true)
            .build()
        runCatching { nm.notify(IS_NO, n) } // bildirim izni yoksa sessizce geçilir (uygulama içinde yine görünür)
    }

    /** APK'yı indirir ve SHA256SUMS.txt ile doğrular. ilerleme(inen, toplam). */
    fun indir(c: Context, s: Surum, iptal: () -> Boolean, ilerleme: (Long, Long) -> Unit): File {
        val ad = s.apkAd ?: throw Hata("dosya_yok")
        val url = s.apkUrl ?: throw Hata("dosya_yok")
        val shaUrl = s.shaUrl ?: throw Hata("sha_yok")
        if (!guvenli(url) || !guvenli(shaUrl) || !ad.all { it.isLetterOrDigit() || it in "._-" }) throw Hata("adres")
        val ozetler = baglan(shaUrl).run { try { inputStream.bufferedReader().use { it.readText() } } catch (e: Exception) { throw Hata("baglanti") } finally { disconnect() } }
        val beklenen = ozetler.lineSequence().mapNotNull { l ->
            val p = l.trim().split(Regex("\\s+"))
            if (p.size >= 2 && p[1].trimStart('*') == ad) p[0].lowercase() else null
        }.firstOrNull() ?: throw Hata("sha_yok")

        val klasor = File(c.cacheDir, "guncelleme").apply { deleteRecursively(); mkdirs() }
        val hedef = File(klasor, ad)
        val b = baglan(url)
        try {
            if (b.responseCode != 200) throw Hata("baglanti")
            val toplam = b.contentLengthLong.takeIf { it > 0 } ?: s.apkBoyut
            val ozet = MessageDigest.getInstance("SHA-256")
            var inen = 0L; var son = 0L
            b.inputStream.use { g -> FileOutputStream(hedef).use { f ->
                val tampon = ByteArray(128 * 1024)
                while (true) {
                    if (iptal()) throw InterruptedException()
                    val n = g.read(tampon); if (n < 0) break
                    f.write(tampon, 0, n); ozet.update(tampon, 0, n); inen += n
                    val simdi = System.nanoTime()
                    if (simdi - son > 200_000_000) { son = simdi; ilerleme(inen, toplam) }
                }
            } }
            ilerleme(inen, toplam)
            val bulunan = ozet.digest().joinToString("") { "%02x".format(it) }
            if (bulunan != beklenen) { hedef.delete(); throw Hata("sha") }
            return hedef
        } catch (e: Hata) { throw e } catch (e: InterruptedException) { throw e } catch (e: Exception) {
            throw Hata("baglanti")
        } finally { b.disconnect() }
    }

    /** Bu uygulamanın APK kurma izni var mı (Android "bilinmeyen uygulamaları yükle" izni). */
    fun kurmaIzniVar(c: Context) = c.packageManager.canRequestPackageInstalls()

    fun kurmaIzniAc(c: Context) {
        c.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Android'in kurulum penceresini açar (kullanıcı "Güncelle"ye basar). */
    fun kur(c: Context, apk: File) {
        val uri = FileProvider.getUriForFile(c, "${c.packageName}.dosyalar", apk)
        c.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Arka plan denetimini kurar ya da kaldırır (Ayarlar'daki seçeneğe göre). */
    fun zamanla(c: Context) = runCatching { zamanlaIc(c) }  // denetim kurulamasa da uygulama açılır

    private fun zamanlaIc(c: Context) {
        val js = c.getSystemService(JobScheduler::class.java)
        if (!Ayarlar(c).guncellemeOtomatik) { js.cancel(IS_NO); return }
        if (js.getPendingJob(IS_NO) != null) return
        js.schedule(JobInfo.Builder(IS_NO, ComponentName(c, GuncellemeIsi::class.java))
            .setPeriodic(ALTI_SAAT)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .build())
    }
}

/** Arka planda 6 saatte bir çalışan denetim (JobScheduler). */
class GuncellemeIsi : JobService() {
    override fun onStartJob(p: JobParameters): Boolean {
        Thread {
            runCatching {
                if (Ayarlar(this).guncellemeOtomatik) Guncelleme.bildir(this, Guncelleme.denetle(this))
            }
            jobFinished(p, false)
        }.start()
        return true
    }
    override fun onStopJob(p: JobParameters) = false
}
