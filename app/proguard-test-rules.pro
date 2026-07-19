# R8 rules for the instrumented-test APK only (testProguardFiles). Effective solely when the
# androidTest APK is itself minified - i.e. the `minified` build type via -PminifiedTests
# (see app/build.gradle.kts); ignored for the default debug test APK, which is not shrunk.
#
# The test APK pulls in test-only dependencies (okhttp-tls, mockwebserver, the Espresso
# accessibility integration) that reference compile-time / annotation-processor classes which
# are absent at runtime. R8 treats those as missing-class errors and aborts
# (minifyMinifiedAndroidTestWithR8). They are safe to ignore - none are reachable at runtime.
# These mirror the rules AGP emits in build/outputs/mapping/minifiedAndroidTest/missing_rules.txt.
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn com.google.auto.value.**
-dontwarn javax.lang.model.**
# coil-test's transitive com.google.android.material references appcompat's DrawableWrapper;
# appcompat is not on the test APK's runtime classpath and no test reaches that code path.
-dontwarn androidx.appcompat.graphics.drawable.DrawableWrapper
