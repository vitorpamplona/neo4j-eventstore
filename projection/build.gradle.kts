plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.vanniktech.mavenPublish)
}

dependencies {
    api(project(":engine"))
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    // The guard tests read the SOURCE of both published modules, so an edit in either must
    // re-run them rather than leave a cached pass (same reason as vespa-eventstore's).
    inputs
        .files(
            fileTree(rootProject.file("engine/src")) { include("**/*.kt") },
            fileTree(rootProject.file("projection/src")) { include("**/*.kt") },
        ).withPropertyName("sourceTreeUnderGuard")
}

mavenPublishing {
    coordinates(
        groupId = "com.vitorpamplona.neo4j.eventstore",
        artifactId = "projection",
        version = libs.versions.app.get(),
    )
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()
    pom {
        name = "Neo4j Event Store: projection"
        description =
            "The change feed and reconciler that keep a Neo4j graph an exact projection of a Nostr event store, and the guarded read-only Cypher service."
        inceptionYear = "2026"
        url = "https://github.com/vitorpamplona/neo4j-eventstore/"
        licenses {
            license {
                name = "MIT License"
                url = "https://github.com/vitorpamplona/neo4j-eventstore/blob/main/LICENSE"
            }
        }
        developers {
            developer {
                id = "vitorpamplona"
                name = "Vitor Pamplona"
                url = "https://github.com/vitorpamplona"
            }
        }
        scm {
            url = "https://github.com/vitorpamplona/neo4j-eventstore/"
            connection = "https://github.com/vitorpamplona/neo4j-eventstore/.git"
        }
    }
}
