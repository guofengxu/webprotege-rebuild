package wprebuild.kernel.crud;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.UUID;

/** Unsigned base62 of a UUID. The alphabet is {@code A-Z}, {@code a-z}, {@code 0-9}. */
public final class Base62 {

    private static final char[] ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    private static final BigInteger BASE = BigInteger.valueOf(ALPHABET.length);

    private Base62() {}

    /**
     * Encodes all 16 bytes. The high bit is not a sign, so two UUIDs that differ only there stay
     * distinct.
     */
    public static String encode(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        byte[] bytes = new byte[16];
        ByteBuffer.wrap(bytes).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits());
        BigInteger value = new BigInteger(1, bytes);
        if (value.signum() == 0) {
            return "A";
        }
        StringBuilder encoded = new StringBuilder();
        while (value.signum() > 0) {
            BigInteger[] divided = value.divideAndRemainder(BASE);
            encoded.append(ALPHABET[divided[1].intValue()]);
            value = divided[0];
        }
        return encoded.reverse().toString();
    }
}
