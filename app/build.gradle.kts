plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.miguelcaldas.mcsmsforwardermultichannel"
    compileSdk = libs.versions.compileSdk.get().toInt()

    // Release signing is opt-in. Define these in ~/.gradle/gradle.properties (or pass via
    // -P flags / env vars) to produce a signed release APK; otherwise assembleRelease
    // still works but emits an unsigned APK that cannot be installed without resigning.
    val keystorePath = providers.gradleProperty("RELEASE_KEYSTORE_PATH").orNull
    val keystorePassword = providers.gradleProperty("RELEASE_KEYSTORE_PASSWORD").orNull
    val keyAlias = providers.gradleProperty("RELEASE_KEY_ALIAS").orNull
    val keyPassword = providers.gradleProperty("RELEASE_KEY_PASSWORD").orNull
    val keystoreFile = keystorePath?.let { rootProject.file(it) }
    val hasReleaseSigning = keystoreFile?.exists() == true &&
        !keystorePassword.isNullOrEmpty() &&
        !keyAlias.isNullOrEmpty() &&
        !keyPassword.isNullOrEmpty()

    defaultConfig {
        applicationId = "com.miguelcaldas.mcsmsforwardermultichannel"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 4
        versionName = "1.0.4"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = keystoreFile
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "[MCSMSForwarder] No release signing config detected. " +
                        "assembleRelease will produce an unsigned APK. " +
                        "Set RELEASE_KEYSTORE_PATH/_PASSWORD/RELEASE_KEY_ALIAS/_PASSWORD " +
                        "in ~/.gradle/gradle.properties to enable signing."
                )
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core)

    // Jetpack Compose
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
