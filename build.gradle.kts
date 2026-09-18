plugins {
    id("java")
    id("antlr")
}

group = "io.suko"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    antlr("org.antlr:antlr4:4.13.1")

    implementation("gg.jte:jte:3.1.12")

    compileOnly(gradleApi())

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(gradleApi())
    testImplementation(gradleTestKit())
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named("generateGrammarSource") {
    enabled = false
}

val grammarDir = layout.projectDirectory.dir("src/main/antlr/io/suko/lang")
val antlrOutputDir = layout.buildDirectory.dir("generated-src/antlr/main/io/suko/lang")

val generateSukoLexer = tasks.register<JavaExec>("generateSukoLexer") {
    val outDir = antlrOutputDir.get().asFile
    doFirst { outDir.mkdirs() }

    classpath = configurations["antlr"]
    mainClass.set("org.antlr.v4.Tool")

    args = listOf(
        "-visitor",
        "-package", "io.suko.lang",
        "-o", outDir.path,
        grammarDir.file("SukoLexer.g4").asFile.path
    )

    inputs.file(grammarDir.file("SukoLexer.g4"))
    outputs.dir(outDir)
}

val generateSukoParser = tasks.register<JavaExec>("generateSukoParser") {
    dependsOn(generateSukoLexer)

    val outDir = antlrOutputDir.get().asFile
    doFirst { outDir.mkdirs() }

    classpath = configurations["antlr"]
    mainClass.set("org.antlr.v4.Tool")

    args = listOf(
        "-visitor",
        "-package", "io.suko.lang",
        "-lib", outDir.path,
        "-o", outDir.path,
        grammarDir.file("SukoParser.g4").asFile.path
    )

    inputs.file(grammarDir.file("SukoParser.g4"))
    outputs.dir(outDir)
}

sourceSets {
    main {
        java {
            srcDir(antlrOutputDir)
        }
    }
}

tasks.compileJava {
    dependsOn(generateSukoParser)
}

tasks.test {
    useJUnitPlatform()
    dependsOn(generateSukoParser)
}