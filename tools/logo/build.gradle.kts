plugins {
    // no version: the Kotlin plugin is already on the build classpath via AGP 9's
    // built-in Kotlin, and a versioned request would conflict with it
    id("org.jetbrains.kotlin.jvm")
    application
}

dependencies {
    implementation(libs.jts.core)
}

application {
    mainClass = "io.github.xexanos.ratatoskr.tools.logo.GenerateLogoKt"
}
