import java.util.Properties

// Unit tests for the shell-side daemon - the parts of it that are plain Java.
//
// The daemon is built by shellserver/build.sh, against the device's platform
// with its @hide internals (see app/build.gradle.kts), and nothing here goes
// into it or into the APK. This module compiles only the daemon classes listed
// below - ones that use nothing of Android - straight from shellserver/src, so
// the code under test is the code that ships.
plugins {
    java
}

sourceSets {
    main {
        java {
            setSrcDirs(listOf("../src"))
            include("com/jemcik/jemrec/shell/AnswerMachine.java")
            include("com/jemcik/jemrec/shell/AnswerSettings.java")
            include("com/jemcik/jemrec/shell/CallStates.java")
            include("com/jemcik/jemrec/shell/Checks.java")
        }
    }
    test {
        java { setSrcDirs(listOf("src")) }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

// ERROR PRONE OVER ALL OF THE DAEMON - an analysis, never a build.
//
// The daemon is plain Java run as the shell user: several threads (the call
// watch, the cable watch, the kill watch, the acceptor), and nothing has ever
// checked it but the tests above, which cover only its Android-free classes.
// Error Prone is Google's javac plugin for the bug patterns javac lets
// through - an ignored return value, a lock held across the wrong scope, a
// switch that falls through, equals() on arrays, and some hundreds more.
//
// This compiles every file in ../src against the same android.jar as
// shellserver/build.sh, into build/errorprone, and throws the classes away.
// The jar in the APK is still built by build.sh alone, so its bytes - which
// F-Droid's reproducible build compares - cannot change. Run it with
// ./gradlew :daemon-tests:errorProne; CI does on every build.
val errorprone: Configuration = configurations.create("errorprone")

dependencies {
    errorprone("com.google.errorprone:error_prone_core:2.51.0")
}

// Where shellserver/build.sh looks, in the same order.
val androidSdk: String? = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    ?: rootProject.file("local.properties").takeIf { it.isFile }?.let { file ->
        Properties().apply { file.inputStream().use { load(it) } }.getProperty("sdk.dir")
    }

// scrcpy's sources expect a BuildConfig, which build.sh writes before it
// compiles (util.IO reads BuildConfig.DEBUG). The same file, here.
val errorProneBuildConfig = tasks.register("errorProneBuildConfig") {
    val out = layout.buildDirectory.file("errorprone/gen/com/genymobile/scrcpy/BuildConfig.java")
    outputs.file(out)
    doLast {
        out.get().asFile.apply { parentFile.mkdirs() }.writeText(
            "package com.genymobile.scrcpy;\n\n" +
                "public final class BuildConfig {\n    public static final boolean DEBUG = false;\n\n" +
                "    private BuildConfig() {\n    }\n}\n",
        )
    }
}

tasks.register<JavaCompile>("errorProne") {
    description = "Error Prone over all of shellserver/src. An analysis: the classes are thrown away."
    group = "verification"
    dependsOn(errorProneBuildConfig)
    source = fileTree("../src") { include("**/*.java") } +
        fileTree(layout.buildDirectory.dir("errorprone/gen")) { include("**/*.java") }
    val platform = System.getenv("ANDROID_PLATFORM") ?: "36"
    classpath = files("$androidSdk/platforms/android-$platform/android.jar")
    destinationDirectory.set(layout.buildDirectory.dir("errorprone/classes"))
    // Every file, every run: compiled incrementally or skipped as up to date,
    // the files that did not change are not looked at, and their findings
    // vanish from the report.
    options.isIncremental = false
    outputs.upToDateWhen { false }
    options.annotationProcessorPath = errorprone
    // Error Prone reaches into javac's internals, which JDK 21 keeps closed
    // unless the compiler's own JVM is started with them open.
    options.isFork = true
    options.forkOptions.jvmArgs = listOf(
        "api", "file", "main", "model", "parser", "processing", "tree", "util",
    ).map { "--add-exports=jdk.compiler/com.sun.tools.javac.$it=ALL-UNNAMED" } + listOf(
        "--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED",
    )
    options.compilerArgs.addAll(listOf(
        "-XDcompilePolicy=simple",
        "-XDaddTypeAnnotationsToSymbol=true",
        "--should-stop=ifError=FLOW",
        "-Xmaxwarns", "10000",
        // NOT -Xlint:none or -nowarn, as build.sh has: they silence Error
        // Prone's warnings too (tried - a planted ReferenceEquality vanished).
        "-Xplugin:ErrorProne",
    ))
}

// COVERAGE AND MUTATION TESTING OF THE DAEMON'S DECISIONS - reports, not gates.
//
// Coverage says which lines the tests above run; mutation testing (PIT) says
// whether they would notice those lines being wrong: it changes the compiled
// code one small way at a time - a < made <=, a return true made false - and
// runs the tests against each change. A change no test fails on is a decision
// the tests do not pin. These classes are the daemon's decisions in plain Java
// (CLAUDE.md), which is what PIT reads best.
//
// Neither runs unless asked, so a plain build or test run resolves nothing new:
//   ./gradlew :daemon-tests:test -Pcoverage  -> build/reports/jacoco/test/html
//   ./gradlew :daemon-tests:pitest           -> build/reports/pitest
if (providers.gradleProperty("coverage").isPresent) {
    apply(plugin = "jacoco")
    tasks.named("test") { finalizedBy("jacocoTestReport") }
}

val pitest: Configuration = configurations.create("pitest")

dependencies {
    pitest("org.pitest:pitest-command-line:1.30.1")
}

tasks.register<JavaExec>("pitest") {
    description = "PIT mutation testing of the daemon's decision classes. A report, not a gate."
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
            "--targetClasses", "com.jemcik.jemrec.shell.*",
            "--targetTests", "com.jemcik.jemrec.shell.*",
            "--sourceDirs", file("../src").path,
            "--mutableCodePaths", main.output.classesDirs.files.joinToString(","),
            "--classPath", test.runtimeClasspath.files.joinToString(","),
            "--outputFormats", "HTML,XML",
            "--timestampedReports=false",
            "--threads", "4",
        )
    }
}
