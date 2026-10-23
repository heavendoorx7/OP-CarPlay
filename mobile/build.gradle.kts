plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val p7Profile = providers.gradleProperty("p7Profile").map(String::toBoolean).getOrElse(false)
val p7ViewAreas = p7Profile && providers.gradleProperty("p7ViewAreas").map(String::toBoolean).getOrElse(false)
val p7FastReconnect = p7ViewAreas || (p7Profile && providers.gradleProperty("p7FastReconnect").map(String::toBoolean).getOrElse(false))
// Private deployment material stays outside the source tree and source archives.
val bundledMfiAssets = providers.environmentVariable("OP_MFI_ASSETS_DIR")
if (p7Profile && bundledMfiAssets.isPresent) {
    listOf("certificate.p7b", "identity.pk8").forEach {
        require(file("${bundledMfiAssets.get()}/op_mfi/$it").isFile) { "Incomplete bundled MFi pair" }
    }
}

android {
    namespace = "com.shilapi.xcertplay"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = if (p7Profile) "com.shilapi.xcertplay.p7" else "com.shilapi.xcertplay"
        minSdk = 28
        targetSdk = 37
        // Keep upgrade ordering independent of the public OP CarPlay version.
        versionCode = if (p7Profile) 1350 else 1303
        // v1.0 is reserved for the final, accepted release. P7 is a vehicle profile.
        versionName = if (p7Profile) "0.7" else "1.3.3"
        manifestPlaceholders["launcherIcon"] = if (p7Profile) "@mipmap/op_launcher" else "@mipmap/ic_launcher"
        manifestPlaceholders["launcherRoundIcon"] = if (p7Profile) "@mipmap/op_launcher_round" else "@mipmap/ic_launcher_round"
        if (p7Profile) {
            resValue("string", "app_name", "OP CarPlay")
            resValue("bool", "config_p7_display_profile", "true")
            resValue("bool", "config_p7_dynamic_viewareas", p7ViewAreas.toString())
            resValue("bool", "config_p7_fast_reconnect", p7FastReconnect.toString())
        }

    }

    signingConfigs {
        create("release") {
            storeFile = file(
                providers.environmentVariable("ANDROID_KEYSTORE_PATH")
                    .getOrElse("missing-release-keystore.jks"),
            )
            storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").getOrElse("")
            keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").getOrElse("")
            keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").getOrElse("")
        }
    }

    buildTypes {
        debug {
            if (providers.environmentVariable("ANDROID_KEYSTORE_PATH").isPresent) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            optimization {
                enable = false
            }
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        resValues = true
    }
    if (p7Profile && bundledMfiAssets.isPresent) {
        sourceSets.getByName("main").assets.srcDir(bundledMfiAssets.get())
    }
    // ContentResolver reads the private provider's assets through openFd().
    androidResources { noCompress += listOf("p7b", "pk8") }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(project(":common"))
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.app.projected)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
