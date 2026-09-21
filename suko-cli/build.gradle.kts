plugins {
    id("java")
}

dependencies {
    // Só o modelo do registry. NUNCA suko-core: ver Global Constraints do
    // plano do subprojeto 8 (D3/D9) — a CLI não pode arrastar o compilador,
    // ANTLR, nem gg.jte para o classpath de quem a instala.
    implementation(project(":suko-registry"))

    // Mesma versão já pinada em suko-registry (implementation lá, por isso
    // não visível no compile-classpath deste módulo por transitividade —
    // só no runtime). Declarada aqui explicitamente, não como "dependência
    // nova": é o mesmo jar já resolvido no grafo, só passa a ser visível a
    // compile-time também em suko-cli. Isto mantém uma única biblioteca de
    // JSON no fat jar (Task 10 do plano do subprojeto 8 exige exatamente
    // isto para o lockfile) em vez de ProjectConfig ter o seu próprio
    // parser à parte do que o lockfile vai usar.
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()

    // NativeImageSmokeTest (Step 7, Task 13) needs a fresh fat jar to run
    // `jbang build --native` against; the jar build itself is cheap (a few
    // seconds), so it is always kept current for `gradle test` rather than
    // asking whoever runs the opt-in native test to remember a separate
    // `fatJar` invocation first. `fatJar` is declared further down this
    // file; Gradle resolves the forward reference at configuration time.
    dependsOn(tasks.named("fatJar"))
}

// Step 2 (Task 13): the CLI needs to know its own version at runtime (see
// io.suko.cli.Version — D5, the registry ref defaults to the CLI's own
// version tag). Same manifest-attributes pattern already used by
// suko-maven-plugin's `tasks.jar` block, applied here to the plain jar
// too (not just the fat jar below) so Version works whichever of the two
// jars ends up on the runtime classpath.
tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "Suko CLI",
            "Implementation-Version" to version,
        )
    }
}

// Step 1 (Task 13): a hand-written executable fat jar, no Shadow plugin
// (Global Constraints of the subprojeto 8 plan). Unpacks
// `runtimeClasspath` into a single self-contained jar instead of relying
// on a third-party fat-jar plugin. There is no `META-INF/services` to
// merge across the dependency graph (Gson does not use
// ServiceLoader-style provider files), so this simple approach — as
// opposed to Shadow's more careful merging of duplicate service-provider
// entries — is sufficient.
val fatJar = tasks.register<Jar>("fatJar") {
    group = "distribution"
    description = "Builds a self-contained, executable suko-cli-<version>-all.jar " +
            "(runtimeClasspath unpacked into the jar; no Shadow plugin)."
    archiveClassifier.set("all")

    // Some entries can legitimately collide across independent jars on
    // the classpath (e.g. more than one dependency shipping the same
    // META-INF/LICENSE-ish file); last-one-wins is fine here since none
    // of those files affect behavior, and there is nothing
    // service-loader-shaped to merge instead of overwrite.
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes(
            "Main-Class" to "io.suko.cli.Main",
            "Implementation-Title" to "Suko CLI",
            "Implementation-Version" to version,
        )
    }

    from(sourceSets.main.get().output)
    from(configurations.runtimeClasspath.map { classpath ->
        classpath.map { if (it.isDirectory) it else zipTree(it) }
    })

    // A fat jar bundling ANTLR/gg.jte-adjacent dependency graphs (even
    // though suko-cli itself never depends on suko-core — see the
    // dependency block above) can exceed the 65535-entry limit of the
    // classic zip format once every dependency's classes are unpacked
    // into it; Zip64 lifts that ceiling.
    isZip64 = true
}

tasks.named("assemble") {
    dependsOn(fatJar)
}
