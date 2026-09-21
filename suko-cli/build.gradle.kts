plugins {
    id("java")
}

dependencies {
    // Só o modelo do registry. NUNCA suko-core: ver Global Constraints do
    // plano do subprojeto 8 (D3/D9) — a CLI não pode arrastar o compilador,
    // ANTLR, nem gg.jte para o classpath de quem a instala. `implementation`
    // (não `api`) é deliberado: mantém o Gson que suko-registry usa
    // internamente fora do compile-classpath deste módulo, o que por sua
    // vez é o motivo por que ProjectConfig (suko.json) tem o seu próprio
    // parser/escritor JSON minúsculo em vez de reutilizar Gson.
    implementation(project(":suko-registry"))

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
