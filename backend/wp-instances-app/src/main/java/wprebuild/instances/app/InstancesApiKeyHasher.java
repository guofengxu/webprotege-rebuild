package wprebuild.instances.app;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Peppered HMAC-SHA256 of the API key plaintext, stored as lowercase hex ({@code key_hash}).
 *
 * <p>Pepper comes from {@code wp.instances.api-key-pepper}. Plaintext never enters the database.
 */
final class InstancesApiKeyHasher {

    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] pepper;

    InstancesApiKeyHasher(String pepper) {
        Objects.requireNonNull(pepper, "pepper");
        if (pepper.isBlank()) {
            throw new IllegalArgumentException("wp.instances.api-key-pepper must not be blank");
        }
        this.pepper = pepper.getBytes(StandardCharsets.UTF_8);
    }

    String hash(String plaintext) {
        Objects.requireNonNull(plaintext, "plaintext");
        if (plaintext.isBlank()) {
            throw new IllegalArgumentException("api key plaintext must not be blank");
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(pepper, ALGORITHM));
            byte[] digest = mac.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            throw new IllegalStateException(ALGORITHM + " is required", ex);
        }
    }
}
