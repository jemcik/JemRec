// The app's pure code, compiled and tested on its own.
//
// The pure files - the decisions and formats that use nothing of Android - are
// listed here and compiled from app/src/main with no android.jar on the
// classpath, the way shellserver/tests compiles the daemon's. Nothing here is
// built into the APK. It gives two things the app module cannot:
//
//   - A file that says it is pure has to be: one that reaches Android, or an
//     app class that does, fails to compile here. In the app's own unit tests
//     android.jar's stubs return defaults (isReturnDefaultValues), so a "pure"
//     function that starts calling Android still passes them. The list grows as
//     the 1.0 restructuring pulls decisions out of Android-bound objects.
//   - PIT (./gradlew :pure-tests:pitest), which runs on plain JVM bytecode and
//     not on an Android module. Like the daemon's, a report, not a gate.
//
// The tests are the app's own, from app/src/test, listed with their files:
// they run there too, as before.
//
//   ./gradlew :pure-tests:test
//   ./gradlew :pure-tests:pitest    -> build/reports/pitest

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

val pure = listOf(
    "capture/AudioStream",
    "capture/OpusHead",
    "capture/OpusPackets",
    "capture/PlaybackRules",
    "capture/SystemProperty",
    "ui/DaySections",
)

val pureTests = listOf(
    "capture/AudioStreamTest",
    "capture/OpusHeadTest",
    "capture/OpusPacketsTest",
    "capture/PlaybackRulesTest",
    "ui/DaySectionsTest",
)

sourceSets {
    main {
        kotlin {
            setSrcDirs(listOf("../src/main/java"))
            pure.forEach { include("com/jemcik/jemrec/$it.kt") }
        }
    }
    test {
        kotlin {
            setSrcDirs(listOf("../src/test/java"))
            pureTests.forEach { include("com/jemcik/jemrec/$it.kt") }
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

val pitest: Configuration = configurations.create("pitest")

dependencies {
    pitest("org.pitest:pitest-command-line:1.30.1")
}

tasks.register<JavaExec>("pitest") {
    description = "PIT mutation testing of the app's pure code. A report, not a gate."
    group = "verification"
    dependsOn("testClasses")
    val main = sourceSets["main"]
    val test = sourceSets["test"]
    classpath = pitest
    mainClass.set("org.pitest.mutationtest.commandline.MutationCoverageReport")
    val reports = layout.buildDirectory.dir("reports/pitest")
    doFirst {
        args(
            "--reportDir", reports.get().asFile.path,
            "--targetClasses", "com.jemcik.jemrec.*",
            "--targetTests", "com.jemcik.jemrec.*",
            "--sourceDirs", file("../src/main/java").path,
            "--mutableCodePaths", main.output.classesDirs.files.joinToString(","),
            "--classPath", test.runtimeClasspath.files.joinToString(","),
            "--outputFormats", "HTML,XML",
            "--timestampedReports=false",
            "--threads", "4",
            // The Kotlin compiler's null checks (Intrinsics), hashCode, and
            // data classes' copy and componentN. Left in, they were 9 of the 26
            // survivors on the first run (2026-11-01): six null checks removed
            // and three hashes changed that still agreed with equals, none of
            // which a test could tell. equals stays in: a hand-written one
            // (AudioStream.Packet's) is a decision.
            "--avoidCallsTo", "kotlin.jvm.internal",
            "--excludedMethods", "hashCode,copy,copy\$default,component*",
        )
    }
}
