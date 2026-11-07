package wprebuild.migrator.mongo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Maps a legacy role id onto one MVP role (BS-10). Stored ids are {@code RoleId} values in
 * UPPER_CAMEL ({@code BuiltInRole}); enum names such as {@code PROJECT_EDITOR} are accepted too.
 * Several roles for one user collapse to the highest one.
 */
public final class RoleMapping {

    public static final String CAN_VIEW = "CAN_VIEW";
    public static final String CAN_COMMENT = "CAN_COMMENT";
    public static final String CAN_EDIT = "CAN_EDIT";
    public static final String CAN_MANAGE = "CAN_MANAGE";

    private static final List<String> RANK = List.of(CAN_VIEW, CAN_COMMENT, CAN_EDIT, CAN_MANAGE);

    private static final Map<String, String> EXACT = Map.ofEntries(
            Map.entry("CAN_VIEW", CAN_VIEW),
            Map.entry("CAN_COMMENT", CAN_COMMENT),
            Map.entry("CAN_EDIT", CAN_EDIT),
            Map.entry("CAN_MANAGE", CAN_MANAGE),
            Map.entry("PROJECT_VIEWER", CAN_VIEW),
            Map.entry("OBJECT_COMMENTER", CAN_COMMENT),
            Map.entry("PROJECT_EDITOR", CAN_EDIT),
            Map.entry("PROJECT_MANAGER", CAN_MANAGE),
            Map.entry("LAYOUT_EDITOR", CAN_VIEW),
            Map.entry("PROJECT_DOWNLOADER", CAN_VIEW));

    private RoleMapping() {}

    public static Optional<String> mapRole(String legacyRole) {
        String canonical = canonical(legacyRole);
        if (canonical.startsWith("ISSUE_")) {
            return Optional.of(CAN_COMMENT);
        }
        return Optional.ofNullable(EXACT.get(canonical));
    }

    public static Collapsed collapse(List<String> assignedRoles) {
        Objects.requireNonNull(assignedRoles, "assignedRoles");
        Optional<String> highest = Optional.empty();
        List<String> unknown = new ArrayList<>();
        for (String role : assignedRoles) {
            Optional<String> mapped = mapRole(role);
            if (mapped.isEmpty()) {
                unknown.add(role);
                continue;
            }
            highest = Optional.of(
                    highest.map(current -> higher(current, mapped.get())).orElseGet(mapped::get));
        }
        return new Collapsed(highest, List.copyOf(unknown));
    }

    static String higher(String left, String right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        return RANK.indexOf(left) >= RANK.indexOf(right) ? left : right;
    }

    private static String canonical(String legacyRole) {
        String trimmed = Objects.requireNonNull(legacyRole, "legacyRole").trim();
        if (trimmed.indexOf('_') >= 0) {
            return trimmed.toUpperCase(Locale.ROOT);
        }
        StringBuilder snake = new StringBuilder(trimmed.length() + 4);
        for (int index = 0; index < trimmed.length(); index++) {
            char current = trimmed.charAt(index);
            if (Character.isUpperCase(current) && index > 0) {
                snake.append('_');
            }
            snake.append(Character.toUpperCase(current));
        }
        return snake.toString();
    }

    public record Collapsed(Optional<String> role, List<String> unknownRoles) {}
}
