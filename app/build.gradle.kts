plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.xiangqi.autoassist"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.xiangqi.autoassist"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
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
    packaging {
        // 需要把 libpikafish.so 解压为真实文件（nativeLibraryDir），供 ProcessBuilder 执行
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
