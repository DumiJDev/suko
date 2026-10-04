package io.suko.lsp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExtensionsTest {

    static Path projectWithManifest(Path root, List<String> classpath) throws Exception {
        Files.createDirectories(root.resolve("src/main/suko"));
        Files.writeString(root.resolve("src/main/suko/Hello.sk"), "component Hello() {\n  <box></box>\n}\n");
        io.suko.lang.ext.ExtensionManifest.write(root.resolve("build/suko/extensions.json"),
            classpath.stream().map(Path::of).toList(), List.of("jte", "demo"));
        return root;
    }

    static String extJar() {
        return System.getProperty("suko.testExtJar");
    }

    @Test
    void trustedWorkspaceRunsTheProjectExtensions(@TempDir Path root) throws Exception {
        projectWithManifest(root, List.of(extJar()));
        var loaded = ProjectExtensions.forProject(root.resolve("src/main/suko"), root, true);
        assertEquals(List.of("jte", "demo"), loaded.targets());
        assertTrue(loaded.registry().target("demo").isPresent());
        assertTrue(loaded.notice().isEmpty());
    }

    @Test
    void untrustedWorkspaceIgnoresExtensions(@TempDir Path root) throws Exception {
        projectWithManifest(root, List.of(extJar()));
        var loaded = ProjectExtensions.forProject(root.resolve("src/main/suko"), root, false);
        assertEquals(List.of("jte"), loaded.targets());
        assertTrue(loaded.registry().target("demo").isEmpty());
        assertTrue(loaded.registry().target("jte").isPresent());
        assertTrue(loaded.notice().orElseThrow().contains("confi"), loaded.notice().toString());
    }

    @Test
    void noManifestMeansOnlyTheBuiltInJte(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("src/main/suko"));
        var loaded = ProjectExtensions.forProject(root.resolve("src/main/suko"), root, true);
        assertEquals(List.of("jte"), loaded.targets());
        assertTrue(loaded.registry().loadDiagnostics().isEmpty());
    }

    @Test
    void duplicateJteFromManifestIsIgnored(@TempDir Path root) throws Exception {
        String jteJar = Path.of(io.suko.jte.JteExtension.class.getProtectionDomain()
            .getCodeSource().getLocation().toURI()).toString();
        projectWithManifest(root, List.of(jteJar, extJar()));
        var loaded = ProjectExtensions.forProject(root.resolve("src/main/suko"), root, true);
        assertTrue(loaded.registry().loadDiagnostics().isEmpty(), loaded.registry().loadDiagnostics().toString());
    }

    static com.google.gson.JsonObject trusted(boolean value) {
        com.google.gson.JsonObject options = new com.google.gson.JsonObject();
        options.addProperty("trusted", value);
        return options;
    }

    static List<String> codes(TestSupport t, String file) {
        return t.client.lastFor(t.uri(file)).getDiagnostics().stream()
            .map(d -> d.getCode().getLeft()).toList();
    }

    @Test
    void trustedExtensionCheckerShowsUpAsEditorDiagnostic(@TempDir Path root) throws Exception {
        projectWithManifest(root, List.of(extJar())); // antes do server: o projeto é descoberto no initialize
        TestSupport t = new TestSupport(root, trusted(true));
        t.open("Hello.sk", "component Hello() {\n  <box></box>\n}\n");
        t.scheduler.fire();
        assertTrue(codes(t, "Hello.sk").contains("DEMO_CHECK"), codes(t, "Hello.sk").toString());
    }

    @Test
    void untrustedServerDoesNotRunTheCheckerButStillWorks(@TempDir Path root) throws Exception {
        projectWithManifest(root, List.of(extJar()));
        TestSupport t = new TestSupport(root, trusted(false));
        t.open("Hello.sk", "component Hello() {\n  <box></box>\n}\n");
        t.scheduler.fire();
        assertFalse(codes(t, "Hello.sk").contains("DEMO_CHECK"), codes(t, "Hello.sk").toString());
    }

    @Test
    void crashingExtensionDoesNotKillServer(@TempDir Path root) throws Exception {
        projectWithManifest(root, List.of(extJar()));
        TestSupport t = new TestSupport(root, trusted(true));
        t.open("Boom.sk", "component Boom() {\n  <box></box>\n}\n");
        t.scheduler.fire();
        assertTrue(codes(t, "Boom.sk").contains("EXTENSION_FAILED"), codes(t, "Boom.sk").toString());

        t.open("Hello.sk", "component Hello() {\n  <box></box>\n}\n");
        t.scheduler.fire();
        assertTrue(codes(t, "Hello.sk").contains("DEMO_CHECK"), "o server continua vivo depois da falha");
    }
}
