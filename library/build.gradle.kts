plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.maven.publish)
}

group = "io.github.dalapenko"
version = "1.0.0"

// Configure Kotlin compiler
kotlin {
    jvmToolchain(17)
}

dependencies {
    // Ktor client dependencies - exposed as API
    api(libs.ktor.client.core)
    api(libs.ktor.client.websockets)
    api(libs.ktor.client.logging)

    // Coroutines for Flow support
    implementation(libs.kotlinx.coroutines.core)

    // Test dependencies
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.ktor.client.mock)
}

// Maven publishing configuration
mavenPublishing {
    coordinates(
        groupId = "io.github.dalapenko",
        artifactId = "ktor-reconnecting-websocket",
        version = version.toString()
    )

    pom {
        name.set("Ktor Reconnecting WebSocket")
        description.set("A resilient WebSocket client library for Ktor with automatic reconnection, exponential backoff, and observable connection states")
        url.set("https://github.com/dalapenko/ktor-reconnecting-websocket")
        inceptionYear.set("2026")

        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("dalapenko")
                name.set("Dmitrii Lapenko")
                email.set("dalapenko.dev@gmail.com")
            }
        }

        scm {
            url.set("https://github.com/dalapenko/ktor-reconnecting-websocket")
            connection.set("scm:git:git://github.com/dalapenko/ktor-reconnecting-websocket.git")
            developerConnection.set("scm:git:ssh://git@github.com/dalapenko/ktor-reconnecting-websocket.git")
        }
    }

    // Publish to Maven Central via Central Portal
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)

    // Enable GPG signing for all publications
    signAllPublications()
}
