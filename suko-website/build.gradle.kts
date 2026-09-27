plugins {
    id("java")
}

dependencies {
    implementation(project(":suko-core"))
    implementation(project(":suko-registry"))
    implementation("gg.jte:jte:3.1.12")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

val generateWebsite = tasks.register<JavaExec>("generateWebsite") {
    group = "documentation"
    description = "Gera o site de documentação estático a partir dos ficheiros .sk em src/main/suko"
    mainClass.set("io.suko.website.WebsiteGenerator")
    classpath = sourceSets.main.get().runtimeClasspath

    // Path resolution: project dir → parent = repo root → sibling = suko-components
    val projectDir = layout.projectDirectory.asFile
    val repoRoot = projectDir.parentFile
    val sourceRootDir = layout.projectDirectory.dir("src/main/suko").asFile
    val outputDirFile = layout.buildDirectory.dir("website").get().asFile
    val registryDirFile = repoRoot.toPath().resolve("suko-components").toFile()

    args(
        projectDir.absolutePath,
        sourceRootDir.absolutePath,
        outputDirFile.absolutePath,
        registryDirFile.absolutePath
    )

    inputs.dir(layout.projectDirectory.dir("src/main/suko"))
    outputs.dir(layout.buildDirectory.dir("website"))
}

tasks.named("check") {
    dependsOn(generateWebsite)
}
tasks.named("build") {
    dependsOn(generateWebsite)
}