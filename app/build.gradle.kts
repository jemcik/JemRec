import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.jemcik.jemrec"

    // compileSdk and targetSdk are deliberately different, and so is minSdk.
    // Three separate decisions that must not be collapsed:
    //
    //   compileSdk 37  - what we compile and lint against. Newest installed.
    //   targetSdk  36  - which platform behaviour changes we opt into: the
    //                    newest whose changes have been gone through here. The
    //                    phones and the newest AVD run Android 17 (37) by now;
    //                    moving the target is a change of its own, made with
    //                    Android 17's behaviour changes read and measured.
    //   minSdk     31  - the run-time floor. Android 12 is where the shell-side
    //                    capture approach starts working at all.
    //
    // A FOURTH "36" exists elsewhere and is not this one: the recorder's jar
    // is compiled against ANDROID_PLATFORM=36's android.jar (shellserver/
    // build.sh), pinned because its bytes depend on it, not because a phone
    // must run 36 - one jar runs on Android 12 and later. The two sharing the
    // number today is a coincidence.
    compileSdk = 37

    // Pinned because shellserver/build.sh reads d8 out of build-tools/36.0.0 by
    // path, and until now that worked only because 36.0.0 happens to be AGP's
    // default. Nothing declared it, so nothing would have caught AGP moving on:
    // the daemon build would fail on a machine that had never been told which
    // build-tools to install - F-Droid's, for one, which installs only what the
    // Gradle build asks for plus what its metadata names. Stating it here makes
    // AGP install the version build.sh expects. Keep the two in step; build.sh
    // takes BUILD_TOOLS from the environment, so it can be told otherwise.
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.jemcik.jemrec"
        minSdk = 31
        targetSdk = 36

        // From gradle.properties, which is the one place the version is
        // written - see the comment there. property() rather than
        // findProperty() so a missing key fails with its name instead of
        // shipping a fallback: a fallback here once meant every release
        // would have been versionCode 1.
        versionCode = (property("jemrecVersionCode") as String).toInt()
        versionName = property("jemrecVersionName") as String
    }

    // Release signing comes from the environment, never from the repo.
    val keystorePath: String? = System.getenv("JEMREC_KEYSTORE")
    val signingReady = !keystorePath.isNullOrBlank() && file(keystorePath).exists()

    signingConfigs {
        if (signingReady) create("release") {
            storeFile = file(keystorePath!!)
            storePassword = System.getenv("JEMREC_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("JEMREC_KEY_ALIAS") ?: "jemrec"
            keyPassword = System.getenv("JEMREC_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            // R8, on. Measured on 0.4 before this: 38 MB on disk, of which
            // 28.5 MB was dex and 0.2 MB of that was this app - the rest was
            // libraries shipped whole, and 12.8 MB was nothing but the names
            // of their classes, methods and fields. proguard-rules.pro says
            // what has to survive and why. Debug stays unshrunk: its
            // diagnostic receivers are reached by name from tools/, and a
            // build that takes a minute longer is a measurement that does
            // not get made.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // With no keystore configured the APK is left UNSIGNED rather than
            // falling back to the debug key: a debug-signed "release" installs
            // once and can never be upgraded by a properly signed one.
            if (signingReady) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // Coverage of the unit and screen tests, only when asked for:
            // ./gradlew createDebugUnitTestCoverageReport -Pcoverage. A report
            // to read, not a gate, and JaCoCo slows every test it watches -
            // so a plain build, and the release build F-Droid reproduces,
            // carry none of it.
            enableUnitTestCoverage = providers.gradleProperty("coverage").isPresent
        }
        // The debug build through R8 with the release's rules, for the
        // emulator checks (tools/emulator_checks.py installs this one).
        // Until 0.25.3 they installed debug, which is not shrunk, so nothing
        // ever ran what R8 makes: a library rule that let R8 remove a
        // constructor WorkManager reflects on failed every one-time work in
        // every release from 0.5 to 0.25.2, and no check could see it (issue
        // #69). This keeps debug's diagnostic receiver, which the checks
        // drive, and debug's key.
        //
        // Not debuggable, unlike debug: AGP runs R8 in its debug mode for a
        // debuggable build, which removes what the same rules let go but
        // optimises far less. Measured 2026-10-31: debuggable, R8 removed
        // 49,827 items to the release's 70,575 and left 35 classes without a
        // constructor to the release's 725. The checks read the app's files
        // as root instead of with run-as.
        create("shrunk") {
            initWith(getByName("debug"))
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            matchingFallbacks += "debug"
        }
    }

    // The shrunk build is the debug build: its source set is debug's folder,
    // every kind in it. Named kind by kind, java alone left out the .kt files
    // there and the shrunk build crashed at launch on the manifest's
    // DemoProvider - and assets, or anything else added later, would have
    // gone missing the same way.
    sourceSets.named("shrunk") { setRoot("src/debug") }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    // Lint as a gate, not noise. Until 0.21 it ran on every build with its
    // warnings ignored - 108 of them, nobody reading. Now every warning is an
    // error, and there is no baseline of old ones left to hide behind: all
    // were fixed, or are allowed where they stand with the reason beside them
    // (lint.xml, tools:ignore, @SuppressLint). So any warning fails the
    // build, locally and in CI.
    lint {
        lintConfig = file("lint.xml")
        warningsAsErrors = true
        abortOnError = true
    }

    // No Google Play dependency metadata. By default AGP writes an encrypted
    // list of the app's dependencies into the APK Signing Block at signing
    // time, for the Play Console to read - and nobody else can, which is why
    // F-Droid's scanner refuses an APK that carries it. Found by their check
    // on the 0.5 release: "found extra signing block 'Dependency metadata'",
    // 5,977 bytes, inside the one part of the APK the reproducibility
    // comparison skips by design. There is no Play listing to inform.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // Package every native library exactly as its Maven artifact ships it.
    //
    // AGP strips debug symbols from .so files when an NDK happens to be
    // installed, and silently skips the step when one is not. Measured on
    // 0.3: the GitHub runner has an NDK and ran :app:stripReleaseDebugSymbols,
    // the machine without one did not, and the four libconscrypt_jni.so came
    // out the same size and different bytes - the only difference between the
    // two APKs apart from the daemon jar. F-Droid's builder has no NDK unless
    // asked for one. Keeping the symbols removes the step on every machine,
    // and costs nothing: Conscrypt ships its libraries stripped already, which
    // is why the size never changed.
    packaging {
        jniLibs {
            keepDebugSymbols += "**/*.so"
            // Phones are ARM. The x86 and x86_64 copies of Conscrypt's
            // BoringSSL are 4.9 MB that only an emulator could run, and this
            // app cannot record a call on an emulator anyway - there is no
            // voice-call audio source to open. armeabi-v7a stays: a 32-bit
            // phone on Android 12 is rare, and 1.3 MB is a fair price for not
            // finding out from the one person who has one.
            excludes += listOf("**/x86/**", "**/x86_64/**")
        }
        resources {
            // BouncyCastle's jar carries 1.3 MB of Java resources this app
            // never opens: a megabyte of lookup tables for the Picnic
            // post-quantum signature scheme, and the message catalogues of a
            // certificate-path reviewer. R8 removes the classes that would
            // read them; nothing removes the files, so this does.
            excludes += listOf(
                "org/bouncycastle/pqc/**",
                "org/bouncycastle/x509/CertPathReviewerMessages*",
                "org/bouncycastle/pkix/CertPathReviewerMessages*",
            )
        }
    }

    testOptions {
        // android.util.Log and the rest of the stub android.jar throw on the
        // JVM. With this they return defaults instead, so pure logic that
        // happens to log a line (Setup.currentStep) is testable off-device.
        unitTests.isReturnDefaultValues = true
        // The screen tests (app/src/test/.../ui/screens) draw the real
        // composables under Robolectric, which needs the app's resources.
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            // Screenshots are compared against the PNGs committed under
            // app/src/test/screenshots, and a difference fails the build.
            // `./gradlew testDebugUnitTest -PrecordScreens=true` writes them
            // anew instead - after a change to a screen that was meant.
            val record = project.findProperty("recordScreens") == "true"
            test.systemProperty("roborazzi.test.record", record.toString())
            test.systemProperty("roborazzi.test.verify", (!record).toString())
            // Robolectric's Android 16 sets up a file descriptor through a
            // JDK internal that Java 21 keeps closed unless asked.
            test.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
            // The contract tests (app/src/test/.../contract) read these as
            // text. Gradle does not know that, and called the tests up to date
            // after a change to the emulator checks alone.
            test.inputs.files(rootProject.fileTree("tools") { include("*.py") })
                .withPropertyName("toolsReadByContractTests")
            test.inputs.dir(rootProject.file("shellserver/tests/fixtures"))
                .withPropertyName("protocolFixture")
            // With -Pcoverage: Robolectric loads the app's classes through a
            // class loader of its own, which JaCoCo skips unless told - and
            // then the screens the screen tests draw count as never run.
            test.extensions.findByType(JacocoTaskExtension::class.java)?.apply {
                isIncludeNoLocationClasses = true
                excludes = listOf("jdk.internal.*")
            }
        }
    }
}

dependencies {
    // The embedded ADB client. This is the whole transport: SPAKE2 pairing
    // against the phone's own Wireless debugging, then a TLS connection to
    // loopback and a shell-privileged stream. Dual licensed GPL-3.0-or-later
    // OR Apache-2.0; we take it under Apache-2.0. See NOTICE.md.
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")

    // Self-signed X.509 for the ADB identity. libadb already pulls bcprov at
    // RUNTIME (it is a runtime-scope dep in its POM), but runtime scope is not
    // on the compile classpath, so both are declared explicitly here.
    //
    // The alternative the library's own sample uses is MuntashirAkon's
    // sun-security-android shim, which drags android.sun.* repackaged JDK
    // internals in. BouncyCastle is a real, auditable, Maven Central artifact
    // and one of the two is already in the graph regardless.
    implementation("org.bouncycastle:bcprov-jdk15to18:1.81")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")

    // REQUIRED FOR PAIRING, and not obvious why.
    //
    // SPAKE2 binds the pairing exchange to the TLS channel by mixing in RFC
    // 5705 exported keying material, so libadb has to call
    // Conscrypt.exportKeyingMaterial(). Which Conscrypt it calls is decided by
    // whether a bundled one is on the classpath:
    //
    //   bundled present -> org.conscrypt.Conscrypt            (public API)
    //   bundled absent  -> com.android.org.conscrypt.Conscrypt (HIDDEN API)
    //
    // Without this dependency it takes the second path and Android's hidden-API
    // blocklist makes the method simply not exist. Measured, on the first real
    // pairing attempt:
    //
    //   pair failed: java.lang.NoSuchMethodException:
    //     com.android.org.conscrypt.Conscrypt.exportKeyingMaterial
    //
    // minSdk 31 guarantees TLSv1.3 from the platform, which is why everything
    // SHORT of pairing worked without this: the connection reached adbd and was
    // correctly told "ADB pairing is required". Only the pairing handshake
    // needs the keying export, so this was invisible until the first pair.
    //
    // The upstream sample solves it the other way, with LSPosed's
    // HiddenApiBypass and a blanket addHiddenApiExemptions("L"). Bundling
    // Conscrypt is better here: no hidden-API exemptions at all, and no
    // exposure to Android 17 tightening them further.
    //
    // 2.7.0, not 2.5.3: 2.5.3's libconscrypt_jni.so ends its RELRO segment on
    // a 4 KB boundary inside a page it shares with writable data, so on a
    // phone with 16 KB pages Android refused to load it normally and ran the
    // whole app in page-size compat mode, with a warning dialog at launch
    // (measured on the Android 17 QPR2 emulator). 2.7.0 ends it on 16 KB.
    // The dialog also named libspake2.so and libandroidx.graphics.path.so;
    // both keep RELRO in a segment of their own, which the check accepts,
    // and with this alone the app was no longer put in compat mode
    // (pageSizeCompat 256 before, 0 after, in `dumpsys package`).
    implementation("org.conscrypt:conscrypt-android:2.7.0")

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    // Play and Share glyphs for the recordings list. The -core set, not
    // -extended: extended is thousands of vectors for the two we use.
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.core:core-ktx:1.19.0")

    // Playing a recording with the screen off: a media session in a service,
    // Android's documented route, under the system's media controls.
    // Measured on the release APK, 2026-10-23: 9.19 MB -> 9.75 MB with it.
    implementation("androidx.media3:media3-session:1.11.1")
    // And ExoPlayer to play with, since 1.2: it can mix both sides of a call
    // into both ears, which MediaPlayer cannot (playback/RecordingPlayer.kt).
    // Cut down to the Ogg extractor and one audio renderer, measured on the
    // release APK, 2026-11-16: 10,240,823 -> 10,751,859 bytes.
    implementation("androidx.media3:media3-exoplayer:1.11.1")

    // Keeps the shell-side recorder alive without a permanent foreground
    // service: a periodic job revives it when it is down and Wi-Fi is around.
    //
    // 2.10 is the floor. 2.9.1's keep rule for input mergers had no member
    // clause, so R8 kept OverwritingInputMerger and removed the constructor
    // WorkManager reflects on for every one-time work: in every shrunk
    // release from 0.5 to 0.25.2, each kick of the keep-alive and every USB
    // debugging watch ended FAILED without running (issue #69). 2.10 ships
    // the rule with the constructor. 2.12 also brings Room 2.7, whose own
    // rule keeps WorkDatabase_Impl's constructor the same way.
    implementation("androidx.work:work-runtime-ktx:2.12.0")

    // Compose's own pitfalls, which Android Lint does not know: Slack's rules
    // for modifiers (passed on, in the right place), state hoisting, unstable
    // parameters and the like. Only lint loads it - the release build's
    // lintVital included, which reads the sources and writes nothing into the
    // APK, so the reproducible bytes are untouched.
    lintChecks("com.slack.lint.compose:compose-lint-checks:1.6.1")

    testImplementation("junit:junit:4.13.2")
    // Mockito stands in for the Android types the tests cannot construct on
    // the JVM: android.net.Uri (abstract in the stub android.jar, and
    // Uri.EMPTY/parse() are null there), android.app.Application, and
    // android.telecom.Call with its Details (no public constructor;
    // CallTrackerTest). The inline mock maker in Mockito 5 mocks abstract and
    // final classes without extra setup. Nothing else in the tests needs a
    // framework.
    testImplementation("org.mockito:mockito-core:5.14.2")

    // The screen tests: Robolectric runs the real Compose UI on the JVM, and
    // Roborazzi turns what it draws into a PNG to compare. Test-only, so the
    // release APK - and F-Droid's reproducible build of it - is untouched;
    // no Gradle plugin either, so a release build resolves nothing new.
    testImplementation("org.robolectric:robolectric:4.18")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.79.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.79.0")
    testImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    // WorkManager's test driver, for the keep-alive's and the USB watch's
    // doWork and for what each trigger enqueues: an in-process WorkManager
    // over a synchronous executor. Its version is work-runtime-ktx's above.
    testImplementation("androidx.work:work-testing:2.12.0")
    // Media3's own parts for testing a player off the phone, at the
    // version the app plays with: ExoPlayer run to a state on a clock the
    // test moves (TestPlayerRunHelper, FakeClock), and codecs Robolectric can
    // stand in for (ShadowMediaCodecConfig) - for RecordingPlayer, which no
    // test reached before (review, 2026-11-18). Test-only: not in the APK.
    testImplementation("androidx.media3:media3-test-utils:1.11.1")
    testImplementation("androidx.media3:media3-test-utils-robolectric:1.11.1")
    // Virtual time for the revive's sequence tests (ReviveSequenceTest): its
    // waits - adbd's four seconds, Allow's twenty - pass at once. The version
    // the app's own coroutines resolve to, which the UI test library already
    // brings to the tests; named here because they use it.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// The shell-side capture daemon ships INSIDE the APK as an asset.
//
// That is the "single APK, nothing else installed" requirement, and it is also
// why the daemon is built from vendored source here rather than downloaded: an
// asset compiled by this build is part of the binary, an opaque blob fetched at
// runtime is a dependency on someone else's server.
//
// It is built by shellserver/build.sh rather than by Gradle: a plain javac
// against android-36's android.jar and d8 - a toolchain of its own, pinned for
// the jar's bytes (see that script), not a variant of the app's.
val shellServerDir = rootProject.file("shellserver")
val shellServerJar = File(shellServerDir, "jemrec-capture.jar")

val buildShellServer = tasks.register<Exec>("buildShellServer") {
    description = "Compiles the shell-side capture daemon to a classes.dex jar"
    workingDir = shellServerDir
    commandLine("./build.sh")
    inputs.dir(File(shellServerDir, "src"))
    inputs.file(File(shellServerDir, "build.sh"))
    // And what build.sh reads besides: the platform and build tools it may be
    // told to use, and which JDK - each changes the jar's bytes. Left out, a
    // change to one kept the jar built before (audit, 2026-11-17).
    inputs.property("ANDROID_PLATFORM", providers.environmentVariable("ANDROID_PLATFORM").orElse("36"))
    inputs.property("BUILD_TOOLS", providers.environmentVariable("BUILD_TOOLS").orElse("36.0.0"))
    inputs.property("JEMREC_JAVA_HOME", providers.environmentVariable("JEMREC_JAVA_HOME").orElse(""))
    inputs.property("JAVA_HOME", providers.environmentVariable("JAVA_HOME").orElse(""))
    inputs.property("JEMREC_JDK_MAJOR", providers.environmentVariable("JEMREC_JDK_MAJOR").orElse("21"))
    // And the files themselves, found as build.sh finds them: an SDK update
    // can change a platform's android.jar, or a build tools' d8, under the
    // same number (review, 2026-11-18). A javac found on the PATH alone is
    // not followed: build.sh checks its major, which is what the bytes
    // depend on.
    val sdk = providers.environmentVariable("ANDROID_HOME").orElse("${System.getProperty("user.home")}/Library/Android/sdk")
    val platform = providers.environmentVariable("ANDROID_PLATFORM").orElse("36")
    val buildTools = providers.environmentVariable("BUILD_TOOLS").orElse("36.0.0")
    inputs.files(sdk.zip(platform) { home, p -> File(home, "platforms/android-$p/android.jar") })
        .withPropertyName("androidJar").optional()
    // d8 itself, not its launcher script: lib/d8.jar is what dexes.
    inputs.files(sdk.zip(buildTools) { home, b -> File(home, "build-tools/$b/lib/d8.jar") })
        .withPropertyName("d8").optional()
    outputs.file(shellServerJar)
}

val copyShellServer = tasks.register<Copy>("copyShellServer") {
    dependsOn(buildShellServer)
    from(shellServerJar)
    into(layout.projectDirectory.dir("src/main/assets"))
}

tasks.named("preBuild") { dependsOn(copyShellServer) }
