plugins {
    id("net.neoforged.moddev") version "2.0.142"
}

base {
    archivesName.set("${providers.gradleProperty("mod_id").get()}-neoforge")
}

neoForge {
    version = providers.gradleProperty("neoforge_version").get()

    mods {
        create(providers.gradleProperty("mod_id").get()) {
            sourceSet(sourceSets.main.get())
        }
    }

    runs {
        create("client") {
            client()
            gameDirectory.set(file("run"))
        }
    }
}

dependencies {
    implementation(project(":common-api"))
    implementation(project(":vulkan-context"))
    implementation(project(":nvidia-sdk-java"))
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("META-INF/neoforge.mods.toml") {
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
