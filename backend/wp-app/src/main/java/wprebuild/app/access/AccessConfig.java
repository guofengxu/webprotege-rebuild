package wprebuild.app.access;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Password hashing, token lifetimes, and the clock used to expire access tokens (ADR-010). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({BootstrapAdminProperties.class, TokenProperties.class})
class AccessConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /** Tests replace this bean when a token must expire without waiting. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
