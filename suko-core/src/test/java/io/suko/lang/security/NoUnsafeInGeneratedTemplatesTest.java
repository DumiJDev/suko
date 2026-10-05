package io.suko.lang.security;

import io.suko.lang.project.SukoProjectCompiler;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Varre o .jte gerado de componentes e site reais: nunca $unsafe, @raw, nem diretivas que o emissor não produziu. */
class NoUnsafeInGeneratedTemplatesTest {

    private static final Pattern FORBIDDEN = Pattern.compile("\\$unsafe\\{|@raw\\b|@endraw\\b");

    @ParameterizedTest
    @ValueSource(strings = {"../suko-components/src/main/suko", "../suko-website/src/main/suko"})
    void generatedTemplatesAreFreeOfRawOutput(String root) {
        var result = new SukoProjectCompiler().compile(Path.of(root));
        assertTrue(result.success(), result.diagnosticsByFile().toString());
        assertFalse(result.generatedJteSources().isEmpty());
        result.generatedJteSources().forEach((path, jte) ->
            assertFalse(FORBIDDEN.matcher(jte).find(), path + " contém output cru:\n" + jte));
    }
}
