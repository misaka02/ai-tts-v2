import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun buildSecretField(envKey: String, propKey: String): String {
    val raw = System.getenv(envKey)?.trim()?.takeIf { it.isNotEmpty() }
        ?: localProps.getProperty(propKey)?.trim().orEmpty()
    val escaped = raw.replace("\\", "\\\\").replace("\"", "\\\"")
    return "\"$escaped\""
}

android {
    namespace = "com.aitts.v2"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.aitts.engine"
        minSdk = 26
        targetSdk = 34
        versionCode = 201
        versionName = "2.8.0-pre.1"

        buildConfigField("String", "GDRIVE_DEFAULT_CLIENT_ID", buildSecretField("AITTS_GDRIVE_CLIENT_ID", "aitts.gdrive.clientId"))

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    flavorDimensions += "engine"
    productFlavors {
        create("cloud") {
            dimension = "engine"
            buildConfigField("boolean", "OFFLINE_ENGINE_INCLUDED", "false")
        }
        create("full") {
            dimension = "engine"
            buildConfigField("boolean", "OFFLINE_ENGINE_INCLUDED", "true")
            ndk {
                abiFilters += "arm64-v8a"
            }
        }
    }

    signingConfigs {
        val keystorePath = System.getenv("AITTS_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
            ?: localProps.getProperty("aitts.keystore.file")?.trim()?.takeIf { it.isNotEmpty() }
        val storePass = System.getenv("AITTS_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
            ?: localProps.getProperty("aitts.keystore.password")?.trim()?.takeIf { it.isNotEmpty() }
        val alias = System.getenv("AITTS_KEY_ALIAS")?.takeIf { it.isNotBlank() }
            ?: localProps.getProperty("aitts.key.alias")?.trim()?.takeIf { it.isNotEmpty() }
        val keyPass = System.getenv("AITTS_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
            ?: localProps.getProperty("aitts.key.password")?.trim()?.takeIf { it.isNotEmpty() }
        if (keystorePath != null && storePass != null && alias != null && keyPass != null && file(keystorePath).exists()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = storePass
                keyAlias = alias
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(project(":core-model"))
    implementation(project(":core-engine"))
    implementation(project(":core-reader"))

    implementation(libs.readium.shared)
    implementation(libs.readium.streamer)
    implementation(libs.readium.navigator)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.play.services.auth)
    implementation(libs.androidx.browser)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    "fullImplementation"(files("libs/sherpa-onnx-classes.jar"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
