package wprebuild.app.web.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.session.SessionManagementFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.json.JsonMapper;
import wprebuild.app.access.AccessTokenService;
import wprebuild.app.access.ApiKeyService;
import wprebuild.app.access.UserService;
import wprebuild.app.web.ApiProblems;

/**
 * Session login for {@code /api/**}. {@code spa()} installs the handler that accepts the raw cookie value;
 * the repository is set afterwards so the cookie path is {@code /} and HttpOnly stays false.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(CorsProperties.class)
public class SecurityConfig {

    @Bean
    CookieCsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookiePath("/");
        return repository;
    }

    @Bean
    @Order(0)
    SecurityFilterChain apiSecurity(
            HttpSecurity http,
            CookieCsrfTokenRepository csrfTokens,
            CorsProperties cors,
            JsonMapper json,
            ApiKeyService apiKeys,
            AccessTokenService accessTokens,
            UserService users)
            throws Exception {
        http.securityMatcher("/api/**")
                .cors(config -> config.configurationSource(corsConfiguration(cors)))
                .authorizeHttpRequests(auth -> auth.requestMatchers(
                                HttpMethod.POST, "/api/auth/login", "/api/auth/tokens", "/api/auth/tokens/refresh")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .sessionManagement(session -> session.sessionFixation().changeSessionId())
                // ADR-010: a bearer token or API key is not a browser form field. The token posts carry the secret
                // in JSON, so a mobile client can call them without a CSRF cookie.
                .csrf(csrf -> csrf.spa()
                        .csrfTokenRepository(csrfTokens)
                        .ignoringRequestMatchers(
                                ApiKeyAuthenticationFilter::carriesApiKey,
                                BearerTokenAuthenticationFilter::carriesBearer)
                        .ignoringRequestMatchers(
                                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/auth/tokens"),
                                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/auth/tokens/refresh")))
                // After session management: a non-transient token here would be saved and outlive revoke.
                // Bearer runs last so an Authorization header is not replaced by a session or an API key.
                .addFilterAfter(new ApiKeyAuthenticationFilter(apiKeys, users), SessionManagementFilter.class)
                .addFilterAfter(
                        new BearerTokenAuthenticationFilter(accessTokens, users), ApiKeyAuthenticationFilter.class)
                .exceptionHandling(ex -> ex.authenticationEntryPoint(new ProblemDetailEntryPoint(json))
                        .accessDeniedHandler(new ProblemDetailAccessDenied(json)))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(cache -> cache.disable());
        return http.build();
    }

    /**
     * Pages outside {@code /api/**} are static GET. They stay anonymous, but they still need the default
     * clickjacking and content-type headers. Cache-Control is left to the resource handler so {@code index.html}
     * can be revalidated and hashed {@code assets/*} can be cached.
     */
    @Bean
    @Order(1)
    SecurityFilterChain staticContent(HttpSecurity http) throws Exception {
        http.securityMatcher("/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .headers(headers -> headers.cacheControl(cache -> cache.disable()));
        return http.build();
    }

    /**
     * Not a bean: MVC already publishes a {@link CorsConfigurationSource}, and a second one makes injection
     * ambiguous. An empty allow-list still publishes a configuration so the processor rejects the preflight;
     * a missing configuration would let the request finish as 200 without CORS headers.
     */
    private static CorsConfigurationSource corsConfiguration(CorsProperties cors) {
        CorsConfiguration configuration = new CorsConfiguration();
        if (cors.allowedOrigins().isEmpty()) {
            configuration.setAllowedOrigins(List.of());
        } else {
            configuration.setAllowedOrigins(cors.allowedOrigins());
            configuration.setAllowedMethods(List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE"));
            configuration.setAllowedHeaders(
                    List.of("Authorization", "Content-Type", "Accept", "X-XSRF-TOKEN", "X-API-Key"));
            configuration.setMaxAge(CorsProperties.preflightMaxAge());
        }
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    /** Stops Spring Boot from publishing a generated user and password. Login is the JSON endpoint. */
    @Bean
    UserDetailsService userDetailsService() {
        return username -> {
            throw new UsernameNotFoundException("sign in through POST /api/auth/login");
        };
    }

    private static final class ProblemDetailEntryPoint implements AuthenticationEntryPoint {

        private final JsonMapper json;

        private ProblemDetailEntryPoint(JsonMapper json) {
            this.json = json;
        }

        @Override
        public void commence(
                HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
                throws IOException, ServletException {
            ApiProblems.write(response, ApiProblems.authenticationRequired(), json);
        }
    }

    private static final class ProblemDetailAccessDenied implements AccessDeniedHandler {

        private final JsonMapper json;

        private ProblemDetailAccessDenied(JsonMapper json) {
            this.json = json;
        }

        @Override
        public void handle(
                HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
                throws IOException, ServletException {
            ApiProblems.write(response, ApiProblems.accessDenied(), json);
        }
    }
}
