package wprebuild.kernel.id;

import java.util.Objects;

/**
 * Ordinal of one project revision.
 *
 * <p>BS-03: numbering starts at 1. {@link #ZERO} is an empty project that has no revisions yet.
 */
public record RevisionNumber(long value) implements Comparable<RevisionNumber> {

    /** BS-03: an empty project, before the first revision. */
    public static final RevisionNumber ZERO = new RevisionNumber(0);

    public RevisionNumber {
        if (value < 0) {
            throw new IllegalArgumentException("revision number must be >= 0: " + value);
        }
    }

    public static RevisionNumber of(long value) {
        return new RevisionNumber(value);
    }

    public RevisionNumber next() {
        if (value == Long.MAX_VALUE) {
            throw new IllegalArgumentException("revision number has no successor: " + value);
        }
        return new RevisionNumber(value + 1);
    }

    public boolean isZero() {
        return value == 0;
    }

    public boolean isAfter(RevisionNumber other) {
        return value > Objects.requireNonNull(other, "revision number").value();
    }

    @Override
    public int compareTo(RevisionNumber other) {
        return Long.compare(
                value, Objects.requireNonNull(other, "revision number").value());
    }
}
