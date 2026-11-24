import java.text.SimpleDateFormat
import java.util.Date

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "org.shuyah.personnelmaison"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.shuyah.personnelmaison"
        minSdk = 26
        targetSdk = 35
        // Règle de version : VERSION +1 à chaque livraison (voir Version.kt).
        versionCode = 67
        versionName = "1.66"
        // Fichier brut : personnel-maison-<version>-release.apk, livré sous le nom personnel-maison-<version>.apk (sans « debug » ni « release »)
        setProperty("archivesBaseName", "personnel-maison-$versionName")
        // APK ALLÉGÉ (1.47) : seules les bibliothèques natives des TÉLÉPHONES sont gardées (ARM 64 bits et 32 bits). Les versions x86 / x86_64
        // (émulateurs, ordinateurs) pesaient près de 30 Mo à elles seules, d'où un APK de 71 Mo très long à télécharger sur le réseau mobile.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        // Date et heure de compilation, affichées en bas de l'écran principal.
        // Enregistrement d'écran de débogage (icône discrète) : actif par défaut ; -PsansEnregistrement=true le retire
        buildConfigField("boolean", "ENREGISTREMENT_ECRAN", if ((project.findProperty("sansEnregistrement") as String?) == "true") "false" else "true")
        buildConfigField("String", "DATE_COMPILATION", "\"" + SimpleDateFormat("dd/MM/yyyy HH:mm").format(Date()) + "\"")
    }
    // Livraison : l'APK « release » (non débogable) est signé avec la clé locale de l'environnement pour pouvoir s'installer directement ;
    // il est livré sous le nom personnel-maison-<version>.apk (sans « debug »). Aucune clé de publication n'est dans le dépôt.
    // Signature PERMANENTE (clé ShuyahStore) : les 4 valeurs sont lues dans les variables d'environnement ANDROID_KEYSTORE_FILE, ANDROID_KEYSTORE_PASSWORD,
    // ANDROID_KEY_ALIAS et ANDROID_KEY_PASSWORD (ou en propriétés Gradle -P…). Aucune clé ni mot de passe n'est dans le dépôt.
    // Si l'une manque ou si le fichier est absent, on retombe sur la clé locale de l'environnement (clé de test).
    fun valeurSignature(nom: String): String? =
        ((project.findProperty(nom) as String?) ?: System.getenv(nom))?.takeIf { it.isNotBlank() }
    val cheminCle = valeurSignature("ANDROID_KEYSTORE_FILE")
    val mdpFichier = valeurSignature("ANDROID_KEYSTORE_PASSWORD")
    val aliasCle = valeurSignature("ANDROID_KEY_ALIAS")
    val mdpCle = valeurSignature("ANDROID_KEY_PASSWORD")
    val signaturePermanente = cheminCle != null && file(cheminCle).exists() && mdpFichier != null && aliasCle != null && mdpCle != null
    signingConfigs {
        if (signaturePermanente) {
            create("permanente") {
                storeFile = file(cheminCle!!)
                storePassword = mdpFichier
                keyAlias = aliasCle
                keyPassword = mdpCle
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            // Clé permanente si disponible, sinon clé locale de l'environnement
            signingConfig = if (signaturePermanente) signingConfigs.getByName("permanente") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
}

// Schéma de la base exporté à chaque version (sert à écrire et vérifier les migrations)
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    // Base locale chiffrée (Room + SQLCipher) : tout reste sur le téléphone
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("net.zetetic:android-database-sqlcipher:4.5.4")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")
    // Clé de la base et copies des pièces d'identité chiffrées par le Keystore Android
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // Lecture du texte des pièces d'identité (OCR) : modèle embarqué, fonctionne sans Internet
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // Mesure de surfaces en direct (réalité augmentée) : Google ARCore ; facultatif, l'application fonctionne sans (mesure sur photo)
    implementation("com.google.ar:core:1.44.0")
    // Empreinte digitale (verrou de l'application et des écrans sensibles)
    implementation("androidx.biometric:biometric:1.1.0")
    // Signal de présence à SAWALI (règle 4 du propriétaire)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")   // org.json réel pour les tests JVM (lecture de la réponse de ShuyahStore)
}
