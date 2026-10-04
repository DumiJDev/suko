import java.nio.file.Files

plugins {
    id("java")
}

dependencies {
    implementation(project(":suko-jte"))
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

// Tailwind é compilado (não servido via CDN — o próprio Tailwind desaconselha
// o CDN/JIT em produção) a partir do HTML já gerado, com `content` a apontar
// para build/website/**/*.html: só as classes realmente usadas entram no CSS
// final, purgado e minificado. `npx --yes` evita exigir um `package.json`/
// `node_modules` neste módulo Gradle — resolve a versão pinada uma vez e
// usa cache do npm daí em diante.
val buildTailwindCss = tasks.register<Exec>("buildTailwindCss") {
    group = "documentation"
    description = "Compila o CSS do site (Tailwind) a partir do HTML gerado, purgado e minificado"
    dependsOn("generateWebsite")

    val tailwindDir = layout.projectDirectory.dir("tailwind")
    val cssOutputDir = layout.buildDirectory.dir("website/assets")

    workingDir = tailwindDir.asFile
    commandLine(
        "npx", "--yes", "tailwindcss@3.4.13",
        "-i", tailwindDir.file("input.css").asFile.absolutePath,
        "-c", tailwindDir.file("tailwind.config.js").asFile.absolutePath,
        "-o", cssOutputDir.get().file("site.css").asFile.absolutePath,
        "--minify"
    )

    doFirst {
        Files.createDirectories(cssOutputDir.get().asFile.toPath())
    }

    inputs.dir(layout.buildDirectory.dir("website"))
    inputs.file(tailwindDir.file("input.css"))
    inputs.file(tailwindDir.file("tailwind.config.js"))
    outputs.file(cssOutputDir.get().file("site.css"))
}

tasks.named("check") {
    dependsOn(buildTailwindCss)
}
tasks.named("build") {
    dependsOn(buildTailwindCss)
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
    inputs.dir(layout.projectDirectory.dir("src/main/static"))
    outputs.dir(layout.buildDirectory.dir("website"))
}

tasks.named("check") {
    dependsOn(generateWebsite)
}
tasks.named("build") {
    dependsOn(generateWebsite)
}