package wprebuild.instances.app.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import wprebuild.instances.app.InstancesApiKeyService;

/**
 * Stateless API-key security for the instance-store service (P6-58).
 *
 * <p>{@code /api/**} requires a valid {@code X-API-Key}. Actuator liveness (and readiness for probes)
 * stay anonymous. No session, form login, or editor Bearer tokens.
 */
@Configuration
@EnableWebSecurity
class InstancesSecurityConfig {

    @Bean
    InstancesApiKeyAuthenticationFilter instancesApiKeyAuthenticationFilter(InstancesApiKeyService apiKeys) {
        return new InstancesApiKeyAuthenticationFilter(apiKeys);
    }

    @Bean
    SecurityFilterChain instancesSecurityFilterChain(
            HttpSecurity http, InstancesApiKeyAuthenticationFilter apiKeyFilter) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.requestMatchers(
                                "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness")
                        .permitAll()
                        // Boot forwards failed responses here; locking it causes 401 instead of the real status.
                        .requestMatchers("/error")
                        .permitAll()
                        .requestMatchers("/api/**")
                        .authenticated()
                        .anyRequest()
                        .denyAll())
                .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
