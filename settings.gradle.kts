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
