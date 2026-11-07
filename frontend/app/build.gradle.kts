import com.android.build.api.variant.BuildConfigField
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Public API base URL for the live flavor. Credentials never belong here.
val liveApiBaseUrlProperty = "licensePlanner.apiBaseUrl"
val liveApiBaseUrl: String = providers.gradleProperty(liveApiBaseUrlProperty)
    .getOrElse("http://10.0.2.2:8000/api/v1")
// Release signing uses all four properties or none; none builds an unsigned release.
val releaseSigningProperties = listOf(
    "licensePlanner.releaseStoreFile",
    "licensePlanner.releaseStorePassword",
    "licensePlanner.releaseKeyAlias",
    "licensePlanner.releaseKeyPassword",
).associateWith { providers.gradleProperty(it).orNull }
val hasReleaseSigning = releaseSigningProperties.values.any { it != null }
val invalidReleaseSigningProperties = releaseSigningProperties.filterValues { it.isNullOrBlank() }.keys
if (hasReleaseSigning && invalidReleaseSigningProperties.isNotEmpty()) {
    throw GradleException(
        "Release signing needs all four licensePlanner.release* properties, or none for an unsigned " +
            "build. Missing or blank: ${invalidReleaseSigningProperties.joinToString()}",
    )
}
val releaseStoreFile = releaseSigningProperties.getValue("licensePlanner.releaseStoreFile")
val releaseStorePassword = releaseSigningProperties.getValue("licensePlanner.releaseStorePassword")
val releaseKeyAlias = releaseSigningProperties.getValue("licensePlanner.releaseKeyAlias")
val releaseKeyPassword = releaseSigningProperties.getValue("licensePlanner.releaseKeyPassword")

/**
 * Fails a live release whose API URL the packaged release network policy would block: it must be
 * an absolute HTTP(S) URL with a host, and HTTP is allowed only for the policy's cleartext domains.
 * HTTPS hosts are not contacted. Writes the accepted URL for BuildConfig.
 */
abstract class ValidateLiveReleaseApiBaseUrl : DefaultTask() {
    @get:Input
    abstract val propertyName: Property<String>

    @get:Input
    abstract val apiBaseUrl: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val releaseNetworkPolicy: RegularFileProperty

    @get:OutputFile
    abstract val validatedApiBaseUrl: RegularFileProperty

    @TaskAction
    fun validate() {
        val name = propertyName.get()
        val url = apiBaseUrl.get()
        val uri = try {
            URI(url)
        } catch (error: URISyntaxException) {
            null
        }
        val scheme = uri?.scheme?.lowercase(Locale.ROOT)
        val host = uri?.host?.lowercase(Locale.ROOT)
        if (url.isBlank() || uri == null || !uri.isAbsolute || scheme !in setOf("http", "https") ||
            host.isNullOrBlank()
        ) {
            throw GradleException(
                "$name must be an absolute http:// or https:// API URL with a host for a live " +
                    "release; got '$url'.",
            )
        }
        if (scheme == "http") {
            val cleartextDomains = cleartextDomains()
            val permitted = cleartextDomains.any { (domain, includeSubdomains) ->
                host == domain || (includeSubdomains && host.endsWith(".$domain"))
            }
            if (!permitted) {
                val allowed = cleartextDomains.joinToString { it.first }
                throw GradleException(
                    "$name uses HTTP host '$host', which the release network policy blocks. Use " +
                        "an allowed HTTP host ($allowed), for example " +
                        "http://${cleartextDomains.firstOrNull()?.first}/api/v1, or an https:// endpoint.",
                )
            }
        }
        validatedApiBaseUrl.get().asFile.writeText(url)
    }

    /** Domains, with their includeSubdomains flag, from domain-configs that permit cleartext. */
    private fun cleartextDomains(): List<Pair<String, Boolean>> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(releaseNetworkPolicy.get().asFile)
        val configs = document.getElementsByTagName("domain-config")
        return (0 until configs.length).map { configs.item(it) as Element }
            .filter { it.getAttribute("cleartextTrafficPermitted") == "true" }
            .flatMap { config ->
                val children = config.childNodes
                (0 until children.length).map { children.item(it) }
                    .filterIsInstance<Element>()
                    .filter { it.tagName == "domain" }
                    .map {
                        it.textContent.trim().lowercase(Locale.ROOT) to
                            (it.getAttribute("includeSubdomains") == "true")
                    }
            }
    }
}

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
            // RequestMapperTest exports the serialized request here for contract_checks.
            it.systemProperty(
                "licensePlanner.contractCheckDir",
                layout.buildDirectory.dir("contract-check").get().asFile.absolutePath,
            )
        }
    }
}

// The live release takes its BuildConfig URL from the validation task, so every task that
// packages it (assemble, bundle) first rejects a URL the release network policy would block.
androidComponents {
    onVariants(selector().withBuildType("release").withFlavor("mode" to "live")) { variant ->
        val validation = tasks.register<ValidateLiveReleaseApiBaseUrl>("validateLiveReleaseApiBaseUrl") {
            propertyName.set(liveApiBaseUrlProperty)
            apiBaseUrl.set(liveApiBaseUrl)
            releaseNetworkPolicy.set(
                layout.projectDirectory.file("src/release/res/xml/network_security_config.xml"),
            )
            validatedApiBaseUrl.set(
                layout.buildDirectory.file("intermediates/validated-api-base-url/${variant.name}.txt"),
            )
        }
        variant.buildConfigFields?.put(
            "API_BASE_URL",
            validation.flatMap { it.validatedApiBaseUrl }.map { file ->
                BuildConfigField("String", "\"${file.asFile.readText()}\"", null)
            },
        )
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
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
