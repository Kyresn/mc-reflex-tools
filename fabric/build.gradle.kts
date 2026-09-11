plugins {
    // Pinned to a release rather than 1.17-SNAPSHOT: a snapshot plugin version is
    // resolved fresh on every build, so the build stops being reproducible and the
    // resolution becomes a supply-chain surface.
    id("net.fabricmc.fabric-loom") version "1.17.20"
}

base {
    archivesName.set("${providers.gradleProperty("mod_id").get()}-fabric")
}

loom {
    splitEnvironmentSourceSets()

    mods {
        create(providers.gradleProperty("mod_id").get()) {
            sourceSet(sourceSets.main.get())
            sourceSet(sourceSets.named("client").get())
        }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:${providers.gradleProperty("minecraft_version").get()}")
    implementation("net.fabricmc:fabric-loader:${providers.gradleProperty("fabric_loader_version").get()}")
    implementation("net.fabricmc.fabric-api:fabric-api:${providers.gradleProperty("fabric_api_version").get()}")

    implementation(project(":common-api"))
    implementation(project(":vulkan-context"))
    implementation(project(":nvidia-sdk-java"))

    // Nest the sibling modules inside the mod jar. Without this the embedded
    // native bridge (nvidia-sdk-java/src/main/resources/natives) never reaches
    // the classpath of a distributed build, and NativeLibraryLoader falls back
    // to a developer-only workspace lookup.
    include(project(":common-api"))
    include(project(":vulkan-context"))
    include(project(":nvidia-sdk-java"))
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(providers.gradleProperty("java_version").get().toInt())
}

java {
    withSourcesJar()
    toolchain {
        languageVersion = JavaLanguageVersion.of(providers.gradleProperty("java_version").get().toInt())
    }
}
