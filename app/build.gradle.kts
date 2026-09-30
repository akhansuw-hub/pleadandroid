// Plead for Android: the one application module (docs/android-port/PORT.md).
// Shared file: add to it, never rename or remove what another wave uses.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Firebase is optional at build time: without google-services.json the app builds and runs, and FCM
// token registration is simply skipped (PORT.md §3), as on iOS when APNs is unavailable.
if (file("google-services.json").exists()) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
}

// local.properties holds the same keys as ArgueWin/Resources/Config.xcconfig (gitignored).
val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun config(key: String, placeholder: String): String =
    (localProperties.getProperty(key) ?: System.getenv(key))?.trim()?.takeIf { it.isNotEmpty() } ?: placeholder
fun String.quoted() = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val supabaseUrl = config("SUPABASE_URL", "https://YOUR-PROJECT-REF.supabase.co")
val supabaseConfigured = !supabaseUrl.contains("YOUR-PROJECT-REF") && !supabaseUrl.contains("placeholder.supabase.co")

// Version name / code track MARKETING_VERSION / CURRENT_PROJECT_VERSION in the iOS project.yml.
val projectYml = rootProject.file("../project.yml").takeIf { it.exists() }?.readText().orEmpty()
fun projectSetting(key: String, fallback: String): String =
    Regex("""$key:\s*"?([^"\s]+)"?""").find(projectYml)?.groupValues?.get(1) ?: fallback

android {
    namespace = "app.plead.android"
    // compileSdk 36, not the brief's 35: supabase-kt 3.2 (androidx.browser 1.9) and Compose 1.9 refuse to be
    // compiled against 35. Build-time only; runtime behaviour follows targetSdk, which stays 35 (PORT.md §2).
    compileSdk = 36

    defaultConfig {
        applicationId = "app.plead.android"
        minSdk = 26
        targetSdk = 35
        versionCode = projectSetting("CURRENT_PROJECT_VERSION", "6").toInt()
        versionName = projectSetting("MARKETING_VERSION", "1.0.0")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "SUPABASE_URL", supabaseUrl.quoted())
        buildConfigField("String", "SUPABASE_ANON_KEY", config("SUPABASE_ANON_KEY", "YOUR_SUPABASE_ANON_KEY").quoted())
        buildConfigField("String", "REVENUECAT_API_KEY", config("REVENUECAT_API_KEY", "goog_YOUR_REVENUECAT_PUBLIC_KEY").quoted())
        buildConfigField("String", "APPSFLYER_DEV_KEY", config("APPSFLYER_DEV_KEY", "YOUR_APPSFLYER_DEV_KEY").quoted())
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", config("GOOGLE_WEB_CLIENT_ID", "YOUR_GOOGLE_OAUTH_WEB_CLIENT_ID").quoted())
        // Release builds never read the demo harness (iOS: `#if DEBUG`).
        buildConfigField("boolean", "DEMO_HARNESS", "false")
        buildConfigField("boolean", "DEMO_BY_DEFAULT", "false")
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "DEMO_HARNESS", "true")
            // Commit 83141d4: with no Supabase project configured a debug build can't sign in at all, so demo
            // mode becomes the default; a real config or an explicit `AWDemo NO` extra still wins.
            buildConfigField("boolean", "DEMO_BY_DEFAULT", (!supabaseConfigured).toString())
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/INDEX.LIST", "/META-INF/io.netty.versions.properties")
    }

    lint {
        // Pixel art and copy are ported verbatim from iOS (PORT.md §5): inline strings, no translation pass.
        disable += setOf("MissingTranslation", "SetTextI18n")
        abortOnError = true
        checkDependencies = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.realtime)
    implementation(libs.supabase.storage)
    implementation(libs.supabase.functions)
    implementation(libs.ktor.client.okhttp)

    implementation(libs.revenuecat.purchases)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.appsflyer)
    implementation(libs.play.review.ktx)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.test.core)
    // Wave 2a: canned Supabase HTTP responses for the service tests (iOS StubURLProtocol).
    testImplementation(libs.ktor.client.mock)
    // Wave 2b: Robolectric renders design-system composables to PNG for review (ComponentGallerySnapshotTests).
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
