plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.legacy.kapt)
}

fun buildConfigString(value: String): String = "\"" + value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n") + "\""

val offlinePackCatalogUrl = providers.gradleProperty("resqnet.packCatalogUrl").orNull ?: ""
val offlinePackPublicKeyPem = providers.gradleProperty("resqnet.packPublicKeyPem").orNull ?: ""
val debugPackCatalogUrl = providers.gradleProperty("resqnet.debugPackCatalogUrl").orNull ?: offlinePackCatalogUrl
val debugPackPublicKeyPem = providers.gradleProperty("resqnet.debugPackPublicKeyPem").orNull ?: offlinePackPublicKeyPem

android {
    namespace = "com.resqnet.app"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.resqnet.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "OFFLINE_PACK_CATALOG_URL", buildConfigString(offlinePackCatalogUrl))
        buildConfigField("String", "OFFLINE_PACK_PUBLIC_KEY_PEM", buildConfigString(offlinePackPublicKeyPem))
    }

    buildTypes {
        debug {
            // A local HTTPS fixture can be supplied without changing source:
            // -Presqnet.debugPackCatalogUrl and -Presqnet.debugPackPublicKeyPem.
            buildConfigField("String", "OFFLINE_PACK_CATALOG_URL", buildConfigString(debugPackCatalogUrl))
            buildConfigField("String", "OFFLINE_PACK_PUBLIC_KEY_PEM", buildConfigString(debugPackPublicKeyPem))
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/ASL2.0",
                "META-INF/*.kotlin_module"
            )
        }
        jniLibs {
            pickFirsts += setOf("**/libc++_shared.so")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)

    // Candidates for Offline Routing Spike
    implementation(libs.graphhopper.core) {
        exclude(group = "org.slf4j", module = "slf4j-log4j12")
        exclude(group = "log4j", module = "log4j")
    }
    implementation(libs.valhalla.mobile)
    implementation("org.maplibre.gl:android-sdk:11.8.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")
    implementation("io.github.rallista:valhalla-models:0.5.0")
    implementation("io.github.rallista:valhalla-models-config:0.5.0")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
