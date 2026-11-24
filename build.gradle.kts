import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("net.fabricmc.fabric-loom")
    kotlin("jvm")
    kotlin("plugin.serialization")
}

version = property("mod_version") as String
group = property("maven_group") as String

base {
    archivesName.set("archica-mcp")
}

repositories {
    mavenCentral()
    exclusiveContent {
        forRepository {
            maven("https://api.modrinth.com/maven") {
                name = "Modrinth"
            }
        }
        filter {
            includeGroup("maven.modrinth")
        }
    }
}

loom {
    splitEnvironmentSourceSets()

    mods {
        register("archica-mcp") {
            sourceSet(sourceSets.main.get())
            sourceSet(sourceSets.getByName("client"))
        }
    }
}

val mcpSdkVersion = property("mcp_sdk_version") as String
val ktorVersion = property("ktor_version") as String

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")

    // Fabric API & Kotlin runtime
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    implementation("net.fabricmc:fabric-language-kotlin:${property("fabric_kotlin_version")}")

    // Client-only mod integrations (Litematica & MaLiLib via Modrinth Maven)
    "clientImplementation"("maven.modrinth:malilib:${property("malilib_version")}")
    "clientImplementation"("maven.modrinth:litematica:${property("litematica_version")}")

    // Embedded MCP Server (Kotlin SDK Server + Ktor CIO)
    implementation("io.modelcontextprotocol:kotlin-sdk-server:$mcpSdkVersion")
    implementation("io.ktor:ktor-server-cio-jvm:$ktorVersion")

    // Selective Jar-in-Jar bundling (excludes stdlib/coroutines/serialization/io provided by fabric-language-kotlin)
    listOf(
        "io.modelcontextprotocol:kotlin-sdk-server-jvm:$mcpSdkVersion",
        "io.modelcontextprotocol:kotlin-sdk-core-jvm:$mcpSdkVersion",
        "io.ktor:ktor-server-cio-jvm:$ktorVersion",
        "io.ktor:ktor-server-core-jvm:$ktorVersion",
        "io.ktor:ktor-server-sse-jvm:$ktorVersion",
        "io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion",
        "io.ktor:ktor-server-websockets-jvm:$ktorVersion",
        "io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion",
        "io.ktor:ktor-serialization-kotlinx-jvm:$ktorVersion",
        "io.ktor:ktor-serialization-jvm:$ktorVersion",
        "io.ktor:ktor-websocket-serialization-jvm:$ktorVersion",
        "io.ktor:ktor-http-cio-jvm:$ktorVersion",
        "io.ktor:ktor-http-jvm:$ktorVersion",
        "io.ktor:ktor-network-jvm:$ktorVersion",
        "io.ktor:ktor-events-jvm:$ktorVersion",
        "io.ktor:ktor-utils-jvm:$ktorVersion",
        "io.ktor:ktor-io-jvm:$ktorVersion",
        "io.ktor:ktor-sse-jvm:$ktorVersion",
        "org.jetbrains.kotlinx:kotlinx-collections-immutable-jvm:0.5.2",
        "org.jetbrains.kotlinx:kotlinx-serialization-json-io-jvm:1.11.0",
        "io.github.oshai:kotlin-logging-jvm:8.0.4",
        "com.typesafe:config:1.4.9",
    ).forEach { dep ->
        include(dep)
    }

    // Unit testing for pure Kotlin modules
    testImplementation(kotlin("test"))
}

tasks.processResources {
    val modVersion = project.version.toString()
    inputs.property("version", modVersion)

    filesMatching("fabric.mod.json") {
        expand("version" to modVersion)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
    }
}

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    val archiveBaseName = base.archivesName.get()
    inputs.property("archivesName", archiveBaseName)
    from("LICENSE") {
        rename { "${it}_$archiveBaseName" }
    }
}
