package wprebuild.app.web.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import java.util.Objects;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;
import wprebuild.app.AppInfo;
import wprebuild.app.bus.CommandBus;

/**
 * Registers a synchronous, stateless MCP Streamable HTTP endpoint at {@code /mcp}. Authentication is
 * handled by the dedicated MCP security filter chain; this configuration only wires the SDK servlet.
 *
 * <p>Callers: Spring Boot component scan loads this configuration; {@link ServletRegistrationBean}
 * mounts the servlet; {@link wprebuild.app.web.security.SecurityConfig} protects {@code /mcp}.
 */
@Configuration
@EnableConfigurationProperties(McpProperties.class)
public class McpServerConfiguration {

    @Bean
    HttpServletStatelessServerTransport mcpTransport() {
        return HttpServletStatelessServerTransport.builder()
                .jsonMapper(McpJsonDefaults.getMapper())
                .messageEndpoint("/mcp")
                .contextExtractor(McpUserContext::extract)
                .build();
    }

    @Bean
    McpResultLimits mcpResultLimits(JsonMapper json, McpProperties properties) {
        return new McpResultLimits(json, properties);
    }

    @Bean
    SearchEntitiesTool searchEntitiesTool(CommandBus bus, JsonMapper json, McpResultLimits limits) {
        return new SearchEntitiesTool(bus, json, limits);
    }

    @Bean
    BrowseTools browseTools(CommandBus bus, JsonMapper json, McpResultLimits limits) {
        return new BrowseTools(bus, json, limits);
    }

    @Bean(destroyMethod = "closeGracefully")
    McpStatelessSyncServer mcpServer(
            HttpServletStatelessServerTransport transport, SearchEntitiesTool search, BrowseTools browse) {
        // Tool handlers map IllegalArgumentException to VALIDATION_FAILED; disable SDK schema
        // rejection so missing/invalid arguments share that error envelope.
        var builder = McpServer.sync(transport)
                .serverInfo("wp-ontology", AppInfo.VERSION)
                .validateToolInputs(false);
        builder.toolCall(search.definition(), search::call);
        for (BrowseTools.ToolBinding binding : browse.bindings()) {
            builder.toolCall(binding.definition(), binding.handler());
        }
        return builder.build();
    }

    @Bean
    ServletRegistrationBean<HttpServletStatelessServerTransport> mcpServlet(
            HttpServletStatelessServerTransport transport, McpStatelessSyncServer server) {
        // Depend on the built server so setMcpHandler runs before the servlet accepts traffic.
        Objects.requireNonNull(server, "mcp server");
        ServletRegistrationBean<HttpServletStatelessServerTransport> registration =
                new ServletRegistrationBean<>(transport, "/mcp");
        registration.setName("mcpServlet");
        registration.setLoadOnStartup(1);
        return registration;
    }
}
