package wprebuild.app.integration;

import java.util.Objects;

/**
 * How {@link UpdateWebhook} should treat the stored signing secret: leave it, replace it, or clear
 * it. The raw secret never appears on {@link Webhook} or API responses.
 */
public sealed interface SecretChange permits SecretChange.Keep, SecretChange.Replace, SecretChange.Clear {

    /** Leave the stored secret unchanged. */
    record Keep() implements SecretChange {}

    /** Replace the stored secret with a new value (at least 16 characters). */
    record Replace(String secret) implements SecretChange {
        public Replace {
            Objects.requireNonNull(secret, "secret");
            if (secret.length() < 16) {
                throw new IllegalArgumentException("secret must be at least 16 characters");
            }
        }
    }

    /** Remove the stored secret. */
    record Clear() implements SecretChange {}

    static Keep keep() {
        return new Keep();
    }

    static Replace replace(String secret) {
        return new Replace(secret);
    }

    static Clear clear() {
        return new Clear();
    }
}
