package wprebuild.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AppInfoTest {

    @Test
    void name_is_not_blank() {
        assertThat(AppInfo.NAME).isNotBlank();
    }
}
