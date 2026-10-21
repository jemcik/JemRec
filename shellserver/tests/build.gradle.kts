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
