import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val defaultWebAppUrl = "https://scooljavanrood.ir/app/"
val webAppUrl = providers.gradleProperty("WEB_APP_URL")
    .orElse(providers.environmentVariable("WEB_APP_URL"))
    .orElse(defaultWebAppUrl)
    .get()
    .trim()

val parsedUrl = try { URI(webAppUrl) } catch (e: Exception) {
    throw GradleException("WEB_APP_URL is not a valid URL: $webAppUrl")
}
if (parsedUrl.scheme != "https" || parsedUrl.host.isNullOrBlank()) {
    throw GradleException("For security, WEB_APP_URL must be an HTTPS URL, e.g. https://school.example.com/app/")
}
val escapedWebAppUrl = webAppUrl.replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "ir.madreseyar.teacher"
    compileSdk = 35

    defaultConfig {
        applicationId = "ir.madreseyar.teacher"
        minSdk = 23
        targetSdk = 35
        versionCode = 3
        versionName = "1.0.2"
        buildConfigField("String", "WEB_APP_URL", "\"$escapedWebAppUrl\"")
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.webkit:webkit:1.12.1")
}
