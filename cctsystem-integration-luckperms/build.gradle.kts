plugins {
    `java-library`
}

dependencies {
    api(project(":cctsystem-domain-membership"))
    compileOnly("net.luckperms:api:5.5")
}
