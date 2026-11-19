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
        versionCode = 6
        versionName = "1.2.3"
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
    androidResources {
        // NNUE 权重不压缩，保证运行时能通过 openFd 读取
        noCompress.add("nnue")
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
