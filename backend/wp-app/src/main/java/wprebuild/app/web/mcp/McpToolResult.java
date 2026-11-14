package wprebuild.app.web.mcp;

/**
 * Uniform MCP tool payload. {@code result} mirrors the matching REST body; {@link McpResultLimits} may set
 * {@code truncated} and {@code hint} when the serialized payload exceeds {@code wp.mcp.max-result-bytes}.
 */
public record McpToolResult(Object result, boolean truncated, String hint) {

    public static McpToolResult of(Object result) {
        return new McpToolResult(result, false, null);
    }
}
