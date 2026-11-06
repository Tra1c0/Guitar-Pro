plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":core:ports"))
    implementation(project(":core:editor"))
    implementation(project(":core:playback"))
    implementation(libs.coroutines.core)
    testImplementation(project(":data:local"))
    // QA cycle 21: MIDI export timing against the rendered audio (OfflineMixdown) in one test.
    testImplementation(project(":core:synth"))
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.serialization.json)
}
