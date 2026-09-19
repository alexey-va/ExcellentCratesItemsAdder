import java.security.MessageDigest

plugins {
    java
    kotlin("jvm") version "2.3.0"
    id("com.gradleup.shadow") version "9.3.0"
}

group = "ru.ruscrafting"
version = "0.14.13"

repositories {
    mavenCentral()
    maven("https://repo.rus-crafting.ru/grocermc/") {
        content { includeGroup("ru.ruscrafting.arc") }
    }
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.nightexpressdev.com/releases/")
    // ExcellentCrates publishes its 6.6.1 binary through its official
    // Modrinth release, while the source POM's NightExpress coordinate is
    // not present in the public NightExpress Maven repository. Keep the
    // original native coordinate and map it to the immutable release asset.
    ivy {
        name = "ExcellentCratesOfficialRelease"
        url = uri("https://cdn.modrinth.com/data/TdefKtjL/versions/iZVIXY6R")
        patternLayout {
            artifact("ExcellentCrates-6.6.1.jar")
        }
        metadataSources { artifact() }
        content { includeVersion("su.nightexpress.excellentcrates", "ExcellentCrates", "6.6.1") }
    }
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("ru.ruscrafting.arc:arc-core:2.7.9")
    implementation("ru.ruscrafting.arc:arc-core-logging:2.7.9")
    implementation("ru.ruscrafting.arc:arc-core-paper:2.7.9")
    implementation("ru.ruscrafting.arc:arc-core-menu:2.7.9")
    implementation("ru.ruscrafting.arc:arc-core-paper-menu:2.7.9")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.10")

    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("ru.ruscrafting.arc:arc-core-paper-api:2.7.9")
    compileOnly("su.nightexpress.excellentcrates:ExcellentCrates:6.6.1")
    compileOnly("su.nightexpress.nightcore:main:2.16.4")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation("io.kotest:kotest-runner-junit5:6.0.7")
    testImplementation("io.kotest:kotest-assertions-core:6.0.7")
    testImplementation("io.mockk:mockk:1.14.7")
    testImplementation("ru.ruscrafting.arc:arc-core-paper-testing:2.7.9")
    testImplementation("ru.ruscrafting.arc:arc-core-paper-api:2.7.9")
    testImplementation("su.nightexpress.excellentcrates:ExcellentCrates:6.6.1")
    testImplementation("su.nightexpress.nightcore:main:2.16.4")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}
kotlin { jvmToolchain(25) }

val verifyExcellentCratesArtifact by tasks.registering {
    val nativeJar = configurations.compileClasspath.map { files ->
        files.single { it.name == "ExcellentCrates-6.6.1.jar" }
    }
    inputs.file(nativeJar)
    doLast {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(nativeJar.get().readBytes()).joinToString("") { "%02x".format(it) }
        check(digest == "7ce3377f9f214c629fb4bf15fb2d5a617a4c4fa6d4988c209324a9867e2965fb") {
            "ExcellentCrates 6.6.1 artifact differs from the verified release"
        }
    }
}
tasks.named("compileKotlin") { dependsOn(verifyExcellentCratesArtifact) }
tasks.named("compileJava") { dependsOn(verifyExcellentCratesArtifact) }

tasks {
    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(25)
    }
    withType<Test>().configureEach {
        jvmArgs("-Djdk.attach.allowAttachSelf=true", "-XX:+EnableDynamicAgentLoading")
    }
    test {
        useJUnitPlatform()
    }
    processResources {
        inputs.property("pluginVersion", project.version)
        filesMatching("plugin.yml") {
            expand("version" to project.version)
        }
    }
    jar {
        archiveClassifier.set("plain")
        archiveBaseName.set("ArcExcellentCrates")
    }
    shadowJar {
        archiveBaseName.set("ArcExcellentCrates")
        archiveClassifier.set("")
        mergeServiceFiles()
        exclude("META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/NOTICE")
        relocate("com.github.stefvanschie.inventoryframework", "ru.ruscrafting.ecia.libs.inventoryframework")
        relocate("com.fasterxml.jackson", "ru.ruscrafting.ecia.libs.jackson")
    }
    build { dependsOn(shadowJar) }
}
