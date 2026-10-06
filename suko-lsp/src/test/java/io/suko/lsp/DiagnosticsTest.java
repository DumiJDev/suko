package io.suko.lsp;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.FileChangeType;
import org.eclipse.lsp4j.FileEvent;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DiagnosticsTest {

    private static final String BROKEN = "component Broken() {\n  <b>😀 é</b> Missing()\n}\n";
    private static final String FIXED = "component Broken() {\n  <b>😀 é</b>\n}\n";

    @Test
    void openingAFileWithAnErrorPublishesItWithTheCompilersCodeAndAccurateRange(@TempDir Path folder) throws IOException {
        TestSupport t = new TestSupport(folder);
        t.open("Broken.sk", BROKEN);
        assertNull(t.client.lastFor(t.uri("Broken.sk")), "nada é publicado antes do debounce");

        t.scheduler.fire();

        PublishDiagnosticsParams published = t.client.lastFor(t.uri("Broken.sk"));
        assertNotNull(published);
        Diagnostic d = published.getDiagnostics().stream()
            .filter(x -> "COMPONENT_NOT_FOUND".equals(x.getCode().getLeft())).findFirst().orElseThrow();
        assertEquals(DiagnosticSeverity.Error, d.getSeverity());
        assertEquals("suko", d.getSource());
        assertTrue(d.getMessage().getLeft().contains("Missing"), d.getMessage().getLeft());

        int character = BROKEN.split("\n")[1].indexOf("Missing"); // já em unidades UTF-16
        Range range = d.getRange();
        assertEquals(1, range.getStart().getLine());
        assertEquals(character, range.getStart().getCharacter());
        assertEquals(character + "Missing()".length(), range.getEnd().getCharacter());
    }

    @Test
    void aLeadingDoctypeProducesNoDiagnostics(@TempDir Path folder) throws IOException {
        TestSupport t = new TestSupport(folder);
        t.open("Page.sk", "component Page() {\n  <!DOCTYPE html>\n  <html><body>x</body></html>\n}\n");
        t.scheduler.fire();

        PublishDiagnosticsParams published = t.client.lastFor(t.uri("Page.sk"));
        assertNotNull(published);
        assertTrue(published.getDiagnostics().isEmpty(), published.getDiagnostics().toString());
    }

    @Test
    void aMisplacedDoctypeIsReported(@TempDir Path folder) throws IOException {
        TestSupport t = new TestSupport(folder);
        t.open("Page.sk", "component Page() {\n  <p>x</p>\n  <!DOCTYPE html>\n}\n");
        t.scheduler.fire();

        PublishDiagnosticsParams published = t.client.lastFor(t.uri("Page.sk"));
        assertNotNull(published);
        assertFalse(published.getDiagnostics().isEmpty(), "um doctype fora do sítio tem de ser sinalizado");
        assertTrue(published.getDiagnostics().stream().anyMatch(d -> "INVALID_DOCTYPE".equals(d.getCode().getLeft())),
            published.getDiagnostics().toString());
    }

    @Test
    void fixingTheFileClearsItsDiagnostics(@TempDir Path folder) throws IOException {
        TestSupport t = new TestSupport(folder);
        t.open("Broken.sk", BROKEN);
        t.scheduler.fire();
        assertFalse(t.client.lastFor(t.uri("Broken.sk")).getDiagnostics().isEmpty());

        t.change("Broken.sk", FIXED);
        t.scheduler.fire();

        assertTrue(t.client.lastFor(t.uri("Broken.sk")).getDiagnostics().isEmpty());
    }

    @Test
    void severalChangesWithinTheDebounceWindowCauseASingleVerification(@TempDir Path folder) throws IOException {
        TestSupport t = new TestSupport(folder);
        t.open("A.sk", "component A() { <p>1</p> }\n");
        for (int i = 0; i < 5; i++) {
            t.change("A.sk", "component A() { <p>" + i + "</p> }\n");
        }
        assertEquals(DiagnosticsService.DEBOUNCE_MILLIS, t.scheduler.lastDelay());

        t.scheduler.fire();

        assertEquals(1, t.server.diagnostics().runs());
    }

    @Test
    void identicalDiagnosticsAreNotRepublished(@TempDir Path folder) throws IOException {
        TestSupport t = new TestSupport(folder);
        t.open("Broken.sk", BROKEN);
        t.scheduler.fire();
        int afterFirst = t.client.published.size();

        t.change("Broken.sk", BROKEN + "\n");
        t.scheduler.fire();

        // o ficheiro mudou, mas o seu conjunto de diagnósticos (com posições) é o mesmo: sem tráfego novo
        assertEquals(afterFirst, t.client.published.size());
    }

    @Test
    void errorsInAnOpenFileAppearInTheFilesThatDependOnItOnlyThroughTheirOwnDiagnostics(@TempDir Path folder) throws IOException {
        TestSupport t = new TestSupport(folder);
        t.write("ui/Card.sk", "package ui;\n\npublic component Card(Component header) {\n  <div>${header}</div>\n}\n");
        t.write("Page.sk", "import ui.Card;\n\ncomponent Page() {\n  Card()\n}\n");
        t.open("Page.sk", "import ui.Card;\n\ncomponent Page() {\n  Card()\n}\n");

        t.scheduler.fire();

        assertEquals("REQUIRED_SLOT_MISSING",
            t.client.lastFor(t.uri("Page.sk")).getDiagnostics().get(0).getCode().getLeft());
        assertTrue(t.client.lastFor(t.uri("ui/Card.sk")).getDiagnostics().isEmpty(),
            "ficheiros do projecto que não estão abertos também são verificados e publicados");
    }

    @Test
    void deletingAFileOnDiskClearsWhatWasPublishedForIt(@TempDir Path folder) throws IOException {
        TestSupport t = new TestSupport(folder);
        Path file = t.write("Broken.sk", BROKEN);
        t.open("Broken.sk", BROKEN);
        t.scheduler.fire();
        t.close("Broken.sk");
        t.scheduler.fire();
        assertFalse(t.client.lastFor(t.uri("Broken.sk")).getDiagnostics().isEmpty(),
            "fechar um ficheiro que continua em disco mantém os seus erros");

        Files.delete(file);
        t.server.getWorkspaceService().didChangeWatchedFiles(new DidChangeWatchedFilesParams(
            List.of(new FileEvent(t.uri("Broken.sk"), FileChangeType.Deleted))));
        t.scheduler.fire();

        assertTrue(t.client.lastFor(t.uri("Broken.sk")).getDiagnostics().isEmpty());
    }

    @Test
    void changesMadeOutsideTheEditorAreReverified(@TempDir Path folder) throws IOException {
        TestSupport t = new TestSupport(folder);
        t.write("A.sk", "component A() { <p>1</p> }\n");
        t.open("Other.sk", "component Other() { <p>x</p> }\n");
        t.scheduler.fire();
        assertTrue(t.client.lastFor(t.uri("A.sk")).getDiagnostics().isEmpty());

        t.write("A.sk", "component A() {\n  Missing()\n}\n");
        t.server.getWorkspaceService().didChangeWatchedFiles(new DidChangeWatchedFilesParams(
            List.of(new FileEvent(t.uri("A.sk"), FileChangeType.Changed))));
        t.scheduler.fire();

        assertEquals("COMPONENT_NOT_FOUND",
            t.client.lastFor(t.uri("A.sk")).getDiagnostics().get(0).getCode().getLeft());
    }

    @Test
    void documentsOutsideAnySourceRootAreIgnored(@TempDir Path folder) throws IOException {
        TestSupport t = new TestSupport(folder);
        t.server.getTextDocumentService().didOpen(new org.eclipse.lsp4j.DidOpenTextDocumentParams(
            new org.eclipse.lsp4j.TextDocumentItem(folder.resolve("elsewhere/X.sk").toUri().toString(), "suko", 1, "component X(")));
        t.scheduler.fire();
        assertTrue(t.client.published.isEmpty());
    }

    /** O URI do cliente (%28 %29) e o do Path (parênteses crus) descrevem o mesmo ficheiro:
     * abrir/fechar outro ficheiro não pode limpar os erros deste. */
    @Test
    void openingAnotherFileNeverClearsTheErrorsOfAFileWhoseUriIsSpelledDifferently(@TempDir Path base) throws Exception {
        Path folder = java.nio.file.Files.createDirectories(base.resolve("a(b) c"));
        TestSupport t = new TestSupport(folder);
        Path aPath = t.write("A.sk", BROKEN);
        String clientUri = aPath.toUri().toString().replace("(", "%28").replace(")", "%29");
        assertNotEquals(aPath.toUri().toString(), clientUri);

        t.server.getTextDocumentService().didOpen(new org.eclipse.lsp4j.DidOpenTextDocumentParams(
            new org.eclipse.lsp4j.TextDocumentItem(clientUri, "suko", 1, BROKEN)));
        t.scheduler.fire();
        t.open("B.sk", "component B() { <p>x</p> }\n");
        t.scheduler.fire();
        t.close("B.sk");
        t.scheduler.fire();

        for (PublishDiagnosticsParams p : t.client.published) {
            if (Workspace.pathOf(p.getUri()).equals(aPath.toAbsolutePath().normalize()) && p.getDiagnostics().isEmpty()) {
                fail("os diagnósticos de A.sk foram limpos por " + p.getUri());
            }
        }
        assertFalse(t.client.lastFor(clientUri).getDiagnostics().isEmpty());
    }
}
