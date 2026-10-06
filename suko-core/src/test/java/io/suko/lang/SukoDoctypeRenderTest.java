package io.suko.lang;

import io.suko.lang.support.JteRenderSupport;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ponta a ponta: o doctype é a primeira saída do .jte renderizado pelo gg.jte real. */
class SukoDoctypeRenderTest {

    @Test
    void doctypeIsTheVeryFirstOutputAndAvoidsQuirksMode() throws Exception {
        String html = JteRenderSupport.render(
            "component Layout() { <!DOCTYPE html> <html><body>x</body></html> }", "Layout", Map.of());

        assertTrue(html.startsWith("<!DOCTYPE html>"), html);
        Document doc = Jsoup.parse(html);
        assertEquals(Document.QuirksMode.noQuirks, doc.quirksMode());
    }

    @Test
    void doctypeWithSurroundingNewlinesStillComesFirst() throws Exception {
        String html = JteRenderSupport.render("""
            component Layout() {

              <!doctype HTML>

              <html><body>x</body></html>
            }
            """, "Layout", Map.of());

        assertTrue(html.startsWith("<!DOCTYPE html>"), html);
        assertEquals(Document.QuirksMode.noQuirks, Jsoup.parse(html).quirksMode());
    }

    @Test
    void layoutCalledFromAnotherComponentEmitsDoctypeOnce() throws Exception {
        String html = JteRenderSupport.renderWithDependencies("""
            component Layout(Component children) {
              <!DOCTYPE html>
              <html><body>${children}</body></html>
            }
            component Page() {
              Layout() {
                <p>conteudo</p>
              }
            }
            """, "Page", Map.of());

        assertTrue(html.startsWith("<!DOCTYPE html>"), html);
        assertEquals(1, html.split("(?i)<!DOCTYPE", -1).length - 1, html);
        assertTrue(html.contains("<p>conteudo</p>"), html);
        assertEquals(Document.QuirksMode.noQuirks, Jsoup.parse(html).quirksMode());
    }
}
