package wprebuild.app.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Known HMAC-SHA256 vectors for webhook signatures (P6-21 / BS-15). */
class WebhookSignerTest {

    @Test
    void signs_known_secret_and_body() {
        byte[] body = "{\"projectId\":\"p\",\"userId\":\"u\",\"revisionNumber\":1,\"timestamp\":0}"
                .getBytes(StandardCharsets.UTF_8);
        assertThat(WebhookSigner.signatureHex("sixteen-chars!!!", body))
                .isEqualTo("792a193d68c662f8afc92121937de62d93815e9c79f4b7982e3cb96e10ccfe34");
    }

    @Test
    void header_value_uses_sha256_prefix() {
        byte[] body = "payload".getBytes(StandardCharsets.UTF_8);
        String hex = WebhookSigner.signatureHex("secret", body);
        assertThat(WebhookSigner.headerValue("secret", body)).isEqualTo("sha256=" + hex);
        assertThat(hex).isEqualTo("b82fcb791acec57859b989b430a826488ce2e479fdf92326bd0a2e8375a42ba4");
    }

    @Test
    void rejects_null_or_blank_secret() {
        byte[] body = "x".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> WebhookSigner.signatureHex(null, body)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> WebhookSigner.signatureHex("  ", body)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebhookSigner.signatureHex("secret", null)).isInstanceOf(NullPointerException.class);
    }
}
