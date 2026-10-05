plugins {
    java
    id("org.springframework.boot") version "3.3.4"
    id("io.spring.dependency-management") version "1.1.6"
    id("io.suko.lang")
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
        jtePolicy.set(false)                     // templates compilados em runtime: sem o plugin do JTE
        strictCsp.set(true)                      // nenhum style=/on*/<script> inline: a loja serve CSP estrita
    }
}

tasks.test { useJUnitPlatform() }
