plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":core:model"))
    api(project(":core:capabilities"))
    implementation(libs.coroutines.core)
    // Zyklus 27: parsing of the GitHub release answer (UpdateChecker); portable, no platform import.
    implementation(libs.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.serialization.json)
}
