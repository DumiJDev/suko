plugins {
    id("java")
}

dependencies {
    // Só o compilador. NUNCA suko-cli (spec do 11a, secção Módulos): o server lê a
    // chave `sourceRoot` do suko.json com o Gson que o LSP4J já traz.
    implementation(project(":suko-core")) {
        // O plugin `antlr` põe o ANTLR *tool* (com ICU e StringTemplate, ~15 MB) no
        // `api` do suko-core. O server só precisa do runtime, declarado abaixo.
        exclude(group = "org.antlr", module = "antlr4")
    }
    implementation("org.antlr:antlr4-runtime:4.13.1")

    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:1.0.0")
    // Mesma versão que suko-cli e suko-registry; o LSP4J aceita [2.9.1,3.0).
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "Suko Language Server",
            "Implementation-Version" to version,
        )
    }
}

// Fat jar à mão, sem Shadow — o mesmo padrão do suko-cli (ver o comentário
// extenso em suko-cli/build.gradle.kts sobre `dependsOn(runtimeClasspath)`, a
// ordem dos `from(...)` e Zip64). A extensão VSCode embute este jar.
val fatJar = tasks.register<Jar>("fatJar") {
    group = "distribution"
    description = "Builds a self-contained, executable suko-lsp-<version>-all.jar."
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes(
            "Main-Class" to "io.suko.lsp.Main",
            "Implementation-Title" to "Suko Language Server",
            "Implementation-Version" to version,
        )
    }

    dependsOn(configurations.runtimeClasspath)
    from(sourceSets.main.get().output)
    from(configurations.runtimeClasspath.map { classpath ->
        classpath.map { if (it.isDirectory) it else zipTree(it) }
    })
    // Assinaturas de jars assinados invalidariam o jar combinado.
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    isZip64 = true
}

tasks.named("assemble") {
    dependsOn(fatJar)
}

tasks.test {
    useJUnitPlatform()
    dependsOn(fatJar)
    doFirst {
        systemProperty("suko.lsp.fatJar", fatJar.get().archiveFile.get().asFile.absolutePath)
    }
}
