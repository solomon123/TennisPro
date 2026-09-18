import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Upload-key credentials for Play release builds. Kept out of git (see
// .gitignore); without the file, release builds come out unsigned, which is
// fine for everything except uploading. See docs/PLAY_RELEASE.md.
val keystoreProperties = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.tennispro.phone"
    compileSdk = 36

    defaultConfig {
        // MUST match the wear module's applicationId. The Wearable Data Layer
        // pairs the two APKs by package name + signing certificate; if either
        // differs the watch will never appear as a capable node. Permanent once
        // uploaded to Google Play.
        applicationId = "com.tennisreplay"
        minSdk = 30
        // Google Play requires API 36 for new phone apps from 2026-08-31.
        targetSdk = 36
        // Phone and watch share one Play listing, where every uploaded bundle
        // needs a unique versionCode: the phone takes 1xxxxxx, the watch 2xxxxxx,
        // both from the one number in gradle.properties.
        versionCode = 1_000_000 + providers.gradleProperty("appVersionCode").get().toInt()
        versionName = providers.gradleProperty("appVersionName").get()
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("upload") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("upload")
        }
        debug {
            isMinifyEnabled = false
            // A separate app id, so a development build and the Play build can be
            // installed side by side (they are signed with different keys, so one
            // can never update the other). The wear module uses the same suffix,
            // keeping debug phone and debug watch paired with each other.
            applicationIdSuffix = ".debug"
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
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.camera.view)

    // Pose landmarks, to narrow a serve's search window to when the swing
    // actually happens — see docs/ARCHITECTURE.md's Serve speed section for
    // why this isn't used to pinpoint contact directly.
    implementation(libs.mediapipe.tasks.vision)

    implementation(libs.play.services.wearable)

    // In-app updates. Verified to add no permissions of its own: it talks to the
    // installed Play Store app over IPC, so the INTERNET removal in the manifest
    // stands. See docs/PLAY_RELEASE.md.
    implementation(libs.play.app.update)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
