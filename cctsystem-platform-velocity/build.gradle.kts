plugins {
    `java-library`
}

dependencies {
    implementation(project(":cctsystem-core"))
    implementation(project(":cctsystem-storage-mysql"))
    implementation(project(":cctsystem-bridge"))
    implementation(project(":cctsystem-domain-identity"))
    implementation(project(":cctsystem-integration-skinsrestorer"))
    compileOnly("com.velocitypowered:velocity-api:3.5.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:3.5.0-SNAPSHOT")
    compileOnly("net.luckperms:api:5.5")
}
