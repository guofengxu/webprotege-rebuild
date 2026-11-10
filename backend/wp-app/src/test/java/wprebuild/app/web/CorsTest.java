package wprebuild.app.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import wprebuild.app.support.DatabaseTest;

class CorsTest {

    private static final String ORIGIN = "http://127.0.0.1:5173";

    @Nested
    @DatabaseTest
    @AutoConfigureMockMvc
    class WhenOriginsAreNotConfigured {

        @Autowired
        private MockMvc mockMvc;

        @Test
        void preflight_is_rejected() throws Exception {
            mockMvc.perform(preflight(ORIGIN))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        }
    }

    @Nested
    @DatabaseTest
    @AutoConfigureMockMvc
    @TestPropertySource(properties = "wp.cors.allowed-origins=http://127.0.0.1:5173")
    class WhenAnOriginIsConfigured {

        @Autowired
        private MockMvc mockMvc;

        @Test
        void preflight_from_the_configured_origin_is_allowed() throws Exception {
            mockMvc.perform(preflight(ORIGIN))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN))
                    .andExpect(header().string(
                                    HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                                    Matchers.containsString("Authorization")));
        }

        @Test
        void another_origin_is_rejected_on_preflight_and_on_the_response() throws Exception {
            mockMvc.perform(preflight("https://evil.example"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
            mockMvc.perform(get("/api/auth/me").header(HttpHeaders.ORIGIN, "https://evil.example"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        }

        @Test
        void error_response_for_the_configured_origin_is_readable() throws Exception {
            mockMvc.perform(get("/api/auth/me").header(HttpHeaders.ORIGIN, ORIGIN))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN));
        }
    }

    private static MockHttpServletRequestBuilder preflight(String origin) {
        return options("/api/auth/tokens")
                .header(HttpHeaders.ORIGIN, origin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization");
    }
}
