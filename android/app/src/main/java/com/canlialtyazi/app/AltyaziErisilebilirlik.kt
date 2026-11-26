package com.canlialtyazi.app

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Boş erişilebilirlik hizmeti: hiçbir ekran içeriğini okumaz, hiçbir olayı dinlemez, hiçbir şey yapmaz.
 *
 * Neden var: Android 10+ bir görüşme sürerken (WhatsApp, Zoom, Meet… hoparlör açık) mikrofonu yalnızca
 * görüşme uygulamasına verir; sıradan uygulamalar sessizlik alır ("Sharing audio input", developer.android.com).
 * Kuralın istisnası kullanıcının açtığı erişilebilirlik hizmetleridir — Google Canlı Altyazı da bu yolla çalışır.
 * Kullanıcı bu hizmeti Ayarlar → Erişilebilirlik'ten açınca uygulama görüşme sırasında da mikrofonu duyabilir.
 */
class AltyaziErisilebilirlik : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        /** Kullanıcı hizmeti açmış mı (Ayarlar'daki açık hizmetler listesine bakılır). */
        fun acikMi(c: Context): Boolean {
            val ad = ComponentName(c, AltyaziErisilebilirlik::class.java)
            val liste = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return liste.split(':').any { ComponentName.unflattenFromString(it) == ad }
        }
    }
}
