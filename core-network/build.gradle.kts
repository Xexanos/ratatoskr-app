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

// Generate the Kotlin client from the version-pinned contract submodule (SPEC section 4).
// The output is never committed (see .gitignore); the domain/UI layer never touches these
// types directly — the wrapper in this module maps them to domain models (SPEC section 13).
// AGP owns the output directory: addGeneratedSourceDirectory (below) wires it and the task
// ordering, so we do not set outputDir here. sourceFolder="" makes the generator write the
// package tree straight into that directory instead of a nested src/main/kotlin.
val openApiGenerate = tasks.named<GenerateTask>("openApiGenerate") {
    generatorName.set("kotlin")
    library.set("jvm-retrofit2")
    inputSpec.set(
        rootProject.layout.projectDirectory
            .file("contract/ratatoskr-server/contract/openapi.yaml")
            .asFile.absolutePath
    )
    packageName.set("io.github.xexanos.ratatoskr.network.generated")
    apiPackage.set("io.github.xexanos.ratatoskr.network.generated.api")
    modelPackage.set("io.github.xexanos.ratatoskr.network.generated.model")
    configOptions.set(
        mapOf(
            "dateLibrary" to "java8",
            "serializationLibrary" to "moshi",
            "useCoroutines" to "true",
            "sourceFolder" to "",
        )
    )
    // Only the client sources; no test/doc scaffolding.
    generateApiTests.set(false)
    generateModelTests.set(false)
    generateApiDocumentation.set(false)
    generateModelDocumentation.set(false)
}

androidComponents {
    onVariants { variant ->
        variant.sources.java?.addGeneratedSourceDirectory(openApiGenerate) { it.outputDir }
    }
}

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
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}
