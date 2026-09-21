// Root project. Plugin versions are declared here and applied per-module so
// the whole tree bumps in one place. Pinned to the versions Android Studio
// Ladybug (AGP 8.6) accepts against Compose BOM 2024.09.
plugins {
    id("com.android.application") version "8.6.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    // Kotlin 2.0 replaced the KAPT-style Compose compiler with a standalone
    // Gradle plugin; keeping the version pinned here means Compose survives
    // a Kotlin bump only if this plugin bumps too, which is the guarantee
    // JetBrains gives.
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
}
