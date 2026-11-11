plugins {
    id("com.android.application") version "8.11.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.25" apply false
}

// Los repositorios van aquí, en el proyecto: si el equipo tiene un guion global de Gradle
// que añade los suyos, los declarados solo en settings.gradle.kts se ignoran.
allprojects {
    repositories {
        google()
        mavenCentral()
    }
}
