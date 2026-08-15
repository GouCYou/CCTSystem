plugins {
    `java-library`
}

dependencies {
    api(project(":cctsystem-core"))
    implementation(project(":cctsystem-storage-mysql"))
    implementation(project(":cctsystem-domain-identity"))
}
