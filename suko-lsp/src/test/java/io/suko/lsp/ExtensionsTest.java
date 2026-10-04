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

    @Test
    void oversizedManifestFallsBackToBuiltInWithNotice(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("src/main/suko"));
        Path manifest = root.resolve("build/suko/extensions.json");
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, "{\"classpath\":[\"" + "x".repeat((int) ProjectExtensions.MAX_MANIFEST_BYTES + 10) + "\"]}");
        var loaded = ProjectExtensions.forProject(root.resolve("src/main/suko"), root, true);
        assertEquals(List.of("jte"), loaded.targets());
        assertTrue(loaded.registry().target("demo").isEmpty());
        assertTrue(loaded.notice().orElseThrow().contains("ignorado"), loaded.notice().toString());
    }

    @Test
    void invalidEntriesAreRejectedAndTheNoticeIsBounded(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("src/main/suko"));
        List<String> bad = new java.util.ArrayList<>(List.of("relative.jar", "\\\\host\\share\\a.jar", "//host/share/a.jar",
            root.resolve("missing.jar").toString(), root.toString()));
        for (int i = 0; i < 20; i++) {
            bad.add("y".repeat(500) + i);
        }
        io.suko.lang.ext.ExtensionManifest.write(root.resolve("build/suko/extensions.json"),
            bad.stream().map(Path::of).toList(), List.of("jte"));
        var loaded = ProjectExtensions.forProject(root.resolve("src/main/suko"), root, true);
        String notice = loaded.notice().orElseThrow();
        assertTrue(notice.length() < 3000, "" + notice.length());
        assertTrue(notice.contains("e mais"), notice);
        assertTrue(loaded.registry().loadDiagnostics().isEmpty());
    }

    @Test
    void trustedFromOnlyAcceptsBooleanTrue() {
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        assertFalse(SukoWorkspaceService.trustedFrom(o));
        assertFalse(SukoWorkspaceService.trustedFrom(null));
        o.addProperty("trusted", "true");
        assertFalse(SukoWorkspaceService.trustedFrom(o));
        o.addProperty("trusted", 1);
        assertFalse(SukoWorkspaceService.trustedFrom(o));
        o.addProperty("trusted", true);
        assertTrue(SukoWorkspaceService.trustedFrom(o));
    }

    @Test
    void fingerprintChangesWithManifestAndTrust(@TempDir Path root) throws Exception {
        projectWithManifest(root, List.of(extJar()));
        Path src = root.resolve("src/main/suko");
        String a = ProjectExtensions.fingerprint(src, root, true);
        assertEquals(a, ProjectExtensions.fingerprint(src, root, true));
        assertNotEquals(a, ProjectExtensions.fingerprint(src, root, false));
        Files.writeString(root.resolve("build/suko/extensions.json"), "{\"classpath\":[],\"targets\":[\"jte\"],\"x\":1}");
        assertNotEquals(a, ProjectExtensions.fingerprint(src, root, true));
    }

    @Test
    void sourceRootOutsideTheWorkspaceIsIgnored(@TempDir Path tmp) throws Exception {
        Path folder = Files.createDirectories(tmp.resolve("a/ws"));
        Files.createDirectories(tmp.resolve("a/other"));
        Path absolute = Files.createDirectories(tmp.resolve("abs"));
        for (String value : List.of("../..", "../other", absolute.toString(), "\\\\host\\share")) {
            Files.writeString(folder.resolve("suko.json"), new com.google.gson.Gson().toJson(java.util.Map.of("sourceRoot", value)));
            assertTrue(ProjectLocator.locate(folder, "").isEmpty(), value);
            assertTrue(ProjectLocator.locate(folder, value).isEmpty(), "setting " + value);
        }
        Files.createDirectories(folder.resolve("lib"));
        Files.writeString(folder.resolve("suko.json"), "{\"sourceRoot\":\"lib\"}");
        assertEquals(folder.resolve("lib").normalize(), ProjectLocator.locate(folder, "").orElseThrow());
    }
}
