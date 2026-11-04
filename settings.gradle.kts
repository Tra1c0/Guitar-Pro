pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "RiffWorkbench"
include(":app", ":core:model", ":core:editor", ":core:playback", ":core:synth", ":core:ports", ":core:capabilities",
    ":data:local", ":platform:android", ":adapter:alphatab", ":adapter:formats",
    ":adapter:audio", ":adapter:integrations", ":ui:components", ":ui:score",
    ":feature:library", ":feature:workspace", ":feature:settings")
