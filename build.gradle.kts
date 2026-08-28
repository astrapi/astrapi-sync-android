// Root-Build-Datei -- Plugins nur deklarieren (apply false), tatsächlich
// angewendet werden sie im :app-Modul.
//
// Kein separates "kotlin-android"-Plugin mehr: seit AGP 9 bringt das
// Android-Plugin die Kotlin-Unterstützung eingebaut mit, ein zusätzlich
// angewendetes org.jetbrains.kotlin.android quittiert AGP 9 mit einem
// harten Build-Fehler ("no longer required ... since AGP 9.0"), erst
// beim echten Build-Versuch entdeckt.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
