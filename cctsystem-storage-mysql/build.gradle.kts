plugins {
    `java-library`
}

dependencies {
    api(project(":cctsystem-core"))
    implementation("com.zaxxer:HikariCP:7.1.0") {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
    runtimeOnly("com.mysql:mysql-connector-j:26.7.0")
    compileOnly("org.slf4j:slf4j-api:2.0.17")
}
