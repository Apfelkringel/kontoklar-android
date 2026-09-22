plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "de.kontoklar.app"
    compileSdk = 35

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    signingConfigs {
        create("release") {
            val signingKey = System.getenv("KONTOKLAR_SIGNING_KEYSTORE")
            if (!signingKey.isNullOrBlank()) {
                val signingPassword = System.getenv("KONTOKLAR_SIGNING_PASSWORD")
                    ?: error("KONTOKLAR_SIGNING_PASSWORD is required for release builds")
                val signingAlias = System.getenv("KONTOKLAR_SIGNING_KEY_ALIAS")
                    ?: error("KONTOKLAR_SIGNING_KEY_ALIAS is required for release builds")
                storeFile = file(signingKey)
                storePassword = signingPassword
                keyAlias = signingAlias
                keyPassword = signingPassword
            }
        }
    }

    val releaseBuildRequested = gradle.startParameter.taskNames.any { task ->
        task.substringAfterLast(':').contains("Release", ignoreCase = true)
    }
    if (releaseBuildRequested && System.getenv("KONTOKLAR_SIGNING_KEYSTORE").isNullOrBlank()) {
        error("Release builds require the private KontoKlar release keystore; see docs/android-releases.md")
    }

    defaultConfig {
        applicationId = "de.kontoklar.app"
        minSdk = 26
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 94
        versionName = "0.84.1"
        val bankingApiBaseUrl = providers.gradleProperty("KONTOKLAR_BANKING_API_BASE_URL")
            .orElse(providers.environmentVariable("KONTOKLAR_BANKING_API_BASE_URL"))
            .orElse("")
            .get()
            .trimEnd('/')
        buildConfigField("String", "BANKING_API_BASE_URL", "\"$bankingApiBaseUrl\"")
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("net.sf.kxml:kxml2:2.3.0")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
