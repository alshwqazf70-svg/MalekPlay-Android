plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt.android)
}

android {
    namespace = "com.malekplay"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.malekplay"
        minSdk = 23
        targetSdk = 35
        // Allow release versioning to be supplied by CI without editing source files.
        versionCode = providers.gradleProperty("malekplayVersionCode")
            .orNull?.toIntOrNull() ?: 2
        versionName = providers.gradleProperty("malekplayVersionName")
            .orNull?.takeIf(String::isNotBlank) ?: "2.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    androidResources {
        localeFilters += setOf("ar", "en")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Release signing is optional for local analysis builds. Credentials are read
    // only from environment variables and must never be committed to the repository.
    val releaseStoreFile = providers.environmentVariable("MALEKPLAY_RELEASE_STORE_FILE").orNull
    val releaseStorePassword = providers.environmentVariable("MALEKPLAY_RELEASE_STORE_PASSWORD").orNull
    val releaseKeyAlias = providers.environmentVariable("MALEKPLAY_RELEASE_KEY_ALIAS").orNull
    val releaseKeyPassword = providers.environmentVariable("MALEKPLAY_RELEASE_KEY_PASSWORD").orNull
    val hasReleaseSigning = !releaseStoreFile.isNullOrBlank() &&
        !releaseStorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank()

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        getByName("release") {
            // Without all four signing environment variables this remains an
            // unsigned analysis artifact; never silently use the debug key.
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // ABI APK splits are enabled only for APK assembly. Android App Bundles
    // perform their own device targeting and must be built without APK splits.
    if (providers.gradleProperty("enableAbiSplits").orNull == "true") {
        splits {
            abi {
                isEnable = true
                reset()
                include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
                isUniversalApk = false
            }
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
            freeCompilerArgs.add("-Xjvm-default=all")
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    implementation(platform(libs.compose.bom))
    androidTestImplementation(platform(libs.compose.bom))

    // AndroidX core
    implementation(libs.androidx.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.viewmodel.compose)

    // Navigation
    implementation(libs.navigation.compose)

    // Compose
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Media3
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.cast)
    implementation(libs.assMedia)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Datastore
    implementation(libs.datastore.preferences)

    // Hilt
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // ML Kit on-device translation
    implementation(libs.mlkit.translate)

    // Unit tests and SQLite migration coverage
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.room:room-testing:2.8.5")
    testImplementation("org.robolectric:robolectric:4.14.1")
    androidTestImplementation("androidx.room:room-testing:2.8.5")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Network protocols
    implementation(libs.smbj)
    implementation(libs.sshj)
    implementation(libs.okhttp)
    implementation(libs.zxing.core)
    // Standalone camera QR scanner; works without downloading a Google Play services module.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}