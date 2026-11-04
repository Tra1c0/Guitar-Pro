plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":core:model"))
    testImplementation(libs.junit)
}
dependencies { testImplementation(libs.serialization.json) }
