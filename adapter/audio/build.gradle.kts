plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":core:ports"))
    api(project(":core:synth"))
    implementation(project(":core:playback"))
    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
    // Zyklus 28: the default project (ProjectFactory) and the tap time map end to end.
    testImplementation(project(":core:editor"))
    testImplementation(libs.coroutines.test)
    testImplementation(libs.serialization.json)
}
// Audio-thread allocation tests run in their own JVM without escape analysis (QA cycle 24). ART on Android does not
// scalar-replace objects, so this is the stricter and more faithful check; with escape analysis on, HotSpot
// re-materialises eliminated objects when it deoptimises compiled code and charges them to the thread, which made the
// check fail once in a while (896 B at a loop wrap) although the render path allocates nothing.
val allocTest = tasks.register<Test>("allocTest") {
    description = "Audio-thread allocation tests without escape analysis."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    include("**/*Alloc*Test*")
    jvmArgs("-XX:-DoEscapeAnalysis")
}
tasks.test { exclude("**/*Alloc*Test*"); dependsOn(allocTest) }
