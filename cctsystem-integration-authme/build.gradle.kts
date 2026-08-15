plugins {
    `java-library`
}

dependencies {
    implementation(project(":cctsystem-core"))
    implementation(project(":cctsystem-bridge"))
    implementation(project(":cctsystem-domain-identity"))
    compileOnly("fr.xephi:authme-core:6.0.0-SNAPSHOT")
}
