import org.gradle.external.javadoc.StandardJavadocDocletOptions

plugins {
    `java-library`
}

description = "Maven plugin for Suko language compilation"

dependencies {
    // NOTE: `org.apache.maven:maven-bom` não existe como artefacto publicado
    // (confirmado por 404 no Maven Central) — o script original tentava usar
    // `platform("org.apache.maven:maven-bom:3.9.6")`, o que nunca teria resolvido.
    // Fixa-se a versão diretamente em `maven-plugin-api`.
    implementation("org.apache.maven:maven-plugin-api:3.9.6")
    // MavenProject (usado no campo @Parameter `project`) vive em maven-core,
    // não em maven-plugin-api — faltava esta dependência (mais um sintoma de
    // que este módulo nunca tinha sido compilado).
    implementation("org.apache.maven:maven-core:3.9.6")
    implementation("org.apache.maven.plugin-tools:maven-plugin-annotations:3.11.0")
    implementation("org.codehaus.plexus:plexus-utils:3.6.0")

    // Suko core (grammar, AST, JteCompiler, SukoProjectCompiler)
    implementation(project(":suko-core"))

    // ANTLR and JTE (same as core)
    implementation("org.antlr:antlr4:4.13.1")
    implementation("gg.jte:jte:3.1.12")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Java version: fixada uniformemente na raiz (D12, ver build.gradle.kts) via
// `options.release.set(21)` em `subprojects {}`, aplicado a todos os módulos
// por igual. O problema original registado aqui — um `sourceCompatibility`/
// `targetCompatibility` explícito a 17 só neste módulo, em conflito de
// resolução de variante com suko-core (que não fixava versão nenhuma) — já
// não existe: nenhum módulo declara `sourceCompatibility`/`targetCompatibility`
// agora, precisamente para não tocar no atributo de variante
// `org.gradle.jvm.version`. `options.release` foi escolhido em vez desses dois
// por essa razão: fixa o piso de bytecode (major version 65) e a API Java
// aceite em tempo de compilação, sem alterar esse atributo de variante.

// Jar configuration for Maven plugin
tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "Suko Maven Plugin",
            "Implementation-Version" to version,
            "Generated-By" to "Gradle"
        )
    }
}

// Configure the Javadoc
tasks.withType<Javadoc> {
    options.encoding = "UTF-8"
    (options as StandardJavadocDocletOptions).apply {
        charSet("UTF-8")
        author(true)
        version(true)
    }
}

// Test configuration
tasks.test {
    useJUnitPlatform()
}

// NOTE (subprojeto 5, Tarefa 7): este ficheiro tinha, antes desta tarefa, um
// bloco `generateMavenPluginDescriptor` (tipo de tarefa
// `org.apache.maven.plugin.tools.ant.GenerateMojoDescriptor`) e um bloco
// `publishing { ... }`. Nenhum dos dois nunca funcionou: o módulo nunca esteve
// ligado a um build multi-projeto (não existia `settings.gradle`/`settings.gradle.kts`
// no repositório — `gradle projects` na raiz mostrava "No sub-projects"), o script
// tinha erros de sintaxe Kotlin (mapa de atributos do manifest com `:` em vez de
// `to`, listas `[...]` em vez de `listOf(...)`), o tipo `GenerateMojoDescriptor`
// nunca esteve no classpath do buildscript (só haveria classpath de execução do
// projeto, que não é o que uma declaração `tasks.register<T>` usa para resolver
// `T`), e o bloco `publishing {}` era usado sem o plugin `maven-publish` aplicado.
// Ou seja: este módulo nunca compilou nem correu testes desde que foi introduzido.
// Esta tarefa precisa de `gradle test` funcional neste módulo para o teste Maven
// pedido no plano (RED/GREEN reais), por isso a descoberta e a correção mínima
// (settings.gradle.kts a incluir este módulo + esta limpeza) ficam documentadas
// aqui. A geração do descritor do plugin Maven e a publicação não são necessárias
// para compilar/testar a Mojo — ficam fora de âmbito desta tarefa; podem ser
// retomadas por um plano futuro que as construa corretamente (buildscript
// classpath com `maven-plugin-tools-generators`, plugin `maven-publish` aplicado).
