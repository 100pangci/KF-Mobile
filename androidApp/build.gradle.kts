plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "work.miaola.kf"
    compileSdk = 36
    defaultConfig {
        applicationId = "work.miaola.kf.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 14
        versionName = "0.3.1"
        val configUrl = providers.gradleProperty("domainConfigUrl").orElse("").get()
            .replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "DOMAIN_CONFIG_URL", "\"$configUrl\"")
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            // Release signing is deliberately not configured with the debug key.
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation(project(":shared"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.navigation:navigation-compose:2.9.7")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.material:material-icons-extended:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
