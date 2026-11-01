package wprebuild.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import wprebuild.app.support.DatabaseTest;

@DatabaseTest
class FlywayBaselineTest {

    private final JdbcClient jdbcClient;

    FlywayBaselineTest(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Test
    void schema_version_is_1() {
        String value = jdbcClient
                .sql("select value from app_info where key = 'schema'")
                .query(String.class)
                .single();
        assertThat(value).isEqualTo("1");
    }
}
