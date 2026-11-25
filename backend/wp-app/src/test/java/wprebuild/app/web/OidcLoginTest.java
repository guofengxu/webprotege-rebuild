package wprebuild.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import wprebuild.app.access.UserService;
import wprebuild.app.support.DatabaseTest;
import wprebuild.app.web.security.OidcLoginSuccessHandler;

/** OIDC authorization-code login shares the same session shape as password login (P6-51). */
@DatabaseTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(
        properties = {
            "wp.oidc.enabled=true",
            "wp.oidc.providers.test.display-name=Test IdP",
            "wp.oidc.providers.test.link-by-verified-email=false",
            "wp.oidc.providers.test.auto-provision=false",
            "spring.security.oauth2.client.registration.test.provider=test",
            "spring.security.oauth2.client.registration.test.client-id=test-client",
            "spring.security.oauth2.client.registration.test.client-secret=test-secret",
            "spring.security.oauth2.client.registration.test.authorization-grant-type=authorization_code",
            "spring.security.oauth2.client.registration.test.scope=openid,profile,email",
            "spring.security.oauth2.client.registration.test.redirect-uri={baseUrl}/login/oauth2/code/{registrationId}",
            "spring.security.oauth2.client.registration.test.client-name=Test",
            "spring.security.oauth2.client.provider.test.authorization-uri=https://idp.example/auth",
            "spring.security.oauth2.client.provider.test.token-uri=https://idp.example/token",
            "spring.security.oauth2.client.provider.test.jwk-set-uri=https://idp.example/jwks",
            "spring.security.oauth2.client.provider.test.user-info-uri=https://idp.example/userinfo",
            "spring.security.oauth2.client.provider.test.user-name-attribute=sub"
        })
class OidcLoginTest {

    private static final String ISSUER = "https://idp.example";

    private final MockMvc mockMvc;

    private final UserService users;

    private final JdbcClient jdbc;

    private final SessionRegistry sessions;

    private final OidcLoginSuccessHandler successHandler;

    OidcLoginTest(
            MockMvc mockMvc,
            UserService users,
            JdbcClient jdbc,
            SessionRegistry sessions,
            OidcLoginSuccessHandler successHandler) {
        this.mockMvc = mockMvc;
        this.users = users;
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.successHandler = successHandler;
    }

    @Test
    void authorization_endpoint_redirects_with_state_and_nonce() throws Exception {
        MvcResult result = mockMvc.perform(get("/oauth2/authorization/test"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("https://idp.example/auth")))
                .andReturn();

        String location = result.getResponse().getHeader("Location");
        assertThat(location).contains("state=").contains("nonce=");
        assertThat(result.getRequest().getSession(false)).isNotNull();
    }

    @Test
    void success_handler_establishes_password_style_session() throws Exception {
        users.create("oidc-alice", "Alice", "alice@example.com", "password-12", List.of("PROJECT_CREATOR"));
        insertBinding(ISSUER, "sub-alice", "oidc-alice");

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login/oauth2/code/test");
        request.setSession(new MockHttpSession());
        MockHttpServletResponse response = new MockHttpServletResponse();

        successHandler.onAuthenticationSuccess(
                request, response, oauthToken(oidcUser("sub-alice", "alice@example.com")));

        assertThat(response.getRedirectedUrl()).isEqualTo("/");
        HttpSession session = request.getSession(false);
        assertThat(session).isNotNull();
        SecurityContext stored = (SecurityContext)
                session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(stored.getAuthentication().getName()).isEqualTo("oidc-alice");
        assertThat(stored.getAuthentication()).isInstanceOf(UsernamePasswordAuthenticationToken.class);
        assertThat(sessions.getAllSessions("oidc-alice", false)).isNotEmpty();

        mockMvc.perform(get("/api/auth/me").session((MockHttpSession) session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("oidc-alice"))
                .andExpect(jsonPath("$.displayName").value("Alice"));
    }

    @Test
    void success_handler_redirects_unlinked_without_login_state() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login/oauth2/code/test");
        request.setSession(new MockHttpSession());
        MockHttpServletResponse response = new MockHttpServletResponse();

        successHandler.onAuthenticationSuccess(
                request, response, oauthToken(oidcUser("sub-unknown", "nobody@example.com")));

        assertThat(response.getRedirectedUrl()).isEqualTo("/login?error=oidc_unlinked");
        HttpSession session = request.getSession(false);
        if (session != null) {
            assertThat(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
                    .isNull();
        }
        mockMvc.perform(get("/api/auth/me").session(new MockHttpSession())).andExpect(status().isUnauthorized());
    }

    @Test
    void providers_lists_configured_idp_when_enabled() throws Exception {
        mockMvc.perform(get("/api/auth/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("test"))
                .andExpect(jsonPath("$[0].displayName").value("Test IdP"))
                .andExpect(jsonPath("$[0].loginUrl").value("/oauth2/authorization/test"));
    }

    private void insertBinding(String issuer, String subject, String userId) {
        jdbc.sql("""
                        insert into user_identities (issuer, subject, user_id, last_login_at)
                        values (?, ?, ?, ?)
                        """)
                .param(issuer)
                .param(subject)
                .param(userId)
                .param(java.sql.Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")))
                .update();
    }

    private static OAuth2AuthenticationToken oauthToken(OidcUser user) {
        return new OAuth2AuthenticationToken(user, user.getAuthorities(), "test");
    }

    private static OidcUser oidcUser(String subject, String email) {
        Map<String, Object> claims = Map.of(
                "iss",
                ISSUER,
                "sub",
                subject,
                "email",
                email,
                "email_verified",
                true,
                "preferred_username",
                "alice",
                "name",
                "Alice",
                "iat",
                Instant.now().getEpochSecond(),
                "exp",
                Instant.now().plusSeconds(3600).getEpochSecond());
        OidcIdToken idToken =
                new OidcIdToken("id-token", Instant.now(), Instant.now().plusSeconds(3600), claims);
        return new DefaultOidcUser(AuthorityUtils.createAuthorityList("OIDC_USER"), idToken);
    }
}

/** OIDC filter chain stays off when {@code wp.oidc.enabled} is false (default). */
@DatabaseTest
@AutoConfigureMockMvc
@Transactional
class OidcLoginDisabledTest {

    private final MockMvc mockMvc;

    OidcLoginDisabledTest(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    void providers_returns_empty_when_disabled() throws Exception {
        mockMvc.perform(get("/api/auth/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void authorization_endpoint_does_not_start_oauth2_flow() throws Exception {
        MvcResult result = mockMvc.perform(get("/oauth2/authorization/test")).andReturn();
        String location = result.getResponse().getHeader("Location");
        assertThat(location == null || !location.startsWith("https://idp.example"))
                .isTrue();
        assertThat(result.getResponse().getStatus()).isNotEqualTo(302);
    }
}
