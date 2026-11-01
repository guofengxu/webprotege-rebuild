package wprebuild.kernel;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KernelInfoTest {

    @Test
    void name_is_not_blank() {
        assertThat(KernelInfo.NAME).isNotBlank();
    }
}
