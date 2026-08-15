pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "CCTSystem"

include(
    "cctsystem-contract",
    "cctsystem-core",
    "cctsystem-storage-mysql",
    "cctsystem-bridge",
    "cctsystem-domain-identity",
    "cctsystem-integration-authme",
    "cctsystem-domain-points",
    "cctsystem-integration-playerpoints",
    "cctsystem-domain-exchange",
    "cctsystem-integration-vault",
    "cctsystem-domain-promotion",
    "cctsystem-domain-membership",
    "cctsystem-integration-luckperms",
    "cctsystem-integration-skinsrestorer",
    "cctsystem-domain-redeem",
    "cctsystem-platform-paper",
    "cctsystem-platform-velocity",
    "cctsystem-distribution",
)
