plugins {
    `java-library`
}

dependencies {
    api(project(":cctsystem-domain-exchange"))
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1")
}
