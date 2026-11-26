// Çevrimdışı çeviri: NLLB-200 600M (CTranslate2 int8, Ruy) + SentencePiece belirteçleyici.
// Masaüstündeki canli_altyazi/ceviri.py YerelCevirmen ile aynı ayarlar (ışın 2, 4'lü tekrar yasağı,
// çıktı uzunluğu girdinin en fazla ~2 katı).
#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <memory>
#include <string>
#include <vector>

#include <ctranslate2/translator.h>
#include <sentencepiece_processor.h>

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "mlt_ceviri", __VA_ARGS__)

namespace {

struct Cevirici {
    std::unique_ptr<ctranslate2::Translator> model;
    sentencepiece::SentencePieceProcessor spm;
};

std::string metin(JNIEnv* env, jstring s) {
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string r(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return r;
}

// GetStringUTFChars "modified UTF-8" verir (emoji gibi 4 baytlık karakterlerde bozulur); bu yüzden
// metin Java tarafından UTF-8 bayt dizisi olarak gelir ve öyle döner.
std::string baytlar(JNIEnv* env, jbyteArray b) {
    jsize n = env->GetArrayLength(b);
    std::string r(static_cast<size_t>(n), '\0');
    env->GetByteArrayRegion(b, 0, n, reinterpret_cast<jbyte*>(&r[0]));
    return r;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_canlialtyazi_app_Nllb_yukle(JNIEnv* env, jobject, jstring klasor, jstring spmYolu, jint isParcacigi) {
    try {
        auto c = std::make_unique<Cevirici>();
        auto durum = c->spm.Load(metin(env, spmYolu));
        if (!durum.ok()) {
            LOG("belirteçleyici yüklenemedi: %s", durum.ToString().c_str());
            return 0;
        }
        ctranslate2::ReplicaPoolConfig ayar;
        ayar.num_threads_per_replica = static_cast<size_t>(std::max(1, static_cast<int>(isParcacigi)));
        c->model = std::make_unique<ctranslate2::Translator>(
            metin(env, klasor), ctranslate2::Device::CPU, ctranslate2::ComputeType::INT8,
            std::vector<int>{0}, false, ayar);
        LOG("NLLB yüklendi (%d iş parçacığı)", static_cast<int>(isParcacigi));
        return reinterpret_cast<jlong>(c.release());
    } catch (const std::exception& e) {
        LOG("NLLB yüklenemedi: %s", e.what());
        return 0;
    }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_canlialtyazi_app_Nllb_cevir(JNIEnv* env, jobject, jlong tutamac, jobjectArray cumleler,
                                     jstring kaynak, jstring hedef) {
    auto* c = reinterpret_cast<Cevirici*>(tutamac);
    if (!c) return nullptr;
    try {
        const std::string ks = metin(env, kaynak), hs = metin(env, hedef);
        const jsize n = env->GetArrayLength(cumleler);
        std::vector<std::vector<std::string>> girdi;
        size_t enUzun = 0;
        for (jsize i = 0; i < n; ++i) {
            auto b = static_cast<jbyteArray>(env->GetObjectArrayElement(cumleler, i));
            std::vector<std::string> parcalar;
            c->spm.Encode(baytlar(env, b), &parcalar);
            env->DeleteLocalRef(b);
            enUzun = std::max(enUzun, parcalar.size());
            parcalar.insert(parcalar.begin(), ks);
            parcalar.push_back("</s>");
            girdi.push_back(std::move(parcalar));
        }
        if (girdi.empty()) return nullptr;
        ctranslate2::TranslationOptions sec;
        sec.beam_size = 2;
        sec.no_repeat_ngram_size = 4;
        sec.max_decoding_length = std::min<size_t>(256, static_cast<size_t>(enUzun * 2.0) + 10);
        std::vector<std::vector<std::string>> onek(girdi.size(), std::vector<std::string>{hs});
        auto sonuc = c->model->translate_batch(girdi, onek, sec);

        jclass bd = env->FindClass("[B");
        jobjectArray cikti = env->NewObjectArray(static_cast<jsize>(sonuc.size()), bd, nullptr);
        for (size_t i = 0; i < sonuc.size(); ++i) {
            std::vector<std::string> p = sonuc[i].output();
            if (!p.empty() && p.front() == hs) p.erase(p.begin());  // baştaki hedef dil etiketi
            std::string s;
            c->spm.Decode(p, &s);
            jbyteArray b = env->NewByteArray(static_cast<jsize>(s.size()));
            env->SetByteArrayRegion(b, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte*>(s.data()));
            env->SetObjectArrayElement(cikti, static_cast<jsize>(i), b);
            env->DeleteLocalRef(b);
        }
        return cikti;
    } catch (const std::exception& e) {
        LOG("çeviri hatası: %s", e.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_canlialtyazi_app_Nllb_birak(JNIEnv*, jobject, jlong tutamac) {
    delete reinterpret_cast<Cevirici*>(tutamac);
}
