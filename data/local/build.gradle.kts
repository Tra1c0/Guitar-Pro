plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

val fixtureTools by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[fixtureTools.implementationConfigurationName].extendsFrom(configurations.implementation.get())
tasks.register<JavaExec>("generateFixtures") {
    group = "verification"
    description = "Generate own native and PCM fixtures; never claims GP compatibility."
    dependsOn(fixtureTools.classesTaskName)
    classpath = fixtureTools.runtimeClasspath
    mainClass.set("dev.riffworkbench.fixtures.FixtureToolKt")
    args(rootProject.layout.projectDirectory.dir("fixtures").asFile.absolutePath)
}
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":core:model"))
    api(project(":core:ports"))
    implementation(project(":core:editor"))
    implementation(libs.serialization.json)
    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.serialization.json)
}
