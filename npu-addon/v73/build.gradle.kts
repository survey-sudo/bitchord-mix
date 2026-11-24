// BitChord NPU add-on v73 (Part B4.3) — STANDALONE Gradle project.
//
// This directory is its own Gradle build (see settings.gradle.kts here);
// it is deliberately NOT included from the root settings.gradle.kts. The
// manual `.github/workflows/npu-addon.yml` workflow builds it with the
// repo-root wrapper: `./gradlew -p npu-addon/v73 :assembleRelease`.
//
// Code-less by design: no Java/Kotlin sources. Everything the add-on
// ships arrives as packaged content:
//   - src/main/jniLibs/arm64-v8a/ — QAIRT runtime + runner (from Orchard's
//     add-on contents; Qualcomm owns these, review terms before any
//     redistribution — see ../README.md)
//   - src/main/assets/           — QNN models (nirovi_qnn.onnx,
//     syvenu_qnn.onnx) verified by the app's ModelRegistry
//
// Keep the toolchain pins in sync with the root project (AGP 8.13.1,
// compileSdk 36, minSdk 26).
plugins {
    id("com.android.application") version "8.13.1"
}

android {
    namespace = "com.music.bitchord.loopfix.npu.v73"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.music.bitchord.loopfix.npu.v73"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0-v73"
    }

    buildTypes {
        release {
            // Signing is optional and mirrors the root build: without a
            // keystore the release assembles unsigned. The npu-addon
            // workflow configures signing from secrets when present.
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    packaging {
        jniLibs {
            // The QAIRT `.so` set is delivered as packaged JNI libs; do not
            // strip or re-pack them (16 KiB page alignment is verified
            // against the published artifact, not re-asserted here).
            useLegacyPackaging = true
        }
    }
}
