package wprebuild.kernel.crud;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import wprebuild.kernel.id.UserId;

/**
 * Cursor from the old OBO handler (BS-06).
 *
 * <p>A saved cursor is the last issued id, or the range start when nothing has been issued. The
 * next candidate is one greater, so the configured start itself is never issued. Users with no
 * range share one cursor that begins at zero, so their first id is 1, and that cursor has no end.
 * The old handler was rebuilt when the prefix or the suffix settings changed, which dropped the
 * cursor. A prefix, digit width, or range change does the same here. IRIs already minted by this
 * allocator stay reserved, because a change list can mint several entities before any of them is
 * in the project signature.
 */
final class OboIdAllocator {

    private final Map<UserId, RangeCursor> cursorByUser = new HashMap<>();

    private final Set<String> issuedIris = new HashSet<>();

    /** Last id issued to users with no range. Zero means none have been issued. */
    private long unassignedCursor;

    private PrefixWidth width;

    synchronized long allocate(UserId user, String iriPrefix, OboKitSettings settings, Set<String> signatureIris) {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(iriPrefix, "iri prefix");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(signatureIris, "signature");
        adopt(iriPrefix, settings.totalDigits());
        Optional<UserIdRange> range = rangeFor(user, settings);
        long id = range.isEmpty()
                ? nextUnassigned(iriPrefix, settings.totalDigits(), signatureIris)
                : nextInRange(user, range.get(), iriPrefix, settings.totalDigits(), signatureIris);
        issuedIris.add(iri(iriPrefix, settings.totalDigits(), id));
        return id;
    }

    private void adopt(String iriPrefix, int totalDigits) {
        PrefixWidth next = new PrefixWidth(iriPrefix, totalDigits);
        if (next.equals(width)) {
            return;
        }
        width = next;
        cursorByUser.clear();
        unassignedCursor = 0;
    }

    private long nextInRange(
            UserId user, UserIdRange bounds, String iriPrefix, int totalDigits, Set<String> signatureIris) {
        RangeCursor saved = cursorByUser.get(user);
        long current = saved == null || saved.start() != bounds.start() || saved.end() != bounds.end()
                ? bounds.start()
                : saved.last();
        while (true) {
            if (current == Long.MAX_VALUE) {
                throw new CannotGenerateFreshEntityIdException(user, bounds);
            }
            current++;
            if (current > bounds.end()) {
                throw new CannotGenerateFreshEntityIdException(user, bounds);
            }
            if (!taken(iriPrefix, totalDigits, current, signatureIris)) {
                cursorByUser.put(user, new RangeCursor(bounds.start(), bounds.end(), current));
                return current;
            }
        }
    }

    private long nextUnassigned(String iriPrefix, int totalDigits, Set<String> signatureIris) {
        long current = unassignedCursor;
        while (true) {
            if (current == Long.MAX_VALUE) {
                throw new IllegalStateException("no unused OBO id remains");
            }
            current++;
            if (!taken(iriPrefix, totalDigits, current, signatureIris)) {
                unassignedCursor = current;
                return current;
            }
        }
    }

    private boolean taken(String iriPrefix, int totalDigits, long id, Set<String> signatureIris) {
        String iri = iri(iriPrefix, totalDigits, id);
        return issuedIris.contains(iri) || signatureIris.contains(iri);
    }

    private static Optional<UserIdRange> rangeFor(UserId user, OboKitSettings settings) {
        for (UserIdRange range : settings.userRanges()) {
            if (range.userId().equals(user)) {
                return Optional.of(range);
            }
        }
        return Optional.empty();
    }

    static String iri(String iriPrefix, int totalDigits, long id) {
        return iriPrefix + localId(id, totalDigits);
    }

    private static String localId(long id, int totalDigits) {
        String text = Long.toString(id);
        if (text.length() >= totalDigits) {
            return text;
        }
        return "0".repeat(totalDigits - text.length()) + text;
    }

    private record PrefixWidth(String iriPrefix, int totalDigits) {}

    private record RangeCursor(long start, long end, long last) {}
}
