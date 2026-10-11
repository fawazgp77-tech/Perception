pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal(); maven("https://maven.mappls.com/repository/mappls/") } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral(); maven("https://maven.mappls.com/repository/mappls/") }
}
rootProject.name = "MobilityAssist"
include(":app")
