plugins {
    `java-library`
}

dependencies {
    api(project(":cctsystem-core"))
    implementation(project(":cctsystem-bridge"))
    implementation(project(":cctsystem-storage-mysql"))
    implementation(project(":cctsystem-domain-identity"))
    implementation(project(":cctsystem-domain-points"))
}
