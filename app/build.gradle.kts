plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "br.com.checkupcelular"
    compileSdk = 34

    defaultConfig {
        // -PappId=... só é usado no teste automático (gera uma cópia com outro nome de pacote)
        applicationId = (project.findProperty("appId") as String?) ?: "br.com.checkupcelular"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkReleaseBuilds = false }
}
