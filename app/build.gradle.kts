plugins {
    id("com.android.application")
}

android {
    namespace = "com.qqswitcher.demo"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.qqswitcher.demo"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
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
}

dependencies {
    implementation("com.google.code.gson:gson:2.10.1")
}
