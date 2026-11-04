package wprebuild.kernel.crud;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import wprebuild.kernel.id.UserId;

/**
 * OBO id width and per-user ranges (BS-06).
 *
 * <p>These are the old {@code OboIdSuffixSettings} fields: total digits and user ranges. The IRI
 * prefix, including the trailing underscore of an OBO id, stays on {@link NewEntitySettings#iriPrefix()}.
 */
public record OboKitSettings(int totalDigits, List<UserIdRange> userRanges) implements KitSettings {

    /** Same default width as the old settings object. */
    public static final int DEFAULT_TOTAL_DIGITS = 7;

    /** Stops a huge width from allocating an unbounded padding string. */
    public static final int MAX_TOTAL_DIGITS = 64;

    public OboKitSettings {
        if (totalDigits < 1 || totalDigits > MAX_TOTAL_DIGITS) {
            throw new IllegalArgumentException(
                    "total digits must be from 1 to " + MAX_TOTAL_DIGITS + ": " + totalDigits);
        }
        List<UserIdRange> copied = List.copyOf(Objects.requireNonNull(userRanges, "user ranges"));
        Set<UserId> seen = new HashSet<>();
        for (UserIdRange range : copied) {
            if (!seen.add(range.userId())) {
                throw new IllegalArgumentException(
                        "duplicate user id range: " + range.userId().value());
            }
        }
        userRanges = copied;
    }

    public static OboKitSettings defaults() {
        return new OboKitSettings(DEFAULT_TOTAL_DIGITS, List.of());
    }
}
