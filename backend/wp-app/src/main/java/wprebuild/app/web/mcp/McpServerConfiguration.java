package wprebuild.app.web.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import java.util.Objects;
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
    SearchEntitiesTool searchEntitiesTool(CommandBus bus, JsonMapper json) {
        return new SearchEntitiesTool(bus, json);
    }

    @Bean(destroyMethod = "closeGracefully")
    McpStatelessSyncServer mcpServer(HttpServletStatelessServerTransport transport, SearchEntitiesTool search) {
        return McpServer.sync(transport)
                .serverInfo("wp-ontology", AppInfo.VERSION)
                .toolCall(search.definition(), search::call)
                .build();
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
