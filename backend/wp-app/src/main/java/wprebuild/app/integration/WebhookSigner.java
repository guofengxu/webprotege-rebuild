package wprebuild.app.integration;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HMAC-SHA256 signing for webhook request bodies (BS-15). Pure functions; never logs the secret. */
public final class WebhookSigner {

    private static final String ALGORITHM = "HmacSHA256";

    private WebhookSigner() {}

    /** Lower-case hex digest of {@code HMAC-SHA256(secret, body)}. */
    public static String signatureHex(String secret, byte[] body) {
        Objects.requireNonNull(secret, "secret");
        Objects.requireNonNull(body, "body");
        if (secret.isBlank()) {
            throw new IllegalArgumentException("secret must not be blank");
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC-SHA256 failed", ex);
        }
    }

    /** Header value {@code sha256=<hex>}. */
    public static String headerValue(String secret, byte[] body) {
        return "sha256=" + signatureHex(secret, body);
    }
}
