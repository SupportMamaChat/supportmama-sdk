plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    `maven-publish`
}

group = "dev.supportmama"
version = "0.3.0"

android {
    namespace = "dev.supportmama"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    // Every resource the library ships starts with this, so none can collide
    // with (or be accidentally overridden by) the host app's own.
    resourcePrefix = "supportmama_"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

// `./gradlew :supportmama:publishToMavenLocal`, or JitPack straight from the
// Git repo (see jitpack.yml at the repo root). JitPack serves the library as
// com.github.hsyvy.supportmama-sdk:supportmama:<tag>, so publish under that
// group when it builds.
val jitpack = System.getenv("JITPACK") == "true"
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = if (jitpack) "${System.getenv("GROUP")}.${System.getenv("ARTIFACT")}" else "dev.supportmama"
            artifactId = "supportmama"
            version = if (jitpack) System.getenv("VERSION") else project.version.toString()
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("SupportMama")
                description.set("In-app support chat for Android, answered from Telegram or Slack.")
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20250517")
}
