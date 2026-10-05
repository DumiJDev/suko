package io.suko.lang.maven;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * O plugin.xml deste módulo é mantido à mão (ver comentário em build.gradle.kts
 * sobre a tentativa falhada de o gerar via maven-plugin-plugin/buildscript
 * classpath) — sem este teste, um @Parameter novo/renomeado em SukoCompileMojo
 * dessincronizaria do descritor em silêncio, e mvn suko:compile continuaria a
 * "funcionar" ignorando o parâmetro (fallback para o default hardcoded no
 * campo, nunca lido do <configuration> do plugin.xml).
 */
class PluginDescriptorConsistencyTest {

    @Test
    void descriptorDeclaresCompileGoalWithSukoCompileMojoImplementation() throws Exception {
        Element mojo = compileMojoElement();
        assertEquals("io.suko.lang.maven.SukoCompileMojo", text(mojo, "implementation"));
        assertEquals("generate-sources", text(mojo, "phase"));
    }

    @Test
    void descriptorParametersMatchAnnotatedMojoFields() throws Exception {
        // org.apache.maven.plugins.annotations.Parameter tem RetentionPolicy.CLASS
        // (é lido pelo scanner de bytecode do maven-plugin-plugin, não em runtime) —
        // isAnnotationPresent() nunca a veria aqui. Todos os campos de instância
        // de SukoCompileMojo são, de facto, parâmetros @Parameter (nenhum outro
        // tipo de campo existe na classe), por isso comparamos por nome de campo.
        Set<String> annotatedFieldNames = new HashSet<>();
        for (Field field : SukoCompileMojo.class.getDeclaredFields()) {
            if (!field.isSynthetic() && !field.getName().endsWith("ForTests")) {
                // Convenção: campos package-private terminados em "ForTests" são ganchos para
                // testes de Mojo construída à mão (project == null); não são @Parameter.
                annotatedFieldNames.add(field.getName());
            }
        }

        Element mojo = compileMojoElement();
        NodeList parameterNames = mojo.getElementsByTagName("parameters")
            .item(0).getChildNodes();
        Set<String> descriptorParamNames = new HashSet<>();
        IntStream.range(0, parameterNames.getLength())
            .mapToObj(parameterNames::item)
            .filter(Element.class::isInstance)
            .map(Element.class::cast)
            .forEach(param -> descriptorParamNames.add(text(param, "name")));

        assertEquals(annotatedFieldNames, descriptorParamNames,
            "plugin.xml <parameters> desincronizado dos campos @Parameter de SukoCompileMojo");
    }

    private static Element compileMojoElement() throws Exception {
        try (InputStream in = PluginDescriptorConsistencyTest.class.getResourceAsStream(
                "/META-INF/maven/plugin.xml")) {
            assertNotNull(in, "plugin.xml não encontrado em META-INF/maven/plugin.xml");
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in);
            NodeList mojos = doc.getElementsByTagName("mojo");
            for (int i = 0; i < mojos.getLength(); i++) {
                Element mojo = (Element) mojos.item(i);
                if ("compile".equals(text(mojo, "goal"))) {
                    return mojo;
                }
            }
            throw new AssertionError("nenhum <mojo> com <goal>compile</goal> encontrado");
        }
    }

    private static String text(Element parent, String childTag) {
        NodeList nodes = parent.getElementsByTagName(childTag);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent();
    }
}
