plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Push (docs/protocol.md, "PUT /v1/push"): put your Firebase project's google-services.json in
 * sample/ (for the application id below) and the sample receives replies by
 * FCM. Without it the sample builds the same and checks in the background.
 */
if (file("google-services.json").exists()) apply(plugin = "com.google.gms.google-services")

/**
 * Where the sample's chat goes. Defaults to a `wrangler dev` on this machine
 * as the emulator sees it; point it at your deployed server with
 * `./gradlew :sample:installDebug -Psupportmama.endpoint=https://…`.
 */
val endpoint = providers.gradleProperty("supportmama.endpoint").orElse("https://supportmama.pages.dev").get()
/** Hosted SupportMama only: `-Psupportmama.appKey=smk_…`. */
val appKey = providers.gradleProperty("supportmama.appKey").orElse("").get()

android {
    namespace = "dev.supportmama.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.supportmama.sample"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "SUPPORT_ENDPOINT", "\"$endpoint\"")
        buildConfigField("String", "SUPPORT_APP_KEY", "\"$appKey\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":supportmama"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
}
