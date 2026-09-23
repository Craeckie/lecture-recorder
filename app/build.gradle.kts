import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Set by CI from repo secrets; absent for every local build, where release stays unsigned
// exactly as scripts/release.sh (which signs the debug APK separately) expects. Signing is
// enabled only when ALL FOUR are present -- a partially-configured secret set (e.g. only the
// keystore itself, not yet the alias/password) must fall back to unsigned, not hand
// PackageAndroidArtifact an empty alias and fail the whole build.
val ciSigningEnv = mapOf(
    "KEYSTORE_BASE64" to System.getenv("KEYSTORE_BASE64"),
    "KEYSTORE_PASSWORD" to System.getenv("KEYSTORE_PASSWORD"),
    "KEY_ALIAS" to System.getenv("KEY_ALIAS"),
    "KEY_PASSWORD" to System.getenv("KEY_PASSWORD"),
)
val hasCiSigning = ciSigningEnv.values.all { !it.isNullOrBlank() }
if (ciSigningEnv.values.any { !it.isNullOrBlank() } && !hasCiSigning) {
    val missing = ciSigningEnv.filterValues { it.isNullOrBlank() }.keys
    logger.warn("CI signing secrets partially configured, staying unsigned. Missing: $missing")
}

android {
    namespace = "org.Craeckie.lecturerecorder"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.Craeckie.lecturerecorder"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    if (hasCiSigning) {
        signingConfigs {
            create("release") {
                val keystoreFile = layout.buildDirectory.file("ci-release.jks").get().asFile
                keystoreFile.parentFile.mkdirs()
                keystoreFile.writeBytes(Base64.getDecoder().decode(ciSigningEnv["KEYSTORE_BASE64"]))
                storeFile = keystoreFile
                storePassword = ciSigningEnv["KEYSTORE_PASSWORD"]
                keyAlias = ciSigningEnv["KEY_ALIAS"]
                keyPassword = ciSigningEnv["KEY_PASSWORD"]
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasCiSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions { jvmTarget = "11" }

    buildFeatures { compose = true }

    // Unit tests touch only MicRouting's pure logic, but this keeps any stray android.jar
    // call returning a default instead of throwing "not mocked".
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
