plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Для FCM-доставки (опционально) — см. INSTALL.md:
    // id("com.google.gms.google-services")
}

android {
    namespace = "com.example.shlagbaumrelay"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.shlagbaumrelay"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-prototype"
        // Адрес backend по умолчанию (переопределяется в настройках приложения).
        buildConfigField("String", "DEFAULT_SERVER", "\"http://10.0.2.2:8080\"")
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-ktx:1.8.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // --- FCM-доставка (опционально; раскомментируйте вместе с плагином и google-services.json) ---
    // implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    // implementation("com.google.firebase:firebase-messaging-ktx")
}
