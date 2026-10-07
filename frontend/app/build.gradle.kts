plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Public API base URL for the live flavor (design D2). Credentials never belong here.
val liveApiBaseUrl: String = providers.gradleProperty("licensePlanner.apiBaseUrl")
    .getOrElse("http://10.0.2.2:8000/api/v1")

android {
    namespace = "com.swpp.licenseplanner"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.swpp.licenseplanner"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "mode"
    productFlavors {
        // Listed first so Android Studio selects demoDebug by default.
        create("demo") {
            dimension = "mode"
            isDefault = true
            applicationIdSuffix = ".demo"
            versionNameSuffix = "-demo"
            buildConfigField("boolean", "DEMO_MODE", "true")
            buildConfigField("String", "API_BASE_URL", "\"\"")
        }
        create("live") {
            dimension = "mode"
            buildConfigField("boolean", "DEMO_MODE", "false")
            buildConfigField("String", "API_BASE_URL", "\"$liveApiBaseUrl\"")
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.all {
            // RequestMapperTest exports the serialized request here for contract_checks (task 4.3).
            it.systemProperty(
                "licensePlanner.contractCheckDir",
                layout.buildDirectory.dir("contract-check").get().asFile.absolutePath,
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
