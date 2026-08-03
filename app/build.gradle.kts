import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// Release signing lives in local.properties (gitignored) so the keystore path/passwords never
// hit version control. Falls back to null (unsigned release build) if the keys aren't set, so a
// fresh checkout without the keystore still configures everything else.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val releaseStoreFile = localProperties.getProperty("VOYAGE_RELEASE_STORE_FILE")

android {
    namespace = "com.betteraudio"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.betteraudio"
        minSdk = 26
        targetSdk = 36
        versionCode = 59
        versionName = "1.9.10b"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Vosk ships native libs per-ABI; restrict to 64-bit ARM (every modern phone) so the
        // added speech-recognition support doesn't balloon the APK with x86/32-bit variants.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = localProperties.getProperty("VOYAGE_RELEASE_STORE_PASSWORD")
                keyAlias = localProperties.getProperty("VOYAGE_RELEASE_KEY_ALIAS")
                keyPassword = localProperties.getProperty("VOYAGE_RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseStoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            // Room's MigrationTestHelper runs as an instrumented (androidTest) test, and the
            // emulator harness (scripts/emu.sh) runs on an x86_64 system image — scoped to debug
            // only so release APK size/ABI coverage is unaffected.
            ndk { abiFilters += listOf("x86_64") }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("androidTest") {
            java.srcDirs("src/androidTest/java")
            // MigrationTestHelper reads exported schema JSONs from test assets at
            // assets/<db-qualified-name>/<version>.json — exactly the layout room.schemaLocation
            // below already writes to, so just point the test APK's assets at it directly.
            assets.srcDirs("$projectDir/schemas")
        }
    }
}

ksp {
    // Room schema history, diffable per version — see CLAUDE.md's migration-verification recipe.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Media3
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // Coil
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.palette)
    implementation(libs.okhttp)

    // Theme engine (Material You color generation + custom-palette export/import)
    implementation(libs.material.kolor)
    implementation(libs.kotlinx.serialization.json)

    // Shape morphing (Material You container-transform rebuild) — material3 1.4.0 does NOT
    // depend on this; MaterialShapes/Morph aren't available otherwise (see ui/material/motion).
    implementation(libs.androidx.graphics.shapes)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // SAF folder access for auto-backup
    implementation(libs.androidx.documentfile)

    // Vosk — offline (on-device) speech recognition for paragraph-resolution sync.
    // JNA must be the AAR packaging (Vosk loads its native libs through it).
    implementation(libs.vosk.android)
    implementation(variantOf(libs.jna) { artifactType("aar") })

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

