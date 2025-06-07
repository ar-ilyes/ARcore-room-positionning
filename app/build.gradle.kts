plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.arcore"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.arcore"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    // Remove compose build features since we're not using Compose for AR
    // buildFeatures {
    //     compose = true
    // }
}

dependencies {
    // ARCore - Updated to latest version
    implementation("com.google.ar:core:1.42.0")

    // Remove Sceneform as it causes conflicts and is deprecated
    // implementation("com.google.ar.sceneform:core:1.17.1")
    // implementation(libs.sceneform.ux)

    // Core Android libraries
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // UI Components
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.11.0")

    // Remove Compose dependencies since we're using traditional Views
    // implementation(libs.androidx.activity.compose)
    // implementation(platform(libs.androidx.compose.bom))
    // implementation(libs.androidx.ui)
    // implementation(libs.androidx.ui.graphics)
    // implementation(libs.androidx.ui.tooling.preview)
    // implementation(libs.androidx.material3)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // Remove Compose testing
    // androidTestImplementation(platform(libs.androidx.compose.bom))
    // androidTestImplementation(libs.androidx.ui.test.junit4)
    // debugImplementation(libs.androidx.ui.tooling)
    // debugImplementation(libs.androidx.ui.test.manifest)
}

// Force resolution of conflicting dependencies
configurations.all {
    resolutionStrategy {
        force("androidx.core:core:1.12.0")
        force("androidx.core:core-ktx:1.12.0")
    }

    // Exclude old support libraries that conflict with AndroidX
    exclude(group = "com.android.support", module = "support-compat")
    exclude(group = "com.android.support", module = "support-core-utils")
    exclude(group = "com.android.support", module = "support-core-ui")
    exclude(group = "com.android.support", module = "support-fragment")
    exclude(group = "com.android.support", module = "support-annotations")
}