import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// İmza: android/imza/ klasöründeki anahtar (depoya girmez). Yoksa hata ayıklama anahtarıyla imzalanır.
val imzaAyari = Properties().apply {
    val f = rootProject.file("imza/imza.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.canlialtyazi.app"
    compileSdk = 35
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.canlialtyazi.app"
        minSdk = 29          // başka uygulamaların sesini yakalama (AudioPlaybackCapture) Android 10 istiyor
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }  // telefonlar + bilgisayardaki emülatör
        externalNativeBuild {
            cmake { arguments += listOf("-DANDROID_STL=c++_static") }
        }
    }

    signingConfigs {
        create("mlt") {
            if (imzaAyari.isNotEmpty()) {
                storeFile = rootProject.file(imzaAyari.getProperty("dosya"))
                storePassword = imzaAyari.getProperty("sifre")
                keyAlias = imzaAyari.getProperty("takma_ad")
                keyPassword = imzaAyari.getProperty("sifre")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (imzaAyari.isNotEmpty()) signingConfigs.getByName("mlt") else signingConfigs.getByName("debug")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")

        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // kitaplıklar diske açılsın: whisper.cpp işlemci sürümlerini klasörden yükler (dlopen)
    packaging { jniLibs {
        useLegacyPackaging = true
        // sherpa-onnx (Kürtçe Orta): JNI kitaplığı yalnızca onnxruntime'a bağlı; C/C++ API kitaplıkları gereksiz
        excludes += listOf("**/libsherpa-onnx-c-api.so", "**/libsherpa-onnx-cxx-api.so")
    } }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // Kürtçe Orta: sherpa-onnx 2.0.3 (Apache 2.0, github.com/k2-fsa/sherpa-onnx) + model arşivini (tar.bz2) açmak için
    implementation(files("libs/sherpa-onnx-2.0.3.aar"))
    implementation("org.apache.commons:commons-compress:1.27.1")
    testImplementation("junit:junit:4.13.2")
}
