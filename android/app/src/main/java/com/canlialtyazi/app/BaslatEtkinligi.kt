package com.canlialtyazi.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Dinlemeyi başlatan saydam ekran (uygulamadaki Başlat da, kontrol çubuğundaki Başlat da bunu açar).
 * İzinleri ister, "telefonda çalan ses" seçiliyse Android'in ses yakalama onayını alır, servisi başlatır
 * ve kapanır — kullanıcı toplantı uygulamasından ayrılmamış olur. Android, mikrofon ve ses yakalamayı
 * yalnızca önde bir ekrandan başlatmaya izin verdiği için bu ara ekran gerekiyor.
 */
class BaslatEtkinligi : AppCompatActivity() {
    private lateinit var ayarlar: Ayarlar

    private val izinIste = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { s ->
        if (s[Manifest.permission.RECORD_AUDIO] == false) bitir(Metinler.t("hata.izin_mik")) else devam()
    }
    private val projeksiyonIste = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { s ->
        if (s.resultCode != RESULT_OK || s.data == null) bitir(Metinler.t("hata.izin_ekran"))
        else { servisiBaslat(s.resultCode, s.data); finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ayarlar = Ayarlar(this)
        Metinler.dil = ayarlar.arayuzDili
        if (savedInstanceState != null) return  // döndürmede yeniden sorma
        if (!Modeller.hepsiHazir(this)) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish(); return
        }
        val gerekli = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) gerekli += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= 31 && ayarlar.kaynak == Kaynak.BLUETOOTH_MIK) gerekli += Manifest.permission.BLUETOOTH_CONNECT
        val eksik = gerekli.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (eksik.isNotEmpty()) izinIste.launch(eksik.toTypedArray()) else devam()
    }

    private fun devam() {
        if (!Aygitlar.bagliMi(this, ayarlar.kaynak)) {
            bitir(Metinler.t(if (ayarlar.kaynak == Kaynak.BLUETOOTH_MIK) "hata.bt_yok" else "hata.kablolu_yok")); return
        }
        if (ayarlar.kaynak == Kaynak.MEDYA && !AltyaziServisi.projeksiyonHazir) {
            projeksiyonIste.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
        } else { servisiBaslat(0, null); finish() }
    }

    private fun servisiBaslat(kod: Int, veri: Intent?) {
        val i = Intent(this, AltyaziServisi::class.java).setAction(AltyaziServisi.BASLAT)
        if (veri != null) i.putExtra(AltyaziServisi.E_KOD, kod).putExtra(AltyaziServisi.E_VERI, veri)
        ContextCompat.startForegroundService(this, i)
    }

    private fun bitir(hata: String) {
        Canli.durum(hata, false)
        android.widget.Toast.makeText(this, hata, android.widget.Toast.LENGTH_LONG).show()
        finish()
    }
}
