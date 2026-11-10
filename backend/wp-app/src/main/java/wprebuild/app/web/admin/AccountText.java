package wprebuild.app.web.admin;

import java.util.regex.Pattern;
import wprebuild.app.bus.CommandContext;

/** Bounds for accounts created or updated by an administrator. */
final class AccountText {

    static final int MIN_PASSWORD_LENGTH = 12;

    private static final Pattern USER_ID = Pattern.compile("[A-Za-z0-9._-]{2,64}");

    private static final int MAX_DISPLAY_NAME = 200;

    private static final int MAX_EMAIL = 254;

    private AccountText() {}

    static String userId(String raw) {
        if (raw == null || !USER_ID.matcher(raw).matches()) {
            throw new IllegalArgumentException("user id must match [A-Za-z0-9._-]{2,64}");
        }
        return raw;
    }

    static String displayName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("display name must not be blank");
        }
        String name = raw.strip();
        if (name.length() > MAX_DISPLAY_NAME) {
            throw new IllegalArgumentException("display name must be at most " + MAX_DISPLAY_NAME + " characters");
        }
        return name;
    }

    static String email(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String email = raw.strip();
        if (email.length() > MAX_EMAIL || containsWhitespace(email)) {
            throw new IllegalArgumentException("email is invalid");
        }
        return email;
    }

    static String password(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("password must not be blank");
        }
        if (raw.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        return raw;
    }

    static String caller(CommandContext context) {
        return forLog(context.user().value());
    }

    static String forLog(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\r', ' ').replace('\n', ' ');
    }

    private static boolean containsWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
