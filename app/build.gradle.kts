plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
// Zyklus 27 (automatische Updates): CI passes the release keystore and the build number through the environment. Without
// them (local builds, forks, pull requests) the build is unchanged: default debug key, versionCode 1, "0.1.0-dev".
val updateSigning: Map<String, String>? = listOf("RW_KEYSTORE_FILE", "RW_KEYSTORE_PASSWORD", "RW_KEY_ALIAS", "RW_KEY_PASSWORD")
    .associateWith { System.getenv(it).orEmpty() }.takeIf { env -> env.values.all { it.isNotBlank() } && file(env.getValue("RW_KEYSTORE_FILE")).isFile }
val ciVersionCode: Int? = System.getenv("RW_VERSION_CODE")?.trim()?.toIntOrNull()?.takeIf { it > 1 }
android {
    namespace = "dev.riffworkbench.app"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        applicationId = "dev.riffworkbench.app"
        targetSdk = 36
        // CI: RW_VERSION_CODE = run number + 1000, versionName "0.<run number>"; the updater compares versionCode (tag "v<code>").
        versionCode = ciVersionCode ?: 1
        versionName = ciVersionCode?.let { code -> System.getenv("RW_VERSION_NAME")?.trim()?.takeIf { it.isNotEmpty() } ?: "0.${if (code > 1000) code - 1000 else code}" } ?: "0.1.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // BuildConfig.VERSION_CODE/VERSION_NAME tell the updater which build is installed.
    buildFeatures { compose = true; buildConfig = true }
    signingConfigs {
        updateSigning?.let { env ->
            create("updates") {
                storeFile = file(env.getValue("RW_KEYSTORE_FILE"))
                storePassword = env.getValue("RW_KEYSTORE_PASSWORD")
                keyAlias = env.getValue("RW_KEY_ALIAS")
                keyPassword = env.getValue("RW_KEY_PASSWORD")
                storeType = "PKCS12"
            }
        }
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    // The sample bank is read once at start; stored uncompressed it streams without inflating 29 MB.
    androidResources { noCompress += listOf("rwsb", "rwdk") }
    buildTypes {
        // The debug build is the one the user installs and tests (the minified release build was never tested on a device):
        // with the CI keystore it is signed with the stable update key, so each new build installs over the previous one.
        debug { if (updateSigning != null) signingConfig = signingConfigs.getByName("updates") }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":ui:components"))
    implementation(project(":ui:score"))
    implementation(project(":feature:library"))
    implementation(project(":feature:workspace"))
    implementation(project(":feature:settings"))
    implementation(project(":data:local"))
    implementation(project(":platform:android"))
    implementation(project(":adapter:alphatab"))
    implementation(project(":adapter:formats"))
    implementation(project(":adapter:audio"))
    implementation(project(":adapter:integrations"))
    implementation(libs.coroutines.core)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.preview)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.runtime)
    debugImplementation(libs.compose.tooling)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.test)
    debugImplementation(libs.compose.test.manifest)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
}

tasks.withType<Test>().configureEach { testLogging { exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL } }
