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
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.malekplay"
        minSdk = 23
        targetSdk = 35
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


    // Enable only after the native platform adapters and JNI bridge are complete.
    // Phase 6 CI will pass -PenableNstackxNativeBuild=true explicitly.
    if (providers.gradleProperty("enableNstackxNativeBuild").orNull == "true") {
        externalNativeBuild {
            cmake {
                path = file("../native/nstackx/CMakeLists.txt")
                version = "3.22.1"
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

    implementation(libs.androidx.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)

    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.animation)
    implementation(libs.compose.icons.extended)
    implementation(libs.coil.compose)
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.effect)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.cast)
    implementation(project(":ass-media-local"))

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)

    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.mlkit.translate)
    // Offline speech recognition; language models are installed on demand, never bundled in the APK.
    implementation("com.alphacephei:vosk-android:0.3.75")

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

    implementation(libs.smbj)
    implementation(libs.sshj)
    implementation(libs.okhttp)
    implementation(libs.zxing.core)
    
    // ⚡️ المكتبة السحرية للسرعة (Okio)
    implementation("com.squareup.okio:okio:3.9.0")
    
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
