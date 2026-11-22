plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.arc.xmanager"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.arc.xmanager"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // Signature : clé fournie par variables d'environnement (secrets GitHub en CI),
    // ou keystore/sideload.keystore en local (non versionné). Sinon : clé debug par défaut.
    val storePath = System.getenv("ARC_KEYSTORE") ?: "keystore/sideload.keystore"
    val storeFileObj = rootProject.file(storePath)
    val storePass = System.getenv("ARC_KEYSTORE_PASSWORD")
    val hasKey = storeFileObj.exists() && storePass != null
    signingConfigs {
        if (hasKey) {
            create("sideload") {
                storeFile = storeFileObj
                storePassword = storePass
                keyAlias = System.getenv("ARC_KEY_ALIAS") ?: "arc"
                keyPassword = storePass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("sideload") ?: signingConfigs.getByName("debug")
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
    lint {
        checkReleaseBuilds = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.04.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.browser:browser:1.8.0")
    testImplementation("junit:junit:4.13.2")
}
