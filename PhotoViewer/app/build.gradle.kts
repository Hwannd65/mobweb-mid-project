plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.photoviewer"
    compileSdk {
        version = release(37)
    }

    buildFeatures { buildConfig = true }
    defaultConfig {
        val serverUrl = providers.gradleProperty("photoBlogUrl")
            .orElse("https://hwannd65mid.pythonanywhere.com").get()
        buildConfigField("String", "PHOTO_BLOG_URL", "\"$serverUrl\"")
        applicationId = "com.example.photoviewer"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.recyclerview)
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.4.0")
    androidTestImplementation(libs.ext.junit)
}