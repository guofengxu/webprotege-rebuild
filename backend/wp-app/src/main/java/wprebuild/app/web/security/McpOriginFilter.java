package wprebuild.app.web.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects MCP requests that carry an {@code Origin} outside {@link CorsProperties}. Requests without an
 * Origin (typical non-browser MCP clients) are allowed through.
 */
final class McpOriginFilter extends OncePerRequestFilter {

    private final CorsProperties cors;

    McpOriginFilter(CorsProperties cors) {
        this.cors = Objects.requireNonNull(cors, "cors");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin != null && !origin.isBlank() && !cors.allowedOrigins().contains(origin.strip())) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        chain.doFilter(request, response);
    }
}
