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
}
