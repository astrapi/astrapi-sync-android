import java.util.Properties

// Kein org.jetbrains.kotlin.android-Plugin -- seit AGP 9 bringt
// com.android.application die Kotlin-Unterstützung eingebaut mit (siehe
// Kommentar in der Root-build.gradle.kts).
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Signierschlüssel liegt bewusst außerhalb des Repos, Zugangsdaten in einer
// git-ignorierten keystore.properties (gleiches Muster wie local.properties) --
// nie Passwörter direkt in dieser eingecheckten Datei. Fehlt die Datei (z.B.
// auf einer frischen Maschine ohne Schlüssel), baut assembleRelease weiterhin,
// nur unsigniert statt mit dem echten App-Schlüssel.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(keystorePropertiesFile.inputStream())
}

android {
    namespace = "de.astrapi.sync"
    compileSdk = 37 // von 36 hochgesetzt -- aktuelle Compose-/core-ktx-Versionen
    // verlangen das laut echtem Build-Fehler ("requires ... compile against
    // version 37 or later"), war beim ersten Ansatz noch nicht absehbar.

    defaultConfig {
        applicationId = "de.astrapi.sync"
        minSdk = 26
        targetSdk = 37
        versionCode = 11
        versionName = "0.5.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Kein separates kotlin.compilerOptions.jvmTarget noetig -- mit dem
    // eingebauten Kotlin-Support in AGP 9 leitet sich das automatisch
    // von compileOptions.targetCompatibility ab.

    buildFeatures {
        compose = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.navigation.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.okhttp)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.documentfile)
    implementation(libs.security.crypto)

    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.work.runtime.ktx)
    // com.google.crypto.tink:tink (Desktop-Variante) ausgeschlossen -- kollidiert
    // im Dex-Merge mit tink-android (via androidx.security:security-crypto,
    // SecurePrefs.kt), das dieselben Klassen bereitstellt und für Android
    // ohnehin die richtige Wahl ist.
    implementation(libs.unifiedpush.connector) {
        exclude(group = "com.google.crypto.tink", module = "tink")
    }

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
