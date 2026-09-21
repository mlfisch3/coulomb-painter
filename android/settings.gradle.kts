pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    // FAIL_ON_PROJECT_REPOS is what a fresh Android Studio project uses today.
    // It refuses per-module repositories so a `google()` accidentally added
    // inside `app/build.gradle.kts` fails loudly rather than shadowing this
    // canonical list.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "coulomb-painter"
include(":app")
