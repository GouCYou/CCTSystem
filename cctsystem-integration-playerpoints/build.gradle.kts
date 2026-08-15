plugins {
    `java-library`
}

dependencies {
    api(project(":cctsystem-domain-points"))
    compileOnly("org.black_ixx:playerpoints:3.3.5")
}
