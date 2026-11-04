plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":core:model"))
    api(project(":core:playback"))
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
