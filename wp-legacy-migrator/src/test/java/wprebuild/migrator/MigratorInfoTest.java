package wprebuild.migrator;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MigratorInfoTest {

    @Test
    void name_is_not_blank() {
        assertThat(MigratorInfo.NAME).isNotBlank();
    }
}
