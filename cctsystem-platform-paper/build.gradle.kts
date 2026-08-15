plugins {
    `java-library`
}

dependencies {
    implementation(project(":cctsystem-core"))
    implementation(project(":cctsystem-storage-mysql"))
    implementation(project(":cctsystem-bridge"))
    implementation(project(":cctsystem-domain-identity"))
    implementation(project(":cctsystem-integration-authme"))
    implementation(project(":cctsystem-domain-points"))
    implementation(project(":cctsystem-integration-playerpoints"))
    implementation(project(":cctsystem-domain-exchange"))
    implementation(project(":cctsystem-integration-vault"))
    implementation(project(":cctsystem-domain-promotion"))
    implementation(project(":cctsystem-domain-membership"))
    implementation(project(":cctsystem-integration-luckperms"))
    implementation(project(":cctsystem-domain-redeem"))
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("fr.xephi:authme-core:6.0.0-SNAPSHOT")
    compileOnly("org.black_ixx:playerpoints:3.3.5")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1")
    compileOnly("net.luckperms:api:5.5")
}
