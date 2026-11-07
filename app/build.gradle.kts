plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Etapa 2: el build nativo SOLO se activa si el árbol Vita3K está
// presente (lo clona el workflow de CI en app/src/main/cpp/vita3k;
// ver docs/etapa2/ETAPA2-ESTADO.md). Sin árbol, la compilación es
// idéntica a Etapa 1 y NO necesita NDK instalado en el runner.
val vita3kTreePresent = file("src/main/cpp/vita3k/CMakeLists.txt").exists()

android {
    namespace = "com.danielsnagustin.childoflight.launcher"
    compileSdk = 35

    if (vita3kTreePresent) {
        // NDK de upstream (Vita3K) y de EmuCoreV.
        ndkVersion = "29.0.14206865"
    }

    defaultConfig {
        applicationId = "com.danielsnagustin.childoflight.launcher"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        if (vita3kTreePresent) {
            // Solo arm64-v8a: el procesador objetivo es arm64 y así el
            // APK no duplica el .so (decenas de MB) con x86_64.
            ndk {
                abiFilters("arm64-v8a")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "2.0.20" }

    if (vita3kTreePresent) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1+"
            }
        }
        packaging {
            jniLibs {
                // Como upstream: empaquetado legacy de .so.
                useLegacyPackaging = true
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)
    testImplementation(libs.junit)
}
