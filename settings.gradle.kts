pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // libadb-android is NOT on Maven Central. Checked: a solrsearch for
        // g:io.github.muntashirakon and for a:libadb* both return zero rows,
        // even though the library's own build.gradle declares that group and
        // maven-publish. JitPack is the only distribution, and it does have
        // 3.1.1 and its spake2-android dependency prebuilt (both HTTP 200).
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "JemRec"
include(":app")

// The shell-side daemon's plain-Java parts, under test on the JVM. The daemon
// itself is built by shellserver/build.sh; see shellserver/tests.
include(":daemon-tests")
project(":daemon-tests").projectDir = file("shellserver/tests")

// Lint's Android API-level check over the daemon's sources, at its own floor
// (Android 12). Nothing is built from it; see shellserver/lint.
include(":daemon-lint")
project(":daemon-lint").projectDir = file("shellserver/lint")
