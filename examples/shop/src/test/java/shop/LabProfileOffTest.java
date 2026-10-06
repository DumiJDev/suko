package shop;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Sem o perfil {@code lab} (o por omissão) nenhuma página do lab existe. */
@SpringBootTest
@AutoConfigureMockMvc
class LabProfileOffTest {

    @Autowired MockMvc mvc;
    @Autowired ApplicationContext context;

    @Test
    void labPagesAre404WithoutTheLabProfile() throws Exception {
        for (String url : List.of("/lab", "/lab/", "/lab/links?u=x", "/lab/srcset?s=x", "/lab/style?pct=1", "/lab/embed?v=x",
            "/lab/rel?rel=x", "/lab/trusted?site=repo")) {
            mvc.perform(get(java.net.URI.create(url))).andExpect(status().isNotFound());
        }
        mvc.perform(post("/lab/items").with(csrf()).param("value", "x")).andExpect(status().isNotFound());
        assertFalse(context.containsBean("labController"));
    }
}
