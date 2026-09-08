plugins {
    `java-library`
}

dependencies {
    api(project(":common-api"))
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(providers.gradleProperty("java_version").get().toInt())
    }
    withSourcesJar()
}
