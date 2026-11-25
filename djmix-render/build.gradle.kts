plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("application")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":djmix-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    testImplementation(kotlin("test-junit"))
    testImplementation("junit:junit:4.13.2")
}

application {
    mainClass.set("com.music.bitchord.djmix.render.cli.ReplayCliKt")
}
