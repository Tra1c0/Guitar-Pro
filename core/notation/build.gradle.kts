plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":core:model"))
    // Tests only: random valid scores are generated through the editor core (QA cycle 15).
    testImplementation(project(":core:editor"))
    testImplementation(libs.coroutines.core)
    testImplementation(libs.junit)
}
dependencies { testImplementation(libs.serialization.json) }
