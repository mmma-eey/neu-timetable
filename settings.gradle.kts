pluginManagement {
    repositories {
        mavenCentral()
        google { content { includeGroupByRegex("com\\.android.*"); includeGroupByRegex("androidx\\..*"); includeGroupByRegex("com\\.google\\.testing.*"); includeGroupByRegex("com\\.google\\.test.*") } }
        gradlePluginPortal()
    }
    resolutionStrategy { eachPlugin { if (requested.id.id == "com.android.application") useModule("com.android.tools.build:gradle:${requested.version}") } }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google { content { includeGroupByRegex("com\\.android.*"); includeGroupByRegex("androidx\\..*"); includeGroupByRegex("com\\.google\\.testing.*"); includeGroupByRegex("com\\.google\\.test.*") } }
    }
}
rootProject.name = "Kejian"
include(":app")
