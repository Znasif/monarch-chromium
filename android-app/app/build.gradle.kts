import java.util.Properties

plugins { id("com.android.application") }

// Local builds only: the Gemini key comes from the repo's git-ignored .env.
val env = Properties().apply {
    rootProject.file("../.env").takeIf { it.exists() }?.reader()?.use { load(it) }
}

android {
    namespace = "org.monarch.browser"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    defaultConfig {
        applicationId = "org.monarch.browser"
        minSdk = 28
        targetSdk = 33
        versionCode = 1
        versionName = "0.1"
        buildConfigField("String", "GEMINI_API_KEY", "\"${env.getProperty("GEMINI_API_KEY", "").trim('"')}\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // tactile-core is plain Kotlin; compile its sources into the app.
    sourceSets["main"].kotlin.srcDir("../../tactile-core/src/main/kotlin")
}

dependencies {
    implementation(files("libs/KeySoftSDK-0.1.136.aar"))
    // KeySoftSDK's own dependencies (from its POM)
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.multidex:multidex:2.0.1")
    implementation("commons-io:commons-io:2.11.0")
    implementation("com.google.code.gson:gson:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.1")
    implementation("androidx.databinding:viewbinding:8.13.0")
}
