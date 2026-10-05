package io.suko.lang.maven;

import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SukoCompileMojoSecurityTest {

    private SukoCompileMojo mojo(Path dir, String artifactId) throws Exception {
        SukoCompileMojo m = new SukoCompileMojo();
        m.sourceDir = dir.resolve("src").toFile();
        m.outputDir = dir.resolve("out").toFile();
        m.buildDirectory = dir.resolve("target").toFile();
        m.generatedJavaDir = dir.resolve("target/generated-sources/suko").toFile();
        m.artifactIdForTests = artifactId;
        return m;
    }

    @Test
    void generatesSukoSafeAndWiresTheSourceRootAndAudits(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("src/ui"));
        Files.writeString(dir.resolve("src/ui/Link.sk"),
            "package ui;\npublic component Link(String url, String e) { <a href=${url}>l</a><iframe src=${trustedUrl(e)}></iframe> }");
        SukoCompileMojo m = mojo(dir, "demo-shop");
        m.execute();

        Path safe = dir.resolve("target/generated-sources/suko/io/suko/generated/demo_shop/SukoSafe.java");
        assertTrue(Files.exists(safe), "SukoSafe.java deveria existir");
        assertTrue(Files.readString(dir.resolve("out/ui/Link.jte")).contains("io.suko.generated.demo_shop.SukoSafe.url(url)"));
        assertTrue(Files.readString(dir.resolve("target/suko/security-audit.json")).contains("TRUSTED_URL"));
    }

    @Test
    void forbiddenSchemeIsAMojoExecutionException(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("src"));
        Files.writeString(dir.resolve("src/A.sk"), "component A() { <p>x</p> }");
        SukoCompileMojo m = mojo(dir, "x");
        m.security = new SukoCompileMojo.Security();
        m.security.urlSchemes = List.of("https", "javascript");
        var e = assertThrows(MojoExecutionException.class, m::execute);
        assertTrue(e.getMessage().contains("javascript"), e.getMessage());
    }

    @Test
    void unsafeSinkFailsTheBuild(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("src"));
        Files.writeString(dir.resolve("src/A.sk"), "component A(String x) { <script>${x}</script> }");
        assertThrows(MojoExecutionException.class, () -> mojo(dir, "x").execute());
    }

    @Test
    void staleAuditIsRemovedOnErrorAndNoSourcesPaths(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("src"));
        Files.writeString(dir.resolve("src/A.sk"), "component A(String u) { <iframe src=${trustedUrl(u)}></iframe> }");
        mojo(dir, "x").execute();
        Path audit = dir.resolve("target/suko/security-audit.json");
        assertTrue(Files.exists(audit));

        // erro de compilação: auditoria antiga não sobrevive
        Files.writeString(dir.resolve("src/A.sk"), "component A(String x) { <script>${x}</script> }");
        assertThrows(MojoExecutionException.class, () -> mojo(dir, "x").execute());
        assertFalse(Files.exists(audit) && Files.readString(audit).contains("TRUSTED_URL"), "auditoria obsoleta após erro");

        // sem fontes
        Files.writeString(dir.resolve("src/A.sk"), "component A(String u) { <iframe src=${trustedUrl(u)}></iframe> }");
        mojo(dir, "x").execute();
        assertTrue(Files.exists(audit));
        SukoCompileMojo none = mojo(dir, "x");
        none.sourceDir = dir.resolve("missing").toFile();
        none.execute();
        assertFalse(Files.exists(audit), "auditoria obsoleta sem fontes");

        // configuração inválida
        mojo(dir, "x").execute();
        assertTrue(Files.exists(audit));
        SukoCompileMojo bad = mojo(dir, "x");
        bad.security = new SukoCompileMojo.Security();
        bad.security.urlSchemes = List.of("data");
        assertThrows(MojoExecutionException.class, bad::execute);
        assertFalse(Files.exists(audit), "auditoria obsoleta após configuração inválida");
    }

    @Test
    void staleSukoSafeIsRemovedWhenGeneratedPackageChangesButUserSourcesStay(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("src"));
        Files.writeString(dir.resolve("src/A.sk"), "component A(String u) { <a href=${u}>l</a> }");
        mojo(dir, "one").execute();
        Path javaDir = dir.resolve("target/generated-sources/suko");
        Path userFile = javaDir.resolve("my/Handwritten.java");
        Files.createDirectories(userFile.getParent());
        Files.writeString(userFile, "package my; class Handwritten {}");

        mojo(dir, "two").execute();
        assertFalse(Files.exists(javaDir.resolve("io/suko/generated/one/SukoSafe.java")));
        assertTrue(Files.exists(javaDir.resolve("io/suko/generated/two/SukoSafe.java")));
        assertTrue(Files.exists(userFile));
    }

    @Test
    void effectivePackageSanitisesArtifactId(@TempDir Path dir) throws Exception {
        assertEquals("io.suko.generated._9_x", mojo(dir, "9-x").effectiveGeneratedPackage());
        SukoCompileMojo m = mojo(dir, "ignored");
        m.generatedPackage = "com.acme.safe";
        assertEquals("com.acme.safe", m.effectiveGeneratedPackage());
    }

    private static final class CountingLog extends SystemStreamLog {
        int warns;

        @Override
        public void warn(CharSequence content) {
            warns++;
        }
    }

    private static Plugin jte(Xpp3Dom cfg) {
        Plugin p = new Plugin();
        p.setGroupId("gg.jte");
        p.setArtifactId("jte-maven-plugin");
        p.setConfiguration(cfg);
        return p;
    }

    @Test
    void warnsWhenJtePluginHasNoHtmlPolicyClass() {
        CountingLog log = new CountingLog();
        SukoCompileMojo.warnIfJtePolicyMissing(List.of(jte(new Xpp3Dom("configuration"))), log);
        assertEquals(1, log.warns);
    }

    @Test
    void warnsWhenJtePluginHasNoConfigurationAtAll() {
        CountingLog log = new CountingLog();
        SukoCompileMojo.warnIfJtePolicyMissing(List.of(jte(null)), log);
        assertEquals(1, log.warns);
    }

    @Test
    void silentWhenPolicyPresentOrPluginAbsent() {
        CountingLog log = new CountingLog();
        Xpp3Dom cfg = new Xpp3Dom("configuration");
        Xpp3Dom policy = new Xpp3Dom("htmlPolicyClass");
        policy.setValue("gg.jte.html.OwaspHtmlPolicy");
        cfg.addChild(policy);
        SukoCompileMojo.warnIfJtePolicyMissing(List.of(jte(cfg)), log);
        SukoCompileMojo.warnIfJtePolicyMissing(List.of(), log);
        assertEquals(0, log.warns);
    }
}
