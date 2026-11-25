package wprebuild.app.web.security;

import java.util.Map;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code wp.oidc.*} switches and per-provider binding policy. Client credentials stay under
 * {@code spring.security.oauth2.client.*}.
 */
@ConfigurationProperties(prefix = "wp.oidc")
public record OidcProperties(@DefaultValue("false") boolean enabled, Map<String, Provider> providers) {

    public OidcProperties {
        providers = providers == null ? Map.of() : Map.copyOf(providers);
    }

    public Provider provider(String registrationId) {
        Objects.requireNonNull(registrationId, "registration id");
        return providers.get(registrationId);
    }

    /**
     * Display name and resolve policy for one {@code spring.security.oauth2.client.registration} id.
     */
    public record Provider(
            String displayName,
            @DefaultValue("false") boolean linkByVerifiedEmail,
            @DefaultValue("false") boolean autoProvision) {

        public Provider {
            Objects.requireNonNull(displayName, "display name");
            if (displayName.isBlank()) {
                throw new IllegalArgumentException("wp.oidc.providers.*.display-name must not be blank");
            }
        }
    }
}
