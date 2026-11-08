package wprebuild.app.access;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Pattern;

/** {@code wpk_} plus the base62 form of 32 random bytes. The digest is SHA-256 of that whole string. */
final class ApiKeyTokens {

    static final int RANDOM_BYTES = 32;

    /** {@code ceil(256 * log(2) / log(62))}. Shorter encodings would drop leading zero bytes and collide. */
    static final int ENCODED_LENGTH = 43;

    private static final String PREFIX = "wpk_";

    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private static final BigInteger BASE = BigInteger.valueOf(ALPHABET.length());

    private static final Pattern TOKEN = Pattern.compile(PREFIX + "[0-9A-Za-z]{" + ENCODED_LENGTH + "}");

    private ApiKeyTokens() {}

    static String encode(byte[] random) {
        if (random.length != RANDOM_BYTES) {
            throw new IllegalArgumentException("api key needs 32 random bytes");
        }
        char[] digits = new char[ENCODED_LENGTH];
        Arrays.fill(digits, '0');
        BigInteger value = new BigInteger(1, random);
        for (int index = digits.length - 1; value.signum() > 0; index--) {
            if (index < 0) {
                throw new IllegalArgumentException("api key random value does not fit in 43 base62 digits");
            }
            BigInteger[] divided = value.divideAndRemainder(BASE);
            digits[index] = ALPHABET.charAt(divided[1].intValue());
            value = divided[0];
        }
        return PREFIX + new String(digits);
    }

    /** Empty when the header cannot be a key this service issued, so arbitrary input never reaches the table. */
    static Optional<byte[]> digestIfWellFormed(String presented) {
        if (presented == null || !TOKEN.matcher(presented.strip()).matches()) {
            return Optional.empty();
        }
        return Optional.of(sha256(presented.strip()));
    }

    static byte[] sha256(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required", ex);
        }
    }
}
