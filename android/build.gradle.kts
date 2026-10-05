// Top-level build file
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("com.android.library") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    // NOTA: "org.jetbrains.kotlin.plugin.compose" só existe a partir do Kotlin 2.0.0.
    // Com Kotlin 1.9.22 o Compose Compiler é configurado via
    // android { composeOptions { kotlinCompilerExtensionVersion = "1.5.8" } }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
