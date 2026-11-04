group = "com.example.card_scanner"
version = "1.0-SNAPSHOT"

buildscript {
    val kotlinVersion = "2.4.0"
    repositories {
        google()
        mavenCentral()
    }

    dependencies {
        classpath("com.android.tools.build:gradle:9.1.0")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    id("com.android.library")
}

val doubangoEnabled = (findProperty("cardScannerDoubango") as String?)?.toBoolean() == true
if (doubangoEnabled) logger.warn("card_scanner: Doubango evaluation engine ENABLED (non-commercial license)")

android {
    namespace = "com.example.card_scanner"

    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
            // EVALUATION ONLY: Doubango ultimateCreditCard (non-commercial
            // license, ~30 MB per ABI). Enabled by the consuming app with
            // `cardScannerDoubango=true` in its android/gradle.properties.
            if (doubangoEnabled) {
                java.srcDirs("src/doubango/kotlin", "third_party/doubango/java")
                jniLibs.srcDirs("third_party/doubango/jniLibs")
                assets.srcDirs("third_party/doubango/assets")
            }
        }
        getByName("test") {
            java.srcDirs("src/test/kotlin")
        }
    }

    defaultConfig {
        minSdk = 24
        // Shipped with the plugin so every consuming app's R8 keeps them.
        consumerProguardFiles("consumer-rules.pro")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.useJUnitPlatform()

                it.outputs.upToDateWhen { false }

                it.testLogging {
                    events("passed", "skipped", "failed", "standardOut", "standardError")
                    showStandardStreams = true
                }
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    val cameraxVersion = "1.6.2"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    implementation("androidx.activity:activity-ktx:1.11.0")
    implementation("androidx.core:core-ktx:1.17.0")

    // Bundled model (~4 MB/ABI): works without Google Play services.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // Google Pay Payment Card Recognition (optional engine).
    implementation("com.google.android.gms:play-services-wallet:19.5.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.11.0")

    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.json:json:20250517")
}
