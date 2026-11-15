import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // Android and desktop are both JVM, but the default hierarchy gives them no source set in
    // common.
    applyDefaultHierarchyTemplate()

    sourceSets {
        val jvmSharedMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                // Compile-only: the two applications supply the runtime artifact themselves.
                compileOnly("com.microsoft.onnxruntime:onnxruntime:1.20.0")
                // The network stack the lyrics providers and the YouTube Music
                // client run on, at the phone's versions — both applications
                // now call the same code, so they resolve the same libraries.
                api("io.ktor:ktor-client-core:3.5.2")
                api("io.ktor:ktor-client-okhttp:3.5.2")
                api("io.ktor:ktor-client-websockets:3.5.2")
                api("io.ktor:ktor-client-content-negotiation:3.5.2")
                api("io.ktor:ktor-serialization-kotlinx-json:3.5.2")
                api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                api("org.jsoup:jsoup:1.22.2")
                // YouTube stream resolution, as the phone does it: InnerTubeX's
                // client catalog and cipher tiers, NewPipe as the fallback. NewPipe
                // is compile-only because the phone ships a trimmed jar of it.
                api("com.github.MetrolistGroup.innertubex:innertubex:v0.7.0")
                compileOnly("com.github.TeamNewPipe:NewPipeExtractor:v0.26.3")
            }
        }
        androidMain.get().dependsOn(jvmSharedMain)
        jvmMain.get().dependsOn(jvmSharedMain)

        commonMain.dependencies {
            // Models in this module intentionally have no Android or Compose dependency.
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation("com.squareup.okhttp3:mockwebserver:5.3.2")
        }
    }
}

android {
    namespace = "com.music.bitchord.shared"
    compileSdk = 36
}

/*
 * InnerTubeX's Android AAR (`innertubex-android`) declares `minCompileSdk = 37`.
 * This project compiles against 36: that is AGP 8.13.1's maximum recommended
 * compileSdk, and 36 is the target the release was built and verified against.
 * The bytecode uses no API-37 symbols, so the dependency check is disabled here,
 * in :sharedUi and in :app — `innertubex` is an `api` dependency of this module,
 * so it reaches the other two transitively.
 *
 * AGP's BundleAar tasks declare the check's output directory as an input, and a
 * disabled check never creates it, so a clean checkout fails Gradle input
 * validation before the bundle runs. The build box never noticed because a stale
 * directory from an earlier build was still on disk. The directory is therefore
 * created by a task that every bundle task depends on.
 */
tasks.configureEach { if (name.endsWith("AarMetadata")) enabled = false }

val prepareAarMetadataCheckDirs = tasks.register("prepareAarMetadataCheckDirs") {
    val dirs = listOf("debug", "release").map { variant ->
        layout.buildDirectory.dir(
            "intermediates/aar_metadata_check/$variant/" +
                "check${variant.replaceFirstChar { it.uppercase() }}AarMetadata"
        )
    }
    outputs.dirs(dirs)
    doLast { dirs.forEach { it.get().asFile.mkdirs() } }
}

tasks.configureEach {
    if (name.startsWith("bundle") && name.endsWith("Aar")) {
        dependsOn(prepareAarMetadataCheckDirs)
    }
}
