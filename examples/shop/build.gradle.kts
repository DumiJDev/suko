plugins {
    java
    id("org.springframework.boot") version "3.3.4"
    id("io.spring.dependency-management") version "1.1.6"
    id("io.suko.lang")
    id("gg.jte.gradle") version "3.1.12"
}

group = "shop"
version = "0.1.0"

java { sourceCompatibility = JavaVersion.VERSION_21 }

repositories { mavenCentral() }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    // O starter declara gg.jte:jte como <optional>: sem esta linha o @ConditionalOnClass(TemplateEngine)
    // da auto-configuração falha em silêncio e o Spring reencaminha as views para o servlet por omissão.
    implementation("gg.jte:jte:3.1.12")
    implementation("gg.jte:jte-spring-boot-starter-3:3.1.12")
    runtimeOnly("com.h2database:h2")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.jsoup:jsoup:1.17.2")
    // Gradle 9 deixou de pôr o launcher da JUnit Platform no classpath de teste (versão vem do BOM do Spring Boot).
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

suko {
    sourceDir.set("src/main/suko")
    outputDir.set("src/main/jte")                // raiz de templates do starter do JTE
    generatedPackage.set("shop.suko")
    security {
        // jtePolicy fica no valor por omissão (true): com o plugin do JTE aplicado, o Suko liga a
        // gg.jte.html.OwaspHtmlPolicy na geração dos templates (defesa em profundidade).
        strictCsp.set(true)                      // nenhum style=/on*/<script> inline: a loja serve CSP estrita
    }
}

// Templates pré-compilados: o plugin do JTE gera Java a partir dos .jte que o sukoCompile escreve em
// src/main/jte, e o compileJava compila-os com o resto. Em runtime não há compilação de templates
// (gg.jte.use-precompiled-templates=true): a pasta dos templates não é código executável.
jte {
    sourceDirectory.set(file("src/main/jte").toPath())
    contentType.set(gg.jte.ContentType.Html)
    generate()
}
tasks.named("generateJte") { dependsOn("sukoCompile") }

tasks.test { useJUnitPlatform() }
