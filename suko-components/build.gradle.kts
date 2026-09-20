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

// Tarefa 9 (subprojeto 7): regenera `registry.json` + `components/*.json` a
// partir de `src/main/suko`. Deliberadamente fina — toda a lógica (uma
// componente por ficheiro, `public`, dependsOn acíclico, sha256,
// description via `descriptions.properties`) já está em `suko-registry`,
// testada por JUnit lá; esta tarefa só invoca o mesmo entry point que
// `RegistryGoldenTest` usa para comparar (`main`, no mesmo ficheiro), para
// que "o que o teste compara" e "o que esta tarefa escreve" nunca possam
// divergir por terem sido escritos em dois sítios.
//
// NÃO entra na tarefa `build` nem em `check`: o manifesto commitado é a
// referência (verificada por `RegistryGoldenTest`, que corre em `test`);
// esta tarefa é só o caminho manual para o atualizar depois de mudar um
// `.sk` ou `descriptions.properties`.
tasks.register<JavaExec>("generateRegistry") {
    group = "suko"
    description = "Regenera registry.json e components/*.json a partir de src/main/suko " +
        "(NÃO corre como parte de build/check; commitar o resultado à mão)."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.suko.components.RegistryGoldenTest")
    workingDir = layout.projectDirectory.asFile
}
