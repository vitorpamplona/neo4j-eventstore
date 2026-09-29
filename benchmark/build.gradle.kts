plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(project(":projection"))
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(testFixtures(project(":projection")))
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.neo4j)
}

application {
    mainClass.set("com.vitorpamplona.neo4j.eventstore.benchmark.GraphDumpKt")
}

tasks.test {
    // docker-java defaults to API 1.32, which Docker Engine 29 refuses — every container
    // test would then SKIP silently (dockerAvailable() == false). Pin a version it accepts.
    systemProperty("api.version", "1.41")
    systemProperty(
        "neo4jImage",
        "neo4j:" +
            libs.versions.neo4j.image
                .get(),
    )

    // Integration tests (real Neo4j via testcontainers) are tagged and excluded from the
    // default build; `-Pintegration` runs them. They self-skip without a Docker daemon.
    useJUnitPlatform {
        if (!project.hasProperty("integration")) excludeTags("integration")
    }

    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}

kotlin {
    jvmToolchain(21)
}
