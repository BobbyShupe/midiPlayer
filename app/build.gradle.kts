plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.midiPlayer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.midiPlayer"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                // C++ standard flag
                cppFlags.add("-std=c++17")

                // Shared STL for better JNI compatibility
                arguments.add("-DANDROID_STL=c++_shared")
            }
        }

        // Correct placement and syntax for ABI filters
        ndk {
            // Use listOf(...) or += to avoid vararg/type inference issues
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            // Alternative safe syntax:
            // abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a", "x86_64"))
        }
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

    // Modern Kotlin compiler options (replaces deprecated kotlinOptions block)
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        viewBinding = false
        compose = false     // using classic Views
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            // Do NOT set 'version' here unless forcing a specific CMake (usually breaks sync)
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.4")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}