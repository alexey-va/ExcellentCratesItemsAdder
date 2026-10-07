plugins {
    `java-library`
    `maven-publish`
}

group = "ru.ruscrafting.arc"
version = "0.1.0"
base.archivesName.set("arc-crate-api")

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnlyApi("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

publishing {
    repositories {
        maven {
            name = "RusCrafting"
            url = uri("https://repo.rus-crafting.ru/grocermc/")
            credentials {
                username = providers.environmentVariable("REPOSILITE_PUBLISH_USERNAME").orElse("arc-publisher").orNull
                password = providers.environmentVariable("REPOSILITE_PUBLISH_PASSWORD").orNull
            }
        }
    }
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "arc-crate-api"
            pom {
                name.set("ARC Crate Location API")
                description.set("Optional Bukkit service contract for managed crate location checks.")
            }
        }
    }
}
