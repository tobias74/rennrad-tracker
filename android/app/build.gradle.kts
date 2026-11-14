plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val trackerServerUrl = providers.gradleProperty("trackerServerUrl")
    .orElse("https://biketracking.tobiga.com")
    .get()
require(Regex("https?://[a-zA-Z0-9.\\-:\\[\\]]+").matches(trackerServerUrl)) {
    "trackerServerUrl must be an HTTP(S) origin without a trailing slash"
}

android {
    namespace = "de.rennrad.tracker"
    compileSdk = 36
    defaultConfig {
        applicationId = "de.rennrad.tracker"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "0.2.5"
        buildConfigField("String", "TRACKER_SERVER_URL", "\"$trackerServerUrl\"")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation("androidx.core:core:1.15.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
