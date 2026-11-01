import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use(::load)
}

fun signingValue(env: String, key: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() }
        ?: keystoreProperties.getProperty(key)?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("ANDROID_KEYSTORE_PATH", "storeFile")
val releaseStorePassword = signingValue("ANDROID_KEYSTORE_PASSWORD", "storePassword")
val releaseKeyAlias = signingValue("ANDROID_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = signingValue("ANDROID_KEY_PASSWORD", "keyPassword")

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
    signingConfigs {
        if (releaseStoreFile != null && releaseStorePassword != null &&
            releaseKeyAlias != null && releaseKeyPassword != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            // Local: keystore.properties. CI: ANDROID_KEYSTORE_* environment variables.
            // The debug key is never used for release builds.
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
