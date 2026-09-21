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

    // Task 14: the plan's capstone test (FullCycleTest) is the ONE place in
    // this module that needs the compiler, the Gradle plugin, and the real
    // gg.jte engine — and, per the deliberate note in the task brief, that
    // need is confined to `src/test`, exactly like suko-components does
    // since subprojeto 7. `main` above still depends on nothing but
    // suko-registry + gson (D3/D9 of the subprojeto 8 plan).
    testImplementation(project(":suko-gradle-plugin"))
    testImplementation(testFixtures(project(":suko-core")))
    // suko-core declares jte as `implementation`, not `api` — not
    // transitive. Version pinned to match suko-core's own.
    testImplementation("gg.jte:jte:3.1.12")
    testImplementation(gradleApi())
    testImplementation(gradleTestKit())

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Task 14: the one real design problem in this task. `FullCycleTest` uses
// Gradle TestKit's `GradleRunner` to apply `io.suko.lang` in an isolated,
// temporary consumer build — but that test lives in `suko-cli`'s own test
// sourceSet, a DIFFERENT module from the one that declares the plugin
// (`suko-gradle-plugin`). Bare `GradleRunner.withPluginClasspath()` (used
// successfully by `SukoPluginFunctionalTest`, inside suko-gradle-plugin
// itself) only auto-detects a plugin-under-test classpath when the calling
// test lives inside the module that applies `java-gradle-plugin` — that
// plugin wires a generated `plugin-under-test-metadata.properties` onto
// ITS OWN `test` sourceSet's runtime classpath, not onto any other
// module's.
//
// The fix: a resolvable configuration here, attributed exactly like the
// standard Java runtime classpath (Usage.JAVA_RUNTIME), depending on
// `project(":suko-gradle-plugin")`. Variant-aware resolution then picks
// that project's `runtimeElements` variant — main output (including the
// generated plugin descriptor resource, since java-gradle-plugin wires
// `generatePluginDescriptors`'s output into the main sourceSet) plus every
// transitive runtime dependency (suko-core, and everything IT pulls in:
// the ANTLR runtime, gg.jte, slf4j-api, ...). That resolved file
// collection is exactly the `List<File>` `GradleRunner.withPluginClasspath(List<File>)`
// needs. It reaches the test JVM as a system property (set in `doFirst`,
// not eagerly at configuration time, so the dependency's tasks — e.g.
// suko-gradle-plugin's compileJava — run first and the files actually
// exist by the time they're read).
val pluginUnderTestRuntime: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
    }
}

dependencies {
    pluginUnderTestRuntime(project(":suko-gradle-plugin"))
}

tasks.test {
    useJUnitPlatform()

    // Task 14: FullCycleTest uses org.gradle.testfixtures.ProjectBuilder
    // (Step 5, to construct a SukoWatchTask instance directly) in the SAME
    // test JVM as a GradleRunner-driven TestKit build (Step 3). On modern
    // JDKs, ProjectBuilder's internal "inject legacy interfaces into the
    // classloader" step needs a privateLookupIn into java.lang, which the
    // module system refuses without this opens — surfacing as
    // IllegalAccessException: "module java.base does not open java.lang to
    // unnamed module" only in this cross-module combination (suko-gradle-plugin's
    // own equivalent test, SukoWatchTaskE2ETest, never combines ProjectBuilder
    // with a GradleRunner build in the same test JVM, so it doesn't hit this).
    jvmArgs("--add-opens", "java.base/java.lang=ALL-UNNAMED")

    // NativeImageSmokeTest (Step 7, Task 13) needs a fresh fat jar to run
    // `jbang build --native` against; the jar build itself is cheap (a few
    // seconds), so it is always kept current for `gradle test` rather than
    // asking whoever runs the opt-in native test to remember a separate
    // `fatJar` invocation first. `fatJar` is declared further down this
    // file; Gradle resolves the forward reference at configuration time.
    dependsOn(tasks.named("fatJar"))

    dependsOn(pluginUnderTestRuntime)
    doFirst {
        systemProperty(
            "suko.pluginUnderTestClasspath",
            pluginUnderTestRuntime.files.joinToString(File.pathSeparator) { it.absolutePath })
    }
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

    // suko-cli's own dependency graph (suko-registry + gson) is small —
    // this fat jar is ~400KB and comfortably under the classic zip
    // format's 65535-entry limit. Zip64 is enabled anyway as a safe
    // default: it costs nothing when the entry count is small, and avoids
    // a silent, hard-to-diagnose failure mode if a future dependency ever
    // pushes this module past that limit (e.g. a heavier registry source
    // or JSON library swapped in later) without anyone remembering to
    // revisit this task's assumptions first.
    isZip64 = true
}

tasks.named("assemble") {
    dependsOn(fatJar)
}
