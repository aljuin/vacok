plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val versionCodeProp: Int = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
val versionNameProp: String = (project.findProperty("versionName") as String?) ?: "1.0"
val serverUrl: String = (project.findProperty("serverUrl") as String? ?: "").trim().trimEnd('/')

android {
    signingConfigs {
        create("release") {
            // Ключ подписи берётся из переменных окружения (GitHub Actions). Без них APK не подписывается.
            System.getenv("KEYSTORE_PATH")?.let {
                storeFile = file(it)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }
    namespace = "com.messenger.app"      
    compileSdk = 35

    defaultConfig {
        applicationId = "com.messenger.app"   
        minSdk = 26
        targetSdk = 35
        versionCode = versionCodeProp
        versionName = versionNameProp
        buildConfigField("String", "SERVER_URL", "\"$serverUrl\"")
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        debug {
            // В отладочной сборке можно ходить на сервер по http (например, http://10.0.2.2:3000)
            manifestPlaceholders["usesCleartext"] = "true"
        }
        release {
            manifestPlaceholders["usesCleartext"] = "false"
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
}
