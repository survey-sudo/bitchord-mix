plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("application")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":djmix-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("com.microsoft.onnxruntime:onnxruntime:1.31.0")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("junit:junit:4.13.2")
}

application {
    mainClass.set("com.music.bitchord.djmix.analysis.cli.TrackAnalysisExporterKt")
}

val hostNativeDir = layout.buildDirectory.dir("host-native")
val configureHostAnalysis by tasks.registering(Exec::class) {
    commandLine("cmake", "-S", file("native").absolutePath, "-B", hostNativeDir.get().asFile.absolutePath,
        "-DCMAKE_BUILD_TYPE=Release")
}
val buildHostAnalysis by tasks.registering(Exec::class) {
    dependsOn(configureHostAnalysis)
    commandLine("cmake", "--build", hostNativeDir.get().asFile.absolutePath, "--parallel", "2")
}
tasks.withType<Test>().configureEach {
    dependsOn(buildHostAnalysis)
    systemProperty("java.library.path", hostNativeDir.get().asFile.absolutePath)
}
tasks.named<JavaExec>("run") {
    dependsOn(buildHostAnalysis)
    systemProperty("java.library.path", hostNativeDir.get().asFile.absolutePath)
}
