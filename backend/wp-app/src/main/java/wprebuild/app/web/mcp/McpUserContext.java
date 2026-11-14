package wprebuild.app.web.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Objects;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import wprebuild.kernel.id.UserId;

/**
 * Per-request MCP transport context. The extractor runs on the servlet request thread after API-key
 * authentication; tool handlers must read the user from this context, not from {@link SecurityContextHolder}.
 */
public final class McpUserContext {

    static final String USER_ID_KEY = "wp.userId";

    private McpUserContext() {}

    static McpTransportContext extract(HttpServletRequest request) {
        Objects.requireNonNull(request, "request");
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication.getPrincipal() == null) {
            throw new IllegalStateException("MCP request is not authenticated");
        }
        String principal = authentication.getName();
        if (principal == null || principal.isBlank()) {
            throw new IllegalStateException("MCP authentication has no user id");
        }
        return McpTransportContext.create(Map.of(USER_ID_KEY, new UserId(principal)));
    }

    static UserId requireUser(McpTransportContext context) {
        Objects.requireNonNull(context, "transport context");
        Object value = context.get(USER_ID_KEY);
        if (value instanceof UserId userId) {
            return userId;
        }
        throw new IllegalStateException("MCP transport context has no user id");
    }
}
