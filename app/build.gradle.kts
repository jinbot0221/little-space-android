import groovy.json.JsonSlurper

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
// Read the Android registration without requiring the Google Services Gradle plugin.
val firebaseJson = file("google-services.json").takeIf { it.isFile }?.let {
    JsonSlurper().parse(it) as Map<*, *>
}
val firebaseClient = (firebaseJson?.get("client") as? List<*>)
    ?.map { it as Map<*, *> }
    ?.firstOrNull {
        val info = it["client_info"] as? Map<*, *>
        val androidInfo = info?.get("android_client_info") as? Map<*, *>
        androidInfo?.get("package_name") == "com.portfolio.littlespace"
    }
require(firebaseJson == null || firebaseClient != null) {
    "google-services.json must contain Android package com.portfolio.littlespace"
}
val projectInfo = firebaseJson?.get("project_info") as? Map<*, *>
val clientInfo = firebaseClient?.get("client_info") as? Map<*, *>
val apiKeys = firebaseClient?.get("api_key") as? List<*>
fun firebaseValue(property: String, fromJson: Any?, fallback: String): String =
    providers.gradleProperty(property).orNull ?: fromJson?.toString() ?: fallback
val firebaseProject = firebaseValue("firebaseProjectId", projectInfo?.get("project_id"), "demo-teumgot")
val firebaseApp = firebaseValue("firebaseAppId", clientInfo?.get("mobilesdk_app_id"), "1:1234567890:android:0000000000000000")
val firebaseKey = firebaseValue("firebaseApiKey", (apiKeys?.firstOrNull() as? Map<*, *>)?.get("current_key"), "demo-api-key")
val useFirebaseEmulators = providers.gradleProperty("useEmulators").orNull?.let {
    require(it == "true" || it == "false") { "useEmulators must be true or false" }
    it.toBoolean()
} ?: firebaseProject.startsWith("demo-")
require(useFirebaseEmulators || (firebaseProject.isNotBlank() && firebaseApp.isNotBlank() && firebaseKey.isNotBlank() && !firebaseProject.startsWith("demo-") && firebaseApp != "1:1234567890:android:0000000000000000" && firebaseKey != "demo-api-key")) {
    "Live Firebase requires google-services.json or firebaseProjectId, firebaseAppId and firebaseApiKey"
}
fun quotedConfig(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.portfolio.littlespace"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.portfolio.littlespace"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.2.0-spark"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "FIREBASE_PROJECT_ID", quotedConfig(firebaseProject))
        buildConfigField("String", "FIREBASE_APP_ID", quotedConfig(firebaseApp))
        buildConfigField("String", "FIREBASE_API_KEY", quotedConfig(firebaseKey))
        buildConfigField("boolean", "USE_EMULATORS", useFirebaseEmulators.toString())
        buildConfigField("String", "EMULATOR_HOST", "\"${providers.gradleProperty("emulatorHost").getOrElse("10.0.2.2")}\"")
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation(platform("com.google.firebase:firebase-bom:33.13.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
