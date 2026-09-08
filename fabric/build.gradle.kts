plugins {
    id("net.fabricmc.fabric-loom") version "1.17-SNAPSHOT"
}

base {
    archivesName.set("${providers.gradleProperty("mod_id").get()}-fabric")
}

loom {
    splitEnvironmentSourceSets()

    mods {
        create(providers.gradleProperty("mod_id").get()) {
            sourceSet(sourceSets.main.get())
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
