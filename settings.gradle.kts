rootProject.name = "ArcExcellentCrates"

// Public builds resolve the pinned release from Maven. A local checkout is
// opt-in for ARC development and never becomes a required filesystem path.
providers.gradleProperty("arcCoreDir").orNull?.let(::file)?.let { arcCoreDir ->
    require(arcCoreDir.resolve("settings.gradle.kts").isFile) {
        "arcCoreDir must point to an arc-core checkout"
    }
    includeBuild(arcCoreDir) {
        dependencySubstitution {
            listOf(
                "arc-core",
                "arc-core-logging",
                "arc-core-menu",
                "arc-core-paper",
                "arc-core-paper-api",
                "arc-core-paper-menu",
                "arc-core-paper-testing",
            ).forEach { artifact ->
                substitute(module("ru.ruscrafting.arc:$artifact")).using(project(":$artifact"))
            }
        }
    }
}
