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
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.compose.bom))
    androidTestImplementation(platform(libs.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.datastore.preferences)
    ksp(libs.room.compiler)
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.mlkit.translate)
    implementation(libs.smbj)
    implementation(libs.sshj)
    implementation(libs.okhttp)
}
