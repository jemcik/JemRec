plugins {
    // AGP 9 compiles Kotlin itself - there is no separate kotlin-android
    // plugin here on purpose. The Compose compiler is still its own plugin.
    id("com.android.application") version "9.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}
