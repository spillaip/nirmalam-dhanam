import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

val signingProperties = Properties().apply {
    val source = rootProject.file("keystore.properties")
    if (source.isFile) source.inputStream().use(::load)
}
fun releaseSecret(name: String): String? = providers.gradleProperty(name).orElse(providers.environmentVariable(name)).orNull ?: signingProperties.getProperty(name)
val uploadStoreFile = releaseSecret("NIRMALAM_UPLOAD_STORE_FILE")
val uploadStorePassword = releaseSecret("NIRMALAM_UPLOAD_STORE_PASSWORD")
val uploadKeyAlias = releaseSecret("NIRMALAM_UPLOAD_KEY_ALIAS")
val uploadKeyPassword = releaseSecret("NIRMALAM_UPLOAD_KEY_PASSWORD")

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.compose")
}

android { namespace = "com.nirmalamgroup.nirmalamdhanam"; compileSdk = 36
    defaultConfig { applicationId = "com.nirmalamgroup.nirmalamdhanam"; minSdk = 26; targetSdk = 36; versionCode = 13; versionName = "1.3.0" }
    buildFeatures { compose = true; buildConfig = true }
    signingConfigs {
        create("release") {
            if (!uploadStoreFile.isNullOrBlank() && !uploadStorePassword.isNullOrBlank() && !uploadKeyAlias.isNullOrBlank() && !uploadKeyPassword.isNullOrBlank()) {
                storeFile = file(uploadStoreFile)
                storePassword = uploadStorePassword
                keyAlias = uploadKeyAlias
                keyPassword = uploadKeyPassword
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (!uploadStoreFile.isNullOrBlank() && !uploadStorePassword.isNullOrBlank() && !uploadKeyAlias.isNullOrBlank() && !uploadKeyPassword.isNullOrBlank()) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-adaptive-navigation-suite")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.runtime:runtime-saveable")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    // Maintained SQLCipher implementation with 16 KB Android page-size support.
    implementation("net.zetetic:sqlcipher-android:4.17.0@aar")
    implementation("androidx.sqlite:sqlite:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}
