import com.github.takahirom.roborazzi.ExperimentalRoborazziApi

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

// Screenshot golden tests for every UI @Preview (SPEC section 9, issue #76): Roborazzi
// generates one Robolectric test per @Preview under the scanned package and renders it on the
// JVM - no emulator, so it runs in the existing unit-test job. Baselines live in
// src/test/screenshots (committed); `recordRoborazziDebug` regenerates them,
// `verifyRoborazziDebug` (CI) fails on any pixel drift across the screens and shared tiles.
roborazzi {
    outputDir.set(layout.projectDirectory.dir("src/test/screenshots"))
    @OptIn(ExperimentalRoborazziApi::class)
    generateComposePreviewRobolectricTests {
        enable = true
        // Every @Preview under the ui tree - all screens and shared components. Infinite
        // animations (the knot loader's comet, the cover spinner) render deterministically at
        // the fixed initial frame because Roborazzi pauses the composition clock.
        packages = listOf("io.github.xexanos.ratatoskr.ui")
        // Capture private @Preview functions too (e.g. the sign-in "submitting" and settings
        // "not configured" states), so the golden set matches the previews the screens declare.
        includePrivatePreviews = true
        // Pinned so the render is machine-independent: SDK 35 (Robolectric-supported) at the
        // default mdpi density, so 56 dp -> 56 px and 260 dp -> 260 px.
        robolectricConfig = mapOf("sdk" to "[35]")
    }
}

android {
    namespace = "io.github.xexanos.ratatoskr"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.xexanos.ratatoskr"
        minSdk = 26
        targetSdk = 36
        // versionCode = MAJOR * 10000 + MINOR * 100 + PATCH, derived from versionName;
        // releases are v<versionName> git tags (SPEC section 8).
        versionCode = 10701
        versionName = "1.7.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // R8 rules for the instrumented-test APK. Only take effect when the androidTest APK is
        // minified (the `minified` build type, -PminifiedTests); the default debug test APK is
        // not shrunk. They -dontwarn the compile-time-only classes that test-only deps
        // (okhttp-tls, mockwebserver, espresso-accessibility) reference but that are absent at
        // runtime, which R8 would otherwise reject in minifyMinifiedAndroidTestWithR8.
        testProguardFiles("proguard-test-rules.pro")
    }

    buildTypes {
        release {
            // R8 shrinking is on (SPEC section 8): the generated API client uses Moshi
            // codegen adapters (no reflection kept), the libraries' consumer rules plus
            // src/main/keepRules cover the rest, and the minified build is validated
            // on-device post-merge by the instrumented harness (SPEC section 9).
            // isShrinkResources also strips the unused material-icons-extended vectors.
            // (Not the optimization { enable } DSL - that is AGP 9's experimental "gradual
            // R8" opt-in; isMinifyEnabled is the standard full-shrink path.)
            isMinifyEnabled = true
            isShrinkResources = true
        }
        // A shrunk build the instrumented tests can run against: identical R8 configuration
        // to release, but debug-signed and debuggable so instrumentation may attach. R8 in a
        // debuggable variant still enforces shrinking and keep rules fully (only code
        // optimizations are reduced), which is exactly the failure class the on-device
        // validation exists to catch (SPEC sections 8 and 9). Selected via
        // -PminifiedTests so the regular per-PR debug test jobs stay untouched.
        create("minified") {
            initWith(getByName("release"))
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = true
            // core-network has no 'minified' build type; use its release variant.
            matchingFallbacks += "release"
        }
    }
    testBuildType = if (providers.gradleProperty("minifiedTests").isPresent) "minified" else "debug"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests {
            // Robolectric needs the merged resources on the classpath to inflate the theme;
            // pixelCopyRenderMode = hardware is Roborazzi's recommended path for pixel-accurate
            // Compose rendering under native graphics.
            isIncludeAndroidResources = true
            all {
                it.systemProperties["robolectric.pixelCopyRenderMode"] = "hardware"
            }
        }
    }
}

dependencies {
    implementation(project(":core-network"))
    implementation(libs.androidx.datastore.preferences)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.core)
    // Cover art (SPEC section 12): Coil 3 with the OkHttp fetcher, fed by core-network's
    // coversCallFactory so image loads share the TOFU trust and bearer/refresh auth.
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    // ViewModel unit tests (SPEC section 9, unit level): reuse the same fakes/fixtures the
    // instrumented UI integration tests use, so wire-format shapes never diverge between them.
    testImplementation(testFixtures(project(":core-network")))
    // Screenshot goldens for the cover-placeholder previews. The scanner-support artifact
    // pulls in roborazzi core/compose; robolectric provides the JVM Android runtime, and the
    // preview scanner discovers the @Preview functions the generated test renders.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.roborazzi.compose.preview.scanner.support)
    testImplementation(libs.robolectric)
    testImplementation(libs.composable.preview.scanner)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4.accessibility)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    // Whole-app UI integration tests (SPEC section 9): drive the real screens against a
    // MockWebServer over HTTPS, reusing the shared HttpsMockServer + wire fixtures.
    androidTestImplementation(testFixtures(project(":core-network")))
    androidTestImplementation(libs.okhttp.mockwebserver)
    // FakeImageLoaderEngine for the cover-state Compose tests: no network, no flake.
    androidTestImplementation(libs.coil.test)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}