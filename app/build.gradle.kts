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
        // O GitHub passa -Pvc=<número da compilação>; vira a versão 1.0.<número>
        val vc = (project.findProperty("vc") as String?)?.toInt() ?: 1
        versionCode = vc
        versionName = "1.0.$vc"
    }
    // Assinatura fixa (vem dos segredos do GitHub). Sem ela o Android não aceita atualizar o app.
    val ksFile = System.getenv("KEYSTORE_FILE")
    signingConfigs {
        if (ksFile != null && file(ksFile).exists()) {
            create("fixa") {
                storeFile = file(ksFile)
                storePassword = System.getenv("KEYSTORE_PASS")?.trim()
                keyAlias = "checkup"
                keyPassword = System.getenv("KEYSTORE_PASS")?.trim()
            }
        }
    }
    buildTypes {
        getByName("debug") {
            signingConfigs.findByName("fixa")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkReleaseBuilds = false }
}
