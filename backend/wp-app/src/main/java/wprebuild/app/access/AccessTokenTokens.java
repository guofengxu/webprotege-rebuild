package wprebuild.app.access;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Pattern;

/** {@code wpa_} or {@code wpr_} plus the base62 form of 32 random bytes. The digest is SHA-256 of that string. */
final class AccessTokenTokens {

    static final int RANDOM_BYTES = 32;

    /** {@code ceil(256 * log(2) / log(62))}. Shorter encodings would drop leading zero bytes and collide. */
    static final int ENCODED_LENGTH = 43;

    static final String ACCESS_PREFIX = "wpa_";

    static final String REFRESH_PREFIX = "wpr_";

    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private static final BigInteger BASE = BigInteger.valueOf(ALPHABET.length());

    private static final Pattern ACCESS = Pattern.compile(ACCESS_PREFIX + "[0-9A-Za-z]{" + ENCODED_LENGTH + "}");

    private static final Pattern REFRESH = Pattern.compile(REFRESH_PREFIX + "[0-9A-Za-z]{" + ENCODED_LENGTH + "}");

    private AccessTokenTokens() {}

    static String encode(String prefix, byte[] random) {
        if (!ACCESS_PREFIX.equals(prefix) && !REFRESH_PREFIX.equals(prefix)) {
            throw new IllegalArgumentException("token prefix must be wpa_ or wpr_");
        }
        if (random.length != RANDOM_BYTES) {
            throw new IllegalArgumentException("token needs 32 random bytes");
        }
        char[] digits = new char[ENCODED_LENGTH];
        Arrays.fill(digits, '0');
        BigInteger value = new BigInteger(1, random);
        for (int index = digits.length - 1; value.signum() > 0; index--) {
            if (index < 0) {
                throw new IllegalArgumentException("token random value does not fit in 43 base62 digits");
            }
            BigInteger[] divided = value.divideAndRemainder(BASE);
            digits[index] = ALPHABET.charAt(divided[1].intValue());
            value = divided[0];
        }
        return prefix + new String(digits);
    }

    /** Empty when the header cannot be an access token this service issued. */
    static Optional<byte[]> accessDigest(String presented) {
        return digest(ACCESS, presented);
    }

    /** Empty when the value cannot be a refresh token this service issued. */
    static Optional<byte[]> refreshDigest(String presented) {
        return digest(REFRESH, presented);
    }

    static byte[] sha256(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required", ex);
        }
    }

    private static Optional<byte[]> digest(Pattern pattern, String presented) {
        if (presented == null || !pattern.matcher(presented.strip()).matches()) {
            return Optional.empty();
        }
        return Optional.of(sha256(presented.strip()));
    }
}
