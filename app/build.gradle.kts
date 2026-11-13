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
        versionCode = 70
        versionName = "0.2.68"

        if (vita3kTreePresent) {
            // Solo arm64-v8a: el procesador objetivo es arm64 y así el
            // APK no duplica el .so (decenas de MB) con x86_64.
            ndk {
                abiFilters += "arm64-v8a"
            }
            // Toolchain de vcpkg: DEBE cargarse en project(). El de
            // Vita3K (cmake/vcpkg_android.cmake) lo asigna demasiado
            // tarde (add_subdirectory, tras project()) y Gradle pasa
            // el del NDK; sin el toolchain de vcpkg, find_package no
            // ve lo instalado (Boost, curl, openssl, zlib) y la
            // configuración falla en get_boost() con "Could NOT find
            // Boost". Los argumentos van aquí (defaultConfig), no en
            // el cmake de nivel superior (que solo acepta path y
            // version): van después de los de Gradle, así que este
            // CMAKE_TOOLCHAIN_FILE gana al del NDK; el del NDK se
            // encadena con VCPKG_CHAINLOAD_TOOLCHAIN_FILE. El
            // manifiesto está en el árbol Vita3K: el workflow instala
            // sus dependencias en modo manifiesto (vcpkg_installed/
            // junto a vcpkg.json), por eso VCPKG_MANIFEST_DIR.
            val vcpkgRoot = System.getenv("VCPKG_ROOT")
                ?: error("VCPKG_ROOT no definido (lo pone el workflow)")
            val androidNdkHome = System.getenv("ANDROID_NDK_HOME")
                ?: error("ANDROID_NDK_HOME no definido (lo pone el workflow)")
            externalNativeBuild {
                cmake {
                    arguments(
                        "-DCMAKE_TOOLCHAIN_FILE=$vcpkgRoot/scripts/buildsystems/vcpkg.cmake",
                        "-DVCPKG_CHAINLOAD_TOOLCHAIN_FILE=$androidNdkHome/build/cmake/android.toolchain.cmake",
                        "-DVCPKG_TARGET_TRIPLET=arm64-android",
                        "-DVCPKG_MANIFEST_DIR=${file("src/main/cpp/vita3k").absolutePath}",
                    )
                }
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
