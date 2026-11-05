plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "dev.riffworkbench.app"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        applicationId = "dev.riffworkbench.app"
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    // The sample bank is read once at start; stored uncompressed it streams without inflating 29 MB.
    androidResources { noCompress += "rwsb" }
    buildTypes {
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
