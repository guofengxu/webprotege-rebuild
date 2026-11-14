package wprebuild.app.web.mcp;

/**
 * Uniform MCP tool payload. {@code result} mirrors the matching REST body; truncation is filled by later
 * cards (this PoC always leaves {@code truncated} false and {@code hint} null).
 */
public record McpToolResult(Object result, boolean truncated, String hint) {

    public static McpToolResult of(Object result) {
        return new McpToolResult(result, false, null);
    }
}
