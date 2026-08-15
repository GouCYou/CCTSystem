plugins {
    `java-library`
}

dependencies {
    api(project(":cctsystem-contract"))
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.22.1")
}
