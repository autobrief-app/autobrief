plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "app.autobrief.car"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.autobrief.car"
        minSdk = 28
        targetSdk = 34
        versionCode = 9
        versionName = "2.1.0"

        // The look the UI draws with (ui/Theme.kt ThemeVariant): B, or -Pautobrief.themeVariant=CURRENT for the look up to 2.0.1
        val themeVariant = (project.findProperty("autobrief.themeVariant") as String?) ?: "B"
        require(themeVariant in setOf("CURRENT", "B")) { "autobrief.themeVariant is one of the ThemeVariant names, got $themeVariant" }
        buildConfigField("String", "THEME_VARIANT", "\"$themeVariant\"")
    }

    // AAOS car API (CarUxRestrictionsManager for the driving layout)
    useLibrary("android.car")

    signingConfigs {
        // The keystore and its password never live in this repository: CI or the builder passes them in.
        create("release") {
            val path = System.getenv("AUTOBRIEF_KEYSTORE_PATH")
            if (path != null) {
                storeFile = file(path)
                storePassword = System.getenv("AUTOBRIEF_KEYSTORE_PASSWORD")
                keyAlias = "autobrief"
                keyPassword = System.getenv("AUTOBRIEF_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 stays off until the shrunk build has been run on the Pleos emulator
            // (the Vehicle SDK uses Gson reflection).
            isMinifyEnabled = false
            if (System.getenv("AUTOBRIEF_KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true // BuildConfig.DEBUG: place labels are logged by debug builds only; THEME_VARIANT
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            // Roborazzi writes the screen images (build/outputs/roborazzi) that the UI review reads
            it.systemProperty("roborazzi.test.record", "true")
            // the variants ScreenshotTest also draws the key screens in (build/outputs/roborazzi/<VARIANT>/):
            // -Pautobrief.variants=CURRENT,B or =all; empty = the build's own variant
            it.systemProperty("autobrief.variants", (project.findProperty("autobrief.variants") as String?) ?: "")
            it.maxHeapSize = "4g"
        }
    }
}

dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:FusedLocation:1.1.3")

    val composeBom = platform("androidx.compose:compose-bom:2024.02.02")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.26.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.26.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-junit-rule:1.26.0")
}
