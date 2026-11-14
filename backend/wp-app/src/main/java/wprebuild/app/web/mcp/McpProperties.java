package wprebuild.app.web.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** MCP transport settings. {@code wp.mcp.max-result-bytes} caps serialized tool payloads (default 256 KiB). */
@ConfigurationProperties(prefix = "wp.mcp")
public record McpProperties(@DefaultValue("262144") int maxResultBytes) {

    public McpProperties {
        if (maxResultBytes < 1) {
            throw new IllegalArgumentException("wp.mcp.max-result-bytes must be at least 1");
        }
    }
}
