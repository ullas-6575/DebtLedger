import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localConfig = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun configValue(name: String): String = providers.environmentVariable(name).orNull
    ?: localConfig.getProperty(name, "")
fun stringLiteral(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

dependencyLocking { lockAllConfigurations() }

android {
    namespace = "com.ledgerx"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.ledgerx"; minSdk = 26; targetSdk = 34; versionCode = 3; versionName = "0.3.0"
        buildConfigField("String", "AMOY_REGISTRY", stringLiteral(configValue("AMOY_REGISTRY")))
        buildConfigField("String", "AMOY_RPC_URL", stringLiteral(configValue("AMOY_RPC_URL")))
        buildConfigField("String", "SUPABASE_URL", stringLiteral(configValue("SUPABASE_URL")))
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", stringLiteral(configValue("SUPABASE_PUBLISHABLE_KEY")))
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    // Supabase
    implementation(platform("io.github.jan-tennert.supabase:bom:2.6.1"))
    implementation("io.github.jan-tennert.supabase:gotrue-kt")
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.github.jan-tennert.supabase:realtime-kt")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.77")
    implementation("io.ktor:ktor-client-okhttp:2.3.12")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    testImplementation("io.ktor:ktor-client-mock:2.3.12")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // Encrypted session storage (Android Keystore-backed)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
