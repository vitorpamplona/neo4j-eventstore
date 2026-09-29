pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "neo4j-eventstore"

// A Neo4j graph projection of the events vespa-eventstore holds, queried through Cypher.
//   :engine — the graph schema, the pure event -> graph derivation, the GraphIndex port and its
//             two implementations (in-memory executable spec, Neo4j)
//   :projection — the change feed, the reconciler against the source of truth, the guarded Cypher
//                 service, and the open() front door
//   :benchmark — the bulk loader (dump -> neo4j-admin CSV), integration tests, probes (not published)
include(":engine")
include(":projection")
include(":benchmark")
