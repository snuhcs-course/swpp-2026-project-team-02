plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Public API base URL for the live flavor. Credentials never belong here.
val liveApiBaseUrl: String = providers.gradleProperty("licensePlanner.apiBaseUrl")
    .getOrElse("http://10.0.2.2:8000/api/v1")
val backendTestApi: String = providers.environmentVariable("LICENSE_PLANNER_TEST_API").getOrElse("")
val releaseStoreFile = providers.gradleProperty("licensePlanner.releaseStoreFile").orNull
val releaseStorePassword = providers.gradleProperty("licensePlanner.releaseStorePassword").orNull
val releaseKeyAlias = providers.gradleProperty("licensePlanner.releaseKeyAlias").orNull
val releaseKeyPassword = providers.gradleProperty("licensePlanner.releaseKeyPassword").orNull
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

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
        buildConfigField("boolean", "INTEGRATION_DEMO", "false")
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
        create("integration") {
            dimension = "mode"
            applicationIdSuffix = ".integration"
            versionNameSuffix = "-integration-demo"
            buildConfigField("boolean", "DEMO_MODE", "false")
            buildConfigField("boolean", "INTEGRATION_DEMO", "true")
            buildConfigField("String", "API_BASE_URL", "\"$liveApiBaseUrl\"")
        }
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }
    buildTypes {
        debug {
            // JVM unit-test JaCoCo reports for CI (create<Flavor>DebugUnitTestCoverageReport).
            enableUnitTestCoverage = true
        }
        release {
            optimization {
                enable = false
            }
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
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
            it.environment("LICENSE_PLANNER_TEST_API", backendTestApi)
            it.inputs.property("backendTestApi", backendTestApi)
            // RequestMapperTest exports the serialized request here for contract_checks.
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
