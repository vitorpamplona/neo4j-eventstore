plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.vanniktech.mavenPublish)
}

dependencies {
    // Quartz is `api`: typed events and IdAndTime are in this module's public surface
    // (GraphIndex.apply takes Quartz Events; visitIds hands back IdAndTime).
    api(libs.quartz)
    // `api` because Neo4jGraphIndex is constructed from a driver the caller owns.
    api(libs.neo4j.driver)
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
    // RelationCatalogTest compares the generated catalog with this file: an edit to it alone
    // must re-run the test, not hit the cache.
    inputs.file(rootProject.file("docs/relations.md")).withPropertyName("relationCatalog")
    environment("UPDATE_DOCS", System.getenv("UPDATE_DOCS") ?: "")
}

mavenPublishing {
    coordinates(
        groupId = "com.vitorpamplona.neo4j.eventstore",
        artifactId = "engine",
        version = libs.versions.app.get(),
    )
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()
    pom {
        name = "Neo4j Event Store: engine"
        description =
            "The Nostr graph schema, the event -> graph derivation from Quartz's hint providers, and the GraphIndex port with its in-memory and Neo4j implementations."
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
