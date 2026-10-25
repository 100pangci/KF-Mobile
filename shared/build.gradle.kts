plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    id("com.android.library")
}

kotlin {
    androidTarget()
    // JVM target runs the real HTML parser/network tests without an emulator.
    jvm("desktop")
    jvmToolchain(21)
    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        val jvmSharedMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation("com.squareup.okhttp3:okhttp:4.12.0")
                implementation("org.jsoup:jsoup:1.18.3")
            }
        }
        androidMain.get().dependsOn(jvmSharedMain)
        getByName("desktopMain").dependsOn(jvmSharedMain)
        getByName("desktopTest").dependencies {
            implementation(kotlin("test-junit"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
            implementation(compose.desktop.currentOs)
        }
    }
}

android {
    namespace = "work.miaola.kf.shared"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

tasks.withType<Test>().configureEach {
    systemProperty("kf.projectRoot", rootProject.projectDir.absolutePath)
    inputs.property("liveReadEnabled", providers.environmentVariable("KF_LIVE_TEST").orElse("false"))
    inputs.property("liveLoginEnabled", providers.environmentVariable("KF_LOGIN_TEST").orElse("false"))
    inputs.property("siteAuditEnabled", providers.environmentVariable("KF_SITE_AUDIT").orElse("false"))
    inputs.property("auditRenderEnabled", providers.environmentVariable("KF_AUDIT_RENDER").orElse("false"))
    outputs.upToDateWhen { System.getenv("KF_LIVE_TEST") != "true" && System.getenv("KF_LOGIN_TEST") != "true" && System.getenv("KF_SITE_AUDIT") != "true" && System.getenv("KF_AUDIT_RENDER") != "true" }
}
