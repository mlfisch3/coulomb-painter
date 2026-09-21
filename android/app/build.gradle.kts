import org.gradle.api.tasks.Exec
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.coulombpainter"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.coulombpainter"
        // API 26 (Android 8.0) is the floor that keeps Vulkan 1.1 available
        // on every supported device, which is the runtime the M3b wgpu bridge
        // will need. `docs/android-plan.md` §2.1 pins this choice.
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.0-m3b"

        // Only the ABIs the Rust cross-compile produces. A device with an
        // unsupported ABI refuses to install rather than crashing at
        // System.loadLibrary time.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // Kept explicit so `assembleDebug` is the intended target for M3a
            // rather than falling through to the release keystore path.
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        // BuildConfig is used by CoulombNative to tag the M3a stub in logs;
        // AGP 8+ requires opting in explicitly.
        buildConfig = true
    }
    // Compose compiler ships out-of-band from the Kotlin bump since Kotlin 2.0;
    // the plugin block above wires it in. No composeOptions block is needed.
}

dependencies {
    // Compose BOM keeps every androidx.compose.* artifact on a compatible
    // version. Bump only the BOM to advance them together.
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    // AndroidExternalSurface (used later by M3b's SurfaceControl bridge) lives
    // in androidx.compose.foundation and comes in through the BOM's foundation
    // artifact. Explicit here so a grep for it finds the transitive origin.
    implementation("androidx.compose.foundation:foundation")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
}

// ---------------------------------------------------------------- Rust cargo-ndk

// Cross-compile the Rust cdylib into the AGP-blessed `jniLibs` layout so the
// APK carries `libcoulomb_jni.so` for both ABIs without a separate shell step.
//
// The task is wired into `preBuild` (the earliest task the Android Gradle
// Plugin exposes that runs before every variant). Registering it against
// mergeDebugNativeLibs or mergeReleaseNativeLibs instead is tempting but AGP
// changes those task names by variant and by AGP version; preBuild is the
// stable entry point.
val cargoRoot = rootProject.file("../rust").absoluteFile
val jniLibsDir = layout.buildDirectory.dir("intermediates/rustJniLibs").get().asFile

val rustAndroidBuild = tasks.register<Exec>("rustAndroidBuild") {
    group = "rust"
    description = "Cross-compile the Rust JNI cdylib for both Android ABIs."
    workingDir = cargoRoot
    // `cargo xtask android-build` is authoritative: it runs the naga WGSL
    // validator plus `cargo ndk`, so this task cannot silently drift from
    // what a bare `cargo xtask android-build` in `rust/` produces.
    commandLine("cargo", "run", "-q", "-p", "xtask", "--", "android-build")

    // The Android NDK path is discovered from local.properties (Android
    // Studio's standard place) or from ANDROID_NDK_HOME / ANDROID_NDK_ROOT.
    // A missing NDK fails this task with a clear message rather than a
    // downstream linker error.
    val localProps = rootProject.file("local.properties")
    val ndkFromLocal: String? = if (localProps.exists()) {
        val props = Properties().apply { localProps.inputStream().use { load(it) } }
        props.getProperty("ndk.dir")
    } else null
    val ndkPath = ndkFromLocal
        ?: System.getenv("ANDROID_NDK_HOME")
        ?: System.getenv("ANDROID_NDK_ROOT")
    if (ndkPath != null) {
        environment("ANDROID_NDK_HOME", ndkPath)
    }
    // Marks so Gradle re-runs the task when Rust sources change and skips
    // when they do not. cargo also caches internally, but this avoids the
    // per-build 100 ms cargo startup on a warm tree.
    inputs.dir(cargoRoot).withPropertyName("cargoRoot").withPathSensitivity(
        org.gradle.api.tasks.PathSensitivity.RELATIVE
    )
    inputs.file(rootProject.file("../rust/Cargo.lock")).withPathSensitivity(
        org.gradle.api.tasks.PathSensitivity.RELATIVE
    )
    outputs.files(
        cargoRoot.resolve("target/aarch64-linux-android/release/libcoulomb_jni.so"),
        cargoRoot.resolve("target/armv7-linux-androideabi/release/libcoulomb_jni.so"),
    )
}

val copyRustJniLibs = tasks.register<Copy>("copyRustJniLibs") {
    group = "rust"
    description = "Stage libcoulomb_jni.so into jniLibs for AGP packaging."
    dependsOn(rustAndroidBuild)
    from(cargoRoot.resolve("target/aarch64-linux-android/release/libcoulomb_jni.so")) {
        into("arm64-v8a")
    }
    from(cargoRoot.resolve("target/armv7-linux-androideabi/release/libcoulomb_jni.so")) {
        into("armeabi-v7a")
    }
    into(jniLibsDir)
}

android.sourceSets["main"].jniLibs.srcDir(jniLibsDir)

tasks.named("preBuild").configure {
    dependsOn(copyRustJniLibs)
}
