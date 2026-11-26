// MED LIVE TRANSLATE — whisper.cpp ile Kotlin arasındaki köprü (JNI).
// Bir model bağlamı yalnızca tek bir iş parçacığından kullanılır (AltyaziServisi'ndeki çözücü).
#include <jni.h>
#include <android/log.h>
#include <cmath>
#include <string>
#include <vector>
#include "ggml-backend.h"
#include "whisper.h"

#define GUNLUK(...) __android_log_print(ANDROID_LOG_INFO, "mlt_whisper", __VA_ARGS__)

// Java'nın NewStringUTF'i 4 baytlık UTF-8'i (ör. emoji) kabul etmez: String(byte[], UTF-8) ile kurulur.
static jstring utf8_dize(JNIEnv* env, const std::string& s) {
    jbyteArray b = env->NewByteArray(static_cast<jsize>(s.size()));
    env->SetByteArrayRegion(b, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte*>(s.data()));
    jclass sinif = env->FindClass("java/lang/String");
    jmethodID kurucu = env->GetMethodID(sinif, "<init>", "([BLjava/lang/String;)V");
    jstring kod = env->NewStringUTF("UTF-8");
    auto sonuc = static_cast<jstring>(env->NewObject(sinif, kurucu, b, kod));
    env->DeleteLocalRef(b);
    env->DeleteLocalRef(kod);
    env->DeleteLocalRef(sinif);
    return sonuc;
}

static std::string dize(JNIEnv* env, jstring j) {
    if (!j) return "";
    const char* p = env->GetStringUTFChars(j, nullptr);
    std::string s(p);
    env->ReleaseStringUTFChars(j, p);
    return s;
}

/** İşlemci sürümlerini (libggml-cpu-*.so) uygulamanın kitaplık klasöründen yükler: en hızlı uyumlu olan seçilir. */
extern "C" JNIEXPORT void JNICALL
Java_com_canlialtyazi_app_Whisper_arkaUclariYukle(JNIEnv* env, jobject, jstring klasor) {
    static bool yuklendi = false;
    if (yuklendi) return;
    ggml_backend_load_all_from_path(dize(env, klasor).c_str());
    for (size_t i = 0; i < ggml_backend_reg_count(); ++i) GUNLUK("arka uç: %s", ggml_backend_reg_name(ggml_backend_reg_get(i)));
    yuklendi = true;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_canlialtyazi_app_Whisper_yukle(JNIEnv* env, jobject, jstring yol) {
    std::string p = dize(env, yol);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context* ctx = whisper_init_from_file_with_params(p.c_str(), cp);
    GUNLUK("model %s: %s", ctx ? "yüklendi" : "YÜKLENEMEDİ", p.c_str());
    return reinterpret_cast<jlong>(ctx);
}

/**
 * Yazıya döker. vad: Silero konuşma algılayıcı modelinin yolu (boşsa kullanılmaz) — parça içindeki
 * konuşmasız kısımları atar; mikrofonda gürültüden uydurulan cümleleri büyük ölçüde önler.
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_canlialtyazi_app_Whisper_coz(JNIEnv* env, jobject, jlong h, jfloatArray ses, jstring dil, jint is, jstring vad,
                                      jint pencere) {
    auto* ctx = reinterpret_cast<whisper_context*>(h);
    if (!ctx) return utf8_dize(env, "");
    std::string dil_kodu = dize(env, dil);
    std::string vad_yolu = dize(env, vad);

    // telefonda hız için açgözlü (greedy) çözme; sıcaklık yedeği yok, önceki metne bağlı değil
    whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wp.greedy.best_of = 1;
    wp.language = dil_kodu.c_str();
    wp.translate = false;
    wp.no_context = true;
    wp.no_timestamps = true;
    wp.single_segment = false;
    wp.temperature = 0.0f;
    wp.temperature_inc = 0.0f;
    wp.suppress_blank = true;
    wp.suppress_nst = true;
    wp.print_progress = false;
    wp.print_realtime = false;
    wp.print_special = false;
    wp.print_timestamps = false;
    wp.n_threads = is > 0 ? is : 4;
    // kısa pencere: Whisper normalde her parçayı 30 sn'lik pencerede işler; parçanın süresine göre
    // kısaltılınca kodlayıcının işi birkaç kat azalır (FLEURS ölçümünde 4 dilde de kalite kaybı yok)
    if (pencere > 0 && pencere < 1500) wp.audio_ctx = pencere;
    if (!vad_yolu.empty()) {
        wp.vad = true;
        wp.vad_model_path = vad_yolu.c_str();
        wp.vad_params = whisper_vad_default_params();
        wp.vad_params.min_speech_duration_ms = 200;
        wp.vad_params.speech_pad_ms = 100;
    }

    jsize n = env->GetArrayLength(ses);
    std::vector<float> veri(n);
    env->GetFloatArrayRegion(ses, 0, n, veri.data());
    if (whisper_full(ctx, wp, veri.data(), n) != 0) {
        GUNLUK("çözme başarısız");
        return utf8_dize(env, "");
    }
    std::string metin;
    const whisper_token eot = whisper_token_eot(ctx);
    const int bolum = whisper_full_n_segments(ctx);
    for (int i = 0; i < bolum; ++i) {
        // güven süzgeci (masaüstündeki kural): konuşma yok olasılığı yüksek ve model emin değilse at
        float toplam = 0.0f;
        int adet = 0;
        for (int j = 0; j < whisper_full_n_tokens(ctx, i); ++j) {
            whisper_token_data td = whisper_full_get_token_data(ctx, i, j);
            if (td.id >= eot) continue;  // özel belirteçler
            toplam += td.plog;
            ++adet;
        }
        const float ort = adet ? toplam / adet : -10.0f;
        const float sessiz = whisper_full_get_segment_no_speech_prob(ctx, i);
        if ((sessiz > 0.6f && ort < -1.0f) || ort < -2.0f) {
            GUNLUK("bölüm atlandı (sessiz %.2f, ort. log-olas. %.2f)", sessiz, ort);
            continue;
        }
        const char* t = whisper_full_get_segment_text(ctx, i);
        if (!t) continue;
        if (!metin.empty()) metin += ' ';
        metin += t;
    }
    return utf8_dize(env, metin);
}

extern "C" JNIEXPORT void JNICALL
Java_com_canlialtyazi_app_Whisper_birak(JNIEnv*, jobject, jlong h) {
    if (h) whisper_free(reinterpret_cast<whisper_context*>(h));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_canlialtyazi_app_Whisper_sistemBilgisi(JNIEnv* env, jobject) {
    return utf8_dize(env, whisper_print_system_info());
}
