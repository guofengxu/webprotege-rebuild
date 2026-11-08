package wprebuild.app.web.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import tools.jackson.databind.json.JsonMapper;
import wprebuild.app.web.ApiProblems;

/**
 * Session login for {@code /api/**}. {@code spa()} installs the handler that accepts the raw cookie value;
 * the repository is set afterwards so the cookie path is {@code /} and HttpOnly stays false.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    CookieCsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookiePath("/");
        return repository;
    }

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, CookieCsrfTokenRepository csrfTokens, JsonMapper json)
            throws Exception {
        http.securityMatcher("/api/**")
                .authorizeHttpRequests(auth -> auth.requestMatchers(HttpMethod.POST, "/api/auth/login")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .sessionManagement(session -> session.sessionFixation().changeSessionId())
                .csrf(csrf -> csrf.spa().csrfTokenRepository(csrfTokens))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(new ProblemDetailEntryPoint(json))
                        .accessDeniedHandler(new ProblemDetailAccessDenied(json)))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(cache -> cache.disable());
        return http.build();
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
