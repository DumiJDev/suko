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
}
