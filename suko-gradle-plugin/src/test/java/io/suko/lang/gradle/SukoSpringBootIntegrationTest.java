package io.suko.lang.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Prova ponta-a-ponta de que um consumidor Spring Boot real consegue usar o
 * `.jte` gerado pelo Suko sem qualquer adaptação: a documentação
 * (ARCHITECTURE.md, "Decisões de design que moldam o pipeline") já afirmava
 * isto por construção — "1 componente Suko → 1 template JTE" simples,
 * consumível pelo `jte-spring-boot-starter` tal como qualquer `.jte` escrito
 * à mão — mas, antes deste teste, nada no repositório verificava a
 * afirmação. Este teste monta um projeto Spring Boot descartável num
 * {@code @TempDir}, aplica o plugin {@code io.suko.lang} real (via TestKit,
 * mesmo mecanismo do {@link SukoPluginFunctionalTest}) com
 * {@code outputDir = "src/main/jte"} (a convenção por omissão do
 * jte-spring-boot-starter, `JteProperties.templateLocation`), sobe a
 * aplicação real (porta aleatória) e faz um pedido HTTP real a um
 * {@code @Controller} que devolve o nome de uma view compilada a partir de
 * um `.sk`. Zero `.jte` escrito à mão neste teste.
 * <p>
 * {@code gg.jte.developmentMode=true} evita o passo extra de precompilação
 * do `jte-gradle-plugin` — o `jte-spring-boot-starter` lê os `.jte` do
 * disco em runtime (via `DirectoryCodeResolver`), que é exatamente o que o
 * Suko acabou de gerar em `src/main/jte`; suficiente para provar a
 * integração, sem exigir também a resolução do `jte-gradle-plugin` aqui.
 * </p>
 */
class SukoSpringBootIntegrationTest {

    private static final String SPRING_BOOT_VERSION = "3.3.4";
    private static final String JTE_SPRING_BOOT_STARTER_VERSION = "3.1.15";

    @TempDir
    Path projectDir;

    @Test
    void springBootControllerRendersSukoCompiledTemplate() throws IOException {
        Files.writeString(projectDir.resolve("settings.gradle.kts"), """
            rootProject.name = "suko-spring-boot-smoke"
            """);

        Files.writeString(projectDir.resolve("build.gradle.kts"), """
            plugins {
                id("java")
                id("io.suko.lang")
                id("org.springframework.boot") version "%s"
            }

            suko {
                sourceDir = "src/main/suko"
                outputDir = "src/main/jte"
            }

            repositories { mavenCentral() }

            dependencies {
                implementation(platform("org.springframework.boot:spring-boot-dependencies:%s"))
                implementation("org.springframework.boot:spring-boot-starter-web")
                implementation("gg.jte:jte-spring-boot-starter-3:%s")
                testImplementation("org.springframework.boot:spring-boot-starter-test")
                testRuntimeOnly("org.junit.platform:junit-platform-launcher")
            }

            tasks.named("compileJava") { dependsOn("sukoCompile") }
            tasks.named<Test>("test") { useJUnitPlatform() }
            tasks.withType<JavaCompile>().configureEach { options.release.set(21) }
            """.formatted(SPRING_BOOT_VERSION, SPRING_BOOT_VERSION, JTE_SPRING_BOOT_STARTER_VERSION));

        Path sukoDir = projectDir.resolve("src/main/suko/greeting");
        Files.createDirectories(sukoDir);
        Files.writeString(sukoDir.resolve("Hello.sk"), """
            package greeting;

            component Hello(String name) {
              <div class="greeting">Olá, ${name}!</div>
            }
            """);

        Path javaDir = projectDir.resolve("src/main/java/smoke");
        Files.createDirectories(javaDir);
        Files.writeString(javaDir.resolve("DemoApplication.java"), """
            package smoke;

            import org.springframework.boot.SpringApplication;
            import org.springframework.boot.autoconfigure.SpringBootApplication;
            import org.springframework.stereotype.Controller;
            import org.springframework.ui.Model;
            import org.springframework.web.bind.annotation.GetMapping;

            @SpringBootApplication
            public class DemoApplication {
                public static void main(String[] args) {
                    SpringApplication.run(DemoApplication.class, args);
                }
            }

            @Controller
            class HelloController {
                @GetMapping("/hello")
                String hello(Model model) {
                    model.addAttribute("name", "Suko");
                    return "greeting/Hello";
                }
            }
            """);

        Path resourcesDir = projectDir.resolve("src/main/resources");
        Files.createDirectories(resourcesDir);
        Files.writeString(resourcesDir.resolve("application.properties"), """
            gg.jte.developmentMode=true
            """);

        Path testDir = projectDir.resolve("src/test/java/smoke");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("HelloControllerTest.java"), """
            package smoke;

            import org.junit.jupiter.api.Test;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.boot.test.context.SpringBootTest;
            import org.springframework.boot.test.web.client.TestRestTemplate;

            import static org.junit.jupiter.api.Assertions.assertTrue;

            @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
            class HelloControllerTest {

                @Autowired
                TestRestTemplate rest;

                @Test
                void rendersSukoCompiledTemplate() {
                    String body = rest.getForObject("/hello", String.class);
                    assertTrue(body != null && body.contains("Olá, Suko!"),
                        "expected the Suko-interpolated greeting in the response body: " + body);
                    assertTrue(body != null && body.contains("class=\\"greeting\\""),
                        "expected Hello's own wrapping <div>: " + body);
                }
            }
            """);

        BuildResult result = GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            .withArguments("test", "--console=plain", "--stacktrace")
            .build();

        Path generatedJte = projectDir.resolve("src/main/jte/greeting/Hello.jte");
        assertFalse(Files.notExists(generatedJte), "expected " + generatedJte + " to exist after sukoCompile");
        assertFalse(result.getOutput().contains("BUILD FAILED"), result.getOutput());
    }
}
