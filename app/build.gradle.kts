plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Versão da ponte (independente da do Sereno). O GitHub Actions passa
// -PversaoNome e -PversaoCodigo a partir da tag v<nome>; sem elas, 0.0.0.
val versaoNome = (findProperty("versaoNome") as String?) ?: "0.0.0"
val versaoCodigo = ((findProperty("versaoCodigo") as String?) ?: "1").toInt()

android {
    namespace = "br.com.orrico.ponte"
    compileSdk = 36

    defaultConfig {
        applicationId = "br.com.orrico.ponte"
        // 28 (Android 9): o mínimo do Health Connect como app.
        minSdk = 28
        targetSdk = 36
        versionCode = versaoCodigo
        versionName = versaoNome
    }

    // Produção e desenvolvimento lado a lado no mesmo celular; cada uma só
    // fala com o próprio projeto e o próprio site.
    flavorDimensions += "ambiente"
    productFlavors {
        create("prod") {
            dimension = "ambiente"
            buildConfigField("String", "SERVIDOR", "\"https://qoartxffsxjdhqkarxkl.supabase.co/functions/v1/saude-ponte\"")
            buildConfigField("String", "SITE", "\"https://sereno-web-35x.pages.dev\"")
            resValue("string", "app_name", "Sereno ponte")
        }
        create("dev") {
            dimension = "ambiente"
            applicationIdSuffix = ".dev"
            buildConfigField("String", "SERVIDOR", "\"https://hlpphudadjrqbfemeqhk.supabase.co/functions/v1/saude-ponte\"")
            buildConfigField("String", "SITE", "\"https://sereno-web-dev.pages.dev\"")
            resValue("string", "app_name", "Sereno ponte (dev)")
        }
    }

    // Assinatura: a keystore própria da ponte vem dos segredos do GitHub
    // (PONTE_KEYSTORE_ARQUIVO, PONTE_KEYSTORE_SENHA). Sem elas (build local
    // de teste), o release sai com a chave de depuração.
    val arquivoChave = System.getenv("PONTE_KEYSTORE_ARQUIVO")
    val senhaChave = System.getenv("PONTE_KEYSTORE_SENHA")
    signingConfigs {
        if (arquivoChave != null && senhaChave != null) {
            create("ponte") {
                storeFile = file(arquivoChave)
                storePassword = senhaChave
                keyAlias = "ponte"
                keyPassword = senhaChave
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("ponte") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
        resValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.health.connect:connect-client:1.1.0-beta02")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
