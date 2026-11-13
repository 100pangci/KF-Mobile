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
    // Every opt-in live path must be a task input. Otherwise the write tests can
    // be silently skipped as UP-TO-DATE after an ordinary test run.
    val liveFlags = listOf(
        "KF_LIVE_TEST",
        "KF_LOGIN_TEST",
        "KF_SITE_AUDIT",
        "KF_AUDIT_RENDER",
        "KF_LIVE_WRITE_TEST",
        "KF_LIVE_FULL_WRITE_TEST",
    )
    liveFlags.forEach { name ->
        inputs.property(name, providers.environmentVariable(name).orElse("false"))
    }
    // Local captures/cookies decide whether several opt-in tests can run at all.
    inputs.files(rootProject.fileTree(".secrets") { include("*.cookies.txt", "site-audit/**") })
        .withPropertyName("localLiveFixtures").optional()
    // Any live switch disables up-to-date checks so a previously successful run
    // can never masquerade as this run's result.
    outputs.upToDateWhen { liveFlags.none { System.getenv(it) == "true" } }
}
