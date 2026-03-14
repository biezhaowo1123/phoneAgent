plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.phoneagent"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.phoneagent"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.7"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/io.netty.versions.properties"
        }
    }
}

// Force all Compose libs to consistent matched versions
configurations.all {
    resolutionStrategy {
        // animation-core 1.6.0 has KeyframesSpecConfig.at with old signature
        // material3 1.1.2 uses the old signature
        force("androidx.compose.animation:animation-core:1.6.0")
        force("androidx.compose.animation:animation:1.6.0")
        force("androidx.compose.material3:material3:1.2.0")
        force("androidx.compose.ui:ui:1.6.0")
        force("androidx.compose.ui:ui-graphics:1.6.0")
        force("androidx.compose.ui:ui-text:1.6.0")
        force("androidx.compose.ui:ui-unit:1.6.0")
        force("androidx.compose.ui:ui-util:1.6.0")
        force("androidx.compose.ui:ui-tooling-preview:1.6.0")
        force("androidx.compose.foundation:foundation:1.6.0")
        force("androidx.compose.foundation:foundation-layout:1.6.0")
        force("androidx.compose.runtime:runtime:1.6.0")
        force("androidx.compose.runtime:runtime-saveable:1.6.0")
        force("androidx.compose.material:material-icons-core:1.6.0")
        force("androidx.compose.material:material-icons-extended:1.6.0")
    }
}

dependencies {
    // Compose — explicit versions, no BOM to avoid resolution conflicts
    implementation("androidx.compose.ui:ui:1.6.0")
    implementation("androidx.compose.ui:ui-graphics:1.6.0")
    implementation("androidx.compose.ui:ui-tooling-preview:1.6.0")
    implementation("androidx.compose.material3:material3:1.2.0")
    implementation("androidx.compose.material:material-icons-extended:1.6.0")
    implementation("androidx.compose.animation:animation-core:1.6.0")
    implementation("androidx.compose.foundation:foundation:1.6.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.navigation:navigation-compose:2.7.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    // Ktor - HTTP client for AI API & MCP
    val ktorVersion = "2.3.7"
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-client-websockets:$ktorVersion")
    implementation("io.ktor:ktor-client-logging:$ktorVersion")

    // Ktor - Embedded server (Gateway control plane)
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // Room - local database for tasks
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // WorkManager - scheduled tasks
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // DataStore - settings
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Shizuku - privileged shell without root
    val shizukuVersion = "13.1.5"
    implementation("dev.rikka.shizuku:api:$shizukuVersion")
    implementation("dev.rikka.shizuku:provider:$shizukuVersion")

    // Coil - image loading for Vision/Image chat
    implementation("io.coil-kt:coil-compose:2.5.0")

    // Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
}
