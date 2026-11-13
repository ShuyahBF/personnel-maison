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
        versionCode = 13
        versionName = "1.12"
        // Nom du fichier livré : personnel-maison-<version>-debug.apk (copié ensuite en personnel-maison-<version>.apk)
        setProperty("archivesBaseName", "personnel-maison-$versionName")
        // Date et heure de compilation, affichées en bas de l'écran principal.
        buildConfigField("String", "DATE_COMPILATION", "\"" + SimpleDateFormat("dd/MM/yyyy HH:mm").format(Date()) + "\"")
    }
    buildTypes { release { isMinifyEnabled = false } }
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
    // Signal de présence à SAWALI (règle 4 du propriétaire)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
}
