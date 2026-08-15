plugins {
    `java-library`
}

dependencies {
    api(project(":cctsystem-core"))
    implementation(project(":cctsystem-bridge"))
    // 15.11.0 is the last API artifact targeting Java 21. Runtime 15.x remains binary-compatible.
    compileOnly("net.skinsrestorer:skinsrestorer-api:15.11.0")
    testImplementation("net.skinsrestorer:skinsrestorer-api:15.11.0")
}
