plugins {
    `java-library`
}

dependencies {
    api(project(":common-api"))
    api(project(":vulkan-context"))
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(providers.gradleProperty("java_version").get().toInt())
    }
    withSourcesJar()
}
