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
    //   targetSdk  36  - which platform behaviour changes we opt into. The
    //                    device under test reports ro.build.version.sdk 36, so
    //                    36 is the newest target any measurement here covers.
    //   minSdk     31  - the run-time floor. Android 12 is where the shell-side
    //                    capture approach starts working at all.
    //
    // A FOURTH "36" exists elsewhere and is not this one: the shell-side
    // capture binary must be compiled against ANDROID_PLATFORM=36 because it
    // links @hide framework internals and has to match the platform the device
    // actually runs. That is a property of the binary, not of the app, and the
    // two happening to share the number 36 today is a coincidence.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.jemcik.jemrec"
        minSdk = 31
        targetSdk = 36

        // Release workflow passes these from the git tag; a local build takes
        // the fallbacks. Hardcoding them ships every release as versionCode 1,
        // and Android refuses to install an APK whose versionCode is not higher
        // than the installed one.
        versionCode = (findProperty("jemrecVersionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("jemrecVersionName") as String?) ?: "0.1"
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
            // Off for now. R8 on a codebase that reaches @hide internals and
            // reflects into Conscrypt needs its own pass with its own testing,
            // not a flag flipped while the transport is still being proven.
            isMinifyEnabled = false
            // With no keystore configured the APK is left UNSIGNED rather than
            // falling back to the debug key: a debug-signed "release" installs
            // once and can never be upgraded by a properly signed one.
            if (signingReady) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    testOptions {
        // android.util.Log and the rest of the stub android.jar throw on the
        // JVM. With this they return defaults instead, so pure logic that
        // happens to log a line (Setup.currentStep) is testable off-device.
        unitTests.isReturnDefaultValues = true
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
    implementation("org.conscrypt:conscrypt-android:2.5.3")

    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
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

    // Keeps the shell-side recorder alive without a permanent foreground
    // service: a periodic job revives it when it is down and Wi-Fi is around.
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    testImplementation("junit:junit:4.13.2")
    // Mockito stands in for the two Android types the pure-logic tests cannot
    // construct on the JVM: android.net.Uri (abstract in the stub android.jar,
    // and Uri.EMPTY/parse() are null there) and android.app.Application. The
    // inline mock maker in Mockito 5 mocks abstract classes without extra
    // setup. Nothing else in the tests needs a framework.
    testImplementation("org.mockito:mockito-core:5.14.2")
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
// It is built by shellserver/build.sh rather than by Gradle, because it must be
// compiled against the platform the DEVICE runs (android-36) with its @hide
// internals intact - which is a different toolchain from the app's, not a
// variant of it.
val shellServerDir = rootProject.file("shellserver")
val shellServerJar = File(shellServerDir, "jemrec-capture.jar")

val buildShellServer = tasks.register<Exec>("buildShellServer") {
    description = "Compiles the shell-side capture daemon to a classes.dex jar"
    workingDir = shellServerDir
    commandLine("./build.sh")
    inputs.dir(File(shellServerDir, "src"))
    inputs.file(File(shellServerDir, "build.sh"))
    outputs.file(shellServerJar)
}

val copyShellServer = tasks.register<Copy>("copyShellServer") {
    dependsOn(buildShellServer)
    from(shellServerJar)
    into(layout.projectDirectory.dir("src/main/assets"))
}

tasks.named("preBuild") { dependsOn(copyShellServer) }
