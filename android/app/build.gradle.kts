import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Clave con la que se firma el APK (../keys/android-keystore.properties, fuera de
// cualquier repositorio). Android solo deja actualizar una app instalada si el APK
// nuevo lleva la misma firma: perderla obliga a desinstalar.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("../keys/android-keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "app.songverse.motor"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.songverse.motor"
        minSdk = 26
        targetSdk = 36
        // Misma numeración que el motor de Windows: el protocolo es el mismo.
        versionCode = 1002002
        versionName = "1.2.2"
        ndk {
            // Teléfonos y tabletas de 64 bits (todos los de 2019 en adelante).
            abiFilters += listOf("arm64-v8a")
        }
        resourceConfigurations += listOf("es", "en")
    }

    // Python, ffmpeg y QuickJS viajan como bibliotecas nativas y se ejecutan desde la
    // carpeta donde Android las extrae: tienen que ir sin comprimir «a la antigua».
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    signingConfigs {
        create("release") {
            if (keystoreProperties.containsKey("storeFile")) {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            if (keystoreProperties.containsKey("storeFile")) signingConfig = signingConfigs.getByName("release")
            // La biblioteca de yt-dlp usa reflexión (Jackson): sin ofuscar ni recortar.
            isMinifyEnabled = false
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
        buildConfig = true
    }
}

dependencies {
    // yt-dlp + Python + QuickJS, y ffmpeg, compilados para Android (GPL v3 / Unlicense).
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
}
