package wprebuild.app.web;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import wprebuild.app.support.DatabaseTest;

/**
 * SPA routes serve the packaged index page. Unknown API paths stay ProblemDetail responses.
 *
 * <p>Boot's MockMvc setup does not install Spring Security's test support, so {@link WithMockUser} needs an explicit
 * configurer. Without it the request stays anonymous and the 401 filter hides whether {@code /api} fell back.
 */
@DatabaseTest
@AutoConfigureMockMvc
@Import(SpaFallbackTest.SpringSecurityMockMvc.class)
class SpaFallbackTest {

    static final String INDEX_MARKER = "spa-fallback-index";

    private final MockMvc mockMvc;

    SpaFallbackTest(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    void project_route_returns_index_with_frame_and_cache_headers() throws Exception {
        mockMvc.perform(get("/projects/abc/class"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString(INDEX_MARKER)))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, Matchers.containsString("no-cache")));
    }

    @Test
    void index_html_is_served() throws Exception {
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString(INDEX_MARKER)))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, Matchers.containsString("no-cache")));
    }

    @Test
    void missing_asset_is_not_found() throws Exception {
        mockMvc.perform(get("/assets/missing.js"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(Matchers.not(Matchers.containsString(INDEX_MARKER))));
    }

    @Test
    void anonymous_unknown_api_is_unauthorized_problem_detail() throws Exception {
        mockMvc.perform(get("/api/does-not-exist"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(content().string(Matchers.not(Matchers.containsString(INDEX_MARKER))));
    }

    @Test
    @WithMockUser
    void signed_in_unknown_api_is_not_found_problem_detail() throws Exception {
        mockMvc.perform(get("/api/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(content().string(Matchers.not(Matchers.containsString(INDEX_MARKER))));
        mockMvc.perform(get("/api"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(content().string(Matchers.not(Matchers.containsString(INDEX_MARKER))));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SpringSecurityMockMvc {

        @Bean
        MockMvcBuilderCustomizer springSecurityMockMvc() {
            return builder -> builder.apply(springSecurity());
        }
    }
}
