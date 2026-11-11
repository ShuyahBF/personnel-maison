// Projet Android « Personnel de maison » : à ouvrir directement dans Android Studio (dossier racine).
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "PersonnelMaison"
include(":app")
