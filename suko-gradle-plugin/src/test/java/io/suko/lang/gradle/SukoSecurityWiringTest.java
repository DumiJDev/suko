package io.suko.lang.gradle;

import org.gradle.api.Project;
import org.gradle.api.provider.Property;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * O plugin gg.jte.gradle não é resolvível offline no TestKit, por isso a ligação da política do JTE é testada
 * com uma extensão "jte" falsa que tem a mesma propriedade {@code htmlPolicyClass}; o paralelismo
 * sukoWatch/compileAll é testado diretamente sobre {@code SukoWatchTask.compileAll} (o ciclo do
 * WatchService em si não é testável sem ficar pendurado).
 */
class SukoSecurityWiringTest {

    public abstract static class StubJte {
        public abstract Property<String> getHtmlPolicyClass();
    }

    public static class StubJteWithoutProperty {
    }

    private Project project(Path dir) {
        Project p = ProjectBuilder.builder().withProjectDir(dir.toFile()).withName("wiring").build();
        p.getPluginManager().apply("io.suko.lang");
        return p;
    }

    private SukoExtension ext(Project p) {
        return p.getExtensions().getByType(SukoExtension.class);
    }

    @Test
    void jtePolicyDefaultsToOn(@TempDir Path dir) {
        Project p = project(dir);
        StubJte jte = p.getExtensions().create("jte", StubJte.class);
        SukoGradlePlugin.applyJtePolicy(p, ext(p));
        assertEquals("gg.jte.html.OwaspHtmlPolicy", jte.getHtmlPolicyClass().getOrNull());
    }

    @Test
    void jtePolicyFalseIsHonouredEvenWhenSetAfterTheWiring(@TempDir Path dir) {
        Project p = project(dir);
        StubJte jte = p.getExtensions().create("jte", StubJte.class);
        SukoGradlePlugin.applyJtePolicy(p, ext(p));
        ext(p).getSecurity().getJtePolicy().set(false);
        assertNull(jte.getHtmlPolicyClass().getOrNull());
    }

    @Test
    void userValueWins(@TempDir Path dir) {
        Project p = project(dir);
        StubJte jte = p.getExtensions().create("jte", StubJte.class);
        jte.getHtmlPolicyClass().set("com.acme.MyPolicy");
        SukoGradlePlugin.applyJtePolicy(p, ext(p));
        assertEquals("com.acme.MyPolicy", jte.getHtmlPolicyClass().get());
    }

    @Test
    void missingPropertyOnlyWarns(@TempDir Path dir) {
        Project p = project(dir);
        p.getExtensions().create("jte", StubJteWithoutProperty.class);
        assertDoesNotThrow(() -> SukoGradlePlugin.applyJtePolicy(p, ext(p)));
    }

    @Test
    void watchCompileAllHonoursSecurityOptions(@TempDir Path dir) throws Exception {
        Project p = project(dir);
        ext(p).getSecurity().getUrlSchemes().set(java.util.List.of("https"));
        ext(p).getGeneratedPackage().set("com.watch");
        Path src = dir.resolve("src/main/suko");
        Files.createDirectories(src);
        Files.writeString(src.resolve("A.sk"), "component A(String e) { <iframe src=${trustedUrl(e)}></iframe> }");
        SukoWatchTask task = (SukoWatchTask) p.getTasks().getByName("sukoWatch");
        task.compileAll(src, ext(p).getOutputDirAsPath());

        String safe = Files.readString(dir.resolve("build/generated-src/suko-java/com/watch/SukoSafe.java"));
        assertTrue(safe.contains("Set.of(\"https\")"), safe);
        assertTrue(Files.readString(dir.resolve("build/suko/security-audit.json")).contains("TRUSTED_URL"));
    }
}
