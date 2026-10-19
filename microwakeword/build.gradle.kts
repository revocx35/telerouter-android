// On-device wake word: microWakeWord models (TFLite Micro + its audio frontend) in C++ behind JNI.
// The C++ sources, CMake setup and MicroWakeWord.kt come from the Home Assistant Companion for Android
// (Apache-2.0, see NOTICE). CMake downloads tflite-micro, kissfft, flatbuffers, gemmlowp and ruy at pinned hashes.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.revocx35.telerouter.microwakeword"
    compileSdk = 37
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = 26
        externalNativeBuild {
            cmake {
                // flexible page sizes for Android 15+ (16 KB pages)
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
        }
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }
}
