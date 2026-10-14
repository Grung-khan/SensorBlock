import org.gradle.internal.os.OperatingSystem

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.sensorblock.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sensorblock.app"
        minSdk = 31
        // Personal device is Android 13. targetSdk 33 preserves Android 13 behaviour
        // (no Android 14+ foreground-service / sensor-privacy changes).
        targetSdk = 33
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // Only the target device ABI for now; add armeabi-v7a later if needed.
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".debug"
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
        // AGP 8 disables AIDL by default; the mirrored sensor-privacy / shell
        // interfaces in src/main/aidl must be compiled (see ROADMAP.md Milestone 1).
        aidl = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Compose (BOM keeps the artifacts aligned)
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // Persist the user's desired policy
    implementation("androidx.datastore:datastore-preferences:1.1.7")

    // Shizuku: run privileged (uid 2000 / shell) Binder calls without root
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

// ---------------------------------------------------------------------------
// Optional Rust native module (self-test engine). Not required for the Java/Kotlin
// build: it is a separate task so a missing `cargo` never breaks `assembleDebug`.
// Output is copied into app/src/main/jniLibs/<abi>/libsensorblock_native.so.
// ---------------------------------------------------------------------------
val rustCrateDir = rootProject.layout.projectDirectory.dir("crates/sensorblock-native")

val buildRustNative by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the Rust NDK self-test library and copies it into jniLibs."
    workingDir = rustCrateDir.asFile
    val sdk = android.sdkDirectory
    val ndkVersion = android.ndkVersion
    environment("ANDROID_NDK_HOME", File(sdk, "ndk/$ndkVersion").absolutePath)
    if (OperatingSystem.current().isWindows) {
        commandLine("cmd", "/c", "cargo", "ndk", "-t", "arm64-v8a", "build", "--release")
    } else {
        commandLine("cargo", "ndk", "-t", "arm64-v8a", "build", "--release")
    }
    doLast {
        val outDir = layout.projectDirectory.dir("src/main/jniLibs/arm64-v8a").asFile
        outDir.mkdirs()
        val produced = File(rustCrateDir.asFile, "target/aarch64-linux-android/release/libsensorblock_native.so")
        produced.copyTo(File(outDir, "libsensorblock_native.so"), overwrite = true)
    }
}
