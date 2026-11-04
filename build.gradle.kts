plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
tasks.register<Exec>("verifyArchitecture") {
    group = "verification"
    commandLine("python3", "tools/verify_architecture.py")
}
tasks.register("check") {
    group = "verification"
    dependsOn("verifyArchitecture")
    val androidModules = setOf(":app", ":platform:android", ":ui:components", ":ui:score", ":feature:library", ":feature:workspace", ":feature:settings")
    dependsOn(subprojects.filter { it.buildFile.isFile }.map { it.path + if (it.path in androidModules) ":testDebugUnitTest" else ":check" })
    dependsOn(":app:lintDebug")
}
