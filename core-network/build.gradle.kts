import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.openapi.generator)
}

android {
    namespace = "io.github.xexanos.ratatoskr.network"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// The generated client is the ONLY thing this task produces; it is never committed
// (see .gitignore). It is regenerated from the version-pinned contract submodule
// (SPEC section 4). The domain/UI layer never touches these types directly — the
// wrapper in this module maps them to domain models (SPEC section 13).
val openApiOutputDir = layout.buildDirectory.dir("generated/openapi")

tasks.named<GenerateTask>("openApiGenerate") {
    generatorName.set("kotlin")
    library.set("jvm-retrofit2")
    inputSpec.set(
        rootProject.layout.projectDirectory
            .file("contract/ratatoskr-server/contract/openapi.yaml")
            .asFile.absolutePath
    )
    outputDir.set(openApiOutputDir.map { it.asFile.absolutePath })
    packageName.set("io.github.xexanos.ratatoskr.network.generated")
    apiPackage.set("io.github.xexanos.ratatoskr.network.generated.api")
    modelPackage.set("io.github.xexanos.ratatoskr.network.generated.model")
    // The kotlin generator emits infrastructure under <packageName>.infrastructure.
    configOptions.set(
        mapOf(
            "dateLibrary" to "java8",
            "serializationLibrary" to "moshi",
            "useCoroutines" to "true",
        )
    )
    // Only the client sources; no test/doc scaffolding.
    generateApiTests.set(false)
    generateModelTests.set(false)
    generateApiDocumentation.set(false)
    generateModelDocumentation.set(false)
    // An older app must tolerate a newer server's unknown fields (SPEC section 4).
    // Moshi ignores unknown JSON fields by default; keep generation lenient too.
    cleanupOutput.set(true)
}

// Feed the generated Kotlin into this module's main source set and make sure it is
// produced before anything compiles.
android.sourceSets.getByName("main").java.srcDir(
    openApiOutputDir.map { it.dir("src/main/kotlin") }
)
tasks.named("preBuild").dependsOn("openApiGenerate")

dependencies {
    // Runtime dependencies the generated jvm-retrofit2/moshi client expects.
    api(libs.retrofit)
    api(libs.retrofit.converter.moshi)
    api(libs.retrofit.converter.scalars)
    api(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    api(libs.moshi.kotlin)
    api(libs.moshi.adapters)
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
}
