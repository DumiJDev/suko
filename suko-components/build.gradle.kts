plugins {
    id("java")
}

// CONTEÚDO PURO (spec, D3): este módulo não tem `src/main/java` e não
// declara nenhuma dependência em `main`. O que ele contém é `.sk` e o
// manifesto JSON gerado. As dependências abaixo existem só para o módulo
// se auto-validar (compilar e renderizar a própria biblioteca, e verificar
// que o manifesto commitado não divergiu dos fontes).
dependencies {
    testImplementation(project(":suko-registry"))
    testImplementation(testFixtures(project(":suko-core")))
    // suko-core declara jte como `implementation`, não `api` — não é
    // transitivo. Versão pinada igual à de suko-core.
    testImplementation("gg.jte:jte:3.1.12")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // O harness lê `src/main/suko/**/*.sk` diretamente do disco (via
    // Path.of("src", "main", "suko")), fora de qualquer sourceSet do
    // Gradle — sem esta declaração de input explícita, o Gradle não sabe
    // que mudar um .sk deve invalidar o cache de :test, e passaria a dar
    // UP-TO-DATE falso a partir da segunda execução.
    inputs.dir(layout.projectDirectory.dir("src/main/suko"))
}
