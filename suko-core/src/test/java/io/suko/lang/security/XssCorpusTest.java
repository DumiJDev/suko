package io.suko.lang.security;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Kit de conformidade dos alvos que aceitam {@code html}: o corpus é renderizado pelo motor real
 * e o resultado é analisado por jsoup. Um alvo futuro acrescenta o seu id a {@link #targets()} e
 * o seu ramo a {@link #render}.
 */
class XssCorpusTest {

    static final String SK = """
        component Page(String payload) {
          <div title=${payload} class=${payload} id=${payload} data-x=${payload}>
            <p>${payload}</p>
            <a href=${payload}>link</a>
            <a href="/p/${payload}">interpolado</a>
            <img src=${payload} srcset=${payload} alt=${payload}/>
            <form action=${payload}><input value=${payload}/></form>
            <a href="/x" ping=${payload}>ping</a>
            <iframe src="https://www.youtube.com/embed/${payload}"></iframe>
            <div style="--pct: ${payload}%">estilo</div>
            <textarea>${payload}</textarea>
            <title>${payload}</title>
          </div>
        }
        """;

    static List<String> targets() {
        return List.of("jte");
    }

    static String render(String targetId, String payload) throws Exception {
        return switch (targetId) {
            case "jte" -> RenderHarness.render(SK, "Page", Map.of("payload", payload));
            default -> throw new IllegalArgumentException("alvo sem renderizador: " + targetId);
        };
    }

    static Stream<Arguments> cases() throws Exception {
        List<String> payloads;
        try (InputStream in = XssCorpusTest.class.getResourceAsStream("/security/xss-corpus.txt")) {
            assertNotNull(in, "xss-corpus.txt em falta");
            payloads = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                .filter(l -> !l.isBlank())
                .map(XssCorpusTest::decode)
                .toList();
        }
        assertTrue(payloads.size() >= 60, "corpus demasiado pequeno: " + payloads.size());
        return targets().stream().flatMap(t -> payloads.stream().map(p -> Arguments.of(t, p)));
    }

    static String decode(String line) {
        return line.replace("\\t", "\t").replace("\\n", "\n").replace("\\u0000", "\u0000")
            .replace("\\u0001", "\u0001").replace("\\u2028", " ");
    }

    @ParameterizedTest(name = "[{0}] {1}")
    @MethodSource("cases")
    void renderedPageContainsNoActiveContentFromThePayload(String targetId, String payload) throws Exception {
        String html = render(targetId, payload);
        Document doc = Jsoup.parse(html);

        assertTrue(doc.select("script").isEmpty(), "script injetado por: " + payload + "\n" + html);
        for (Element e : doc.getAllElements()) {
            e.attributes().forEach(a -> assertFalse(a.getKey().toLowerCase(Locale.ROOT).startsWith("on"),
                "atributo on* injetado por: " + payload + "\n" + html));
        }
        for (Element e : doc.select("[href],[src],[action],[ping],[srcset],[formaction]")) {
            for (String attr : List.of("href", "src", "action", "formaction")) {
                if (e.hasAttr(attr)) {
                    String v = e.attr(attr).replaceAll("[\\t\\n\\r]", "").stripLeading().toLowerCase(Locale.ROOT);
                    assertFalse(v.startsWith("javascript:") || v.startsWith("vbscript:") || v.startsWith("data:"),
                        attr + " perigoso para: " + payload + " -> " + e.attr(attr));
                }
            }
        }
        assertEquals(1, doc.select("iframe").size(), "iframe extra para: " + payload);
        assertEquals(1, doc.select("form").size(), "form extra para: " + payload);
        assertEquals(1, doc.select("textarea").size(), "textarea extra para: " + payload);
        assertFalse(html.contains("$unsafe") && !payload.contains("$unsafe"), html);
    }

    @ParameterizedTest(name = "[{0}] {1}")
    @MethodSource("cases")
    void payloadIsDataNeverInterpretedAsMarkupOrTemplateCode(String targetId, String payload) throws Exception {
        String html = render(targetId, payload);
        if (payload.equals("{{7*7}}") || payload.equals("${7*7}")) {
            assertFalse(html.contains("49"), html);
        }
        // O texto do <p> e do <textarea> é o payload literal (jsoup descodifica as entidades de saída).
        boolean plain = payload.chars().noneMatch(c -> c < 0x20 || c == 0x2028 || c == 0x7f);
        if (plain) {
            Document doc = Jsoup.parse(html);
            String expected = payload.replaceAll("\\s+", " ").strip();
            assertEquals(expected, doc.selectFirst("p").text().strip(), "texto do <p> alterado: " + html);
        }
    }
}
