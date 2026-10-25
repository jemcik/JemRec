// ANDROID API LEVELS FOR THE DAEMON - lint's NewApi over shellserver/src.
//
// The daemon runs on Android 12 and later (d8 --min-api 31 in build.sh) but is
// compiled against Android 16's android.jar, so a call that exists only from
// Android 13 or 14 compiles fine and then fails on an older phone the moment
// it runs, with NoSuchMethodError. The app's own code has Android Lint's
// NewApi check for exactly this; the daemon, built by build.sh outside any
// Android module, had nothing - and Error Prone, which runs on it now, knows
// nothing of API levels: it suggested Scanner(InputStream, Charset), which is
// Android 14 (2026-10-25).
//
// So this module exists only to show lint the daemon's sources, at the
// daemon's real floor, with every other check off. Nothing here is built into
// anything: the jar in the APK still comes from build.sh alone. It runs with
// the rest of lint - ./gradlew lint, which CI runs - or alone with
// ./gradlew :daemon-lint:lint.
plugins {
    id("com.android.library")
}

android {
    // scrcpy's sources import com.genymobile.scrcpy.BuildConfig, which build.sh
    // writes and an Android module generates - for this namespace.
    namespace = "com.genymobile.scrcpy"
    // The platform build.sh compiles the daemon against (ANDROID_PLATFORM).
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        // build.sh's d8 --min-api.
        minSdk = 31
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // Only what can crash an older phone: a class, method or field newer
        // than Android 12, and a constant inlined from one.
        checkOnly += setOf("NewApi", "InlinedApi")
        warningsAsErrors = true
        abortOnError = true
    }
}

// Through AGP's new DSL type: android { sourceSets } in a library script is
// still typed as the old API's source set, and AGP 9 throws a
// ClassCastException at it.
extensions.getByType(com.android.build.api.dsl.LibraryExtension::class.java)
    .sourceSets.getByName("main").java.setSrcDirs(listOf("../src"))
