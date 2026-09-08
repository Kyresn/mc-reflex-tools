group = providers.gradleProperty("mod_group").get()
version = providers.gradleProperty("mod_version").get()

subprojects {
    group = rootProject.group
    version = rootProject.version
}
