import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    `java-library`
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":cctsystem-contract"))
    implementation(project(":cctsystem-core"))
    implementation(project(":cctsystem-storage-mysql"))
    implementation(project(":cctsystem-bridge"))
    implementation(project(":cctsystem-domain-identity"))
    implementation(project(":cctsystem-integration-authme"))
    implementation(project(":cctsystem-domain-points"))
    implementation(project(":cctsystem-integration-playerpoints"))
    implementation(project(":cctsystem-domain-exchange"))
    implementation(project(":cctsystem-integration-vault"))
    implementation(project(":cctsystem-domain-promotion"))
    implementation(project(":cctsystem-domain-membership"))
    implementation(project(":cctsystem-integration-luckperms"))
    implementation(project(":cctsystem-integration-skinsrestorer"))
    implementation(project(":cctsystem-domain-redeem"))
    implementation(project(":cctsystem-platform-paper"))
    implementation(project(":cctsystem-platform-velocity"))
}

tasks.jar {
    enabled = false
}

tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName.set("CCTSystem")
    archiveClassifier.set("")
    archiveVersion.set(project.version.toString())

    mergeServiceFiles()
    duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.INCLUDE
    relocate("com.fasterxml.jackson", "cn.cctstudio.cctsystem.libs.jackson")
    relocate("org.yaml.snakeyaml", "cn.cctstudio.cctsystem.libs.snakeyaml")
    relocate("com.zaxxer.hikari", "cn.cctstudio.cctsystem.libs.hikari")

    exclude(
        "META-INF/*.SF",
        "META-INF/*.DSA",
        "META-INF/*.RSA",
        "META-INF/LICENSE*",
        "META-INF/NOTICE*",
    )
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
