import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.register<JavaExec>("lintRecipes") {
    group = "verification"
    description = "Lint the bundled djmix-core RecipeV2 catalog."
    dependsOn("jvmJar")
    // `runtimeDependencyFiles` is nullable on a KMP compilation, so build the
    // classpath from the produced jar plus the resolved runtime configuration.
    classpath = files(tasks.named("jvmJar"), configurations.named("jvmRuntimeClasspath"))
    mainClass.set("com.music.bitchord.djmix.core.recipes.cli.RecipeLintMainKt")
    args("--all")
}
