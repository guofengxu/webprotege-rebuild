package wprebuild.app.web.security;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Origins allowed to call {@code /api/**} from a browser. {@code wp.cors.allowed-origins} is empty by default,
 * which leaves CORS closed.
 */
@ConfigurationProperties(prefix = "wp.cors")
public record CorsProperties(List<String> allowedOrigins) {

    public CorsProperties {
        if (allowedOrigins == null) {
            allowedOrigins = List.of();
        } else {
            allowedOrigins = allowedOrigins.stream()
                    .filter(origin -> origin != null && !origin.isBlank())
                    .map(String::strip)
                    .distinct()
                    .toList();
        }
        if (allowedOrigins.stream().anyMatch("*"::equals)) {
            throw new IllegalArgumentException("wp.cors.allowed-origins must list explicit origins");
        }
        allowedOrigins.forEach(CorsProperties::requireHttpOrigin);
    }

    private static void requireHttpOrigin(String origin) {
        boolean http = origin.startsWith("https://") || origin.startsWith("http://");
        if (!http || origin.contains("*") || origin.endsWith("/")) {
            throw new IllegalArgumentException("wp.cors.allowed-origins entries must be http(s) origins");
        }
    }

    /** An hour is long enough for H5 debug reloads and short enough to pick up a removed origin. */
    static Duration preflightMaxAge() {
        return Duration.ofHours(1);
    }
}
