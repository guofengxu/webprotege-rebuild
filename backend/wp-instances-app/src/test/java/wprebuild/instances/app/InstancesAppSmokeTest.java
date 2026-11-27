package wprebuild.instances.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * PoC smoke: dual datasources, kernel then app Flyway, readiness behaviour (P6-57).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class InstancesAppSmokeTest {

    private static final String INSTANCES_DB = "wp_instances_poc";
    private static final String PROJECTION_DB = "wp_projection_poc";
    private static final UUID PROJECT_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final ParameterizedTypeReference<java.util.Map<String, Object>> HEALTH_BODY =
            new ParameterizedTypeReference<>() {};

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"));

    @LocalServerPort
    private int port;

    @Autowired
    @Qualifier("instancesDataSource")
    private DataSource instancesDataSource;

    @BeforeAll
    static void createDatabases() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("create database " + INSTANCES_DB);
            statement.execute("create database " + PROJECTION_DB);
        }
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("wp.instances.project-id", PROJECT_ID::toString);
        registry.add("wp.instances.api-key-pepper", () -> "smoke-test-pepper");
        registry.add("wp.instances.datasource.url", () -> jdbcUrl(INSTANCES_DB));
        registry.add("wp.instances.datasource.username", postgres::getUsername);
        registry.add("wp.instances.datasource.password", postgres::getPassword);
        registry.add("wp.projection.datasource.url", () -> jdbcUrl(PROJECTION_DB));
        registry.add("wp.projection.datasource.username", postgres::getUsername);
        registry.add("wp.projection.datasource.password", postgres::getPassword);
    }

    @Test
    void starts_with_kernel_tables_and_readiness_up() {
        assertThat(tableExists(instancesDataSource, "instances")).isTrue();
        assertThat(tableExists(instancesDataSource, "links")).isTrue();
        assertThat(tableExists(instancesDataSource, "flyway_schema_history")).isTrue();
        assertThat(tableExists(instancesDataSource, "flyway_schema_history_app"))
                .isTrue();
        assertThat(tableExists(instancesDataSource, "api_keys")).isTrue();

        ResponseEntity<java.util.Map<String, Object>> readiness = getHealth(port, "readiness");
        assertThat(readiness.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(readiness.getBody()).isNotNull();
        assertThat(readiness.getBody().get("status")).isEqualTo(Status.UP.getCode());

        ResponseEntity<java.util.Map<String, Object>> liveness = getHealth(port, "liveness");
        assertThat(liveness.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(liveness.getBody()).isNotNull();
        assertThat(liveness.getBody().get("status")).isEqualTo(Status.UP.getCode());
    }

    @Test
    void readiness_not_up_when_projection_unreachable() {
        SpringApplication application = new SpringApplication(InstancesApplication.class);
        try (ConfigurableApplicationContext context = application.run(
                "--server.port=0",
                "--wp.instances.project-id=" + PROJECT_ID,
                "--wp.instances.api-key-pepper=smoke-test-pepper",
                "--wp.instances.datasource.url=" + jdbcUrl(INSTANCES_DB),
                "--wp.instances.datasource.username=" + postgres.getUsername(),
                "--wp.instances.datasource.password=" + postgres.getPassword(),
                "--wp.projection.datasource.url=jdbc:postgresql://127.0.0.1:1/does_not_exist",
                "--wp.projection.datasource.username=wp",
                "--wp.projection.datasource.password=wp")) {
            int isolatedPort = context.getEnvironment().getProperty("local.server.port", Integer.class, 0);
            ResponseEntity<java.util.Map<String, Object>> readiness = getHealth(isolatedPort, "readiness");
            assertThat(readiness.getStatusCode().is2xxSuccessful()).isFalse();
            assertThat(readiness.getBody()).isNotNull();
            assertThat(readiness.getBody().get("status")).isNotEqualTo(Status.UP.getCode());

            ResponseEntity<java.util.Map<String, Object>> liveness = getHealth(isolatedPort, "liveness");
            assertThat(liveness.getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(liveness.getBody()).isNotNull();
            assertThat(liveness.getBody().get("status")).isEqualTo(Status.UP.getCode());
        }
    }

    private static ResponseEntity<java.util.Map<String, Object>> getHealth(int serverPort, String probe) {
        return RestClient.create()
                .get()
                .uri("http://localhost:" + serverPort + "/actuator/health/" + probe)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {})
                .toEntity(HEALTH_BODY);
    }

    private static String jdbcUrl(String database) {
        return postgres.getJdbcUrl().replaceFirst("/[^/]+$", "/" + database);
    }

    private static boolean tableExists(DataSource dataSource, String table) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Integer count = jdbc.query("""
                select count(*) from information_schema.tables
                where table_schema = 'public' and table_name = ?
                """, (ResultSet rs) -> rs.next() ? rs.getInt(1) : 0, table);
        return count != null && count > 0;
    }
}
